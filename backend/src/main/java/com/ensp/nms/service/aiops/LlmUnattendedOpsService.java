package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import com.ensp.nms.entity.AiopsUnattendedRun;
import com.ensp.nms.entity.AiopsUnattendedStep;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.AiopsUnattendedRunRepository;
import com.ensp.nms.repository.AiopsUnattendedStepRepository;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.AuditLogService;
import com.ensp.nms.service.llm.LlmAssistantService;
import com.ensp.nms.service.llm.LlmToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可长期运营的无人值守：LLM 规划 → 白名单执行 → 落库；失败/暂停/熔断回退规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmUnattendedOpsService {

    public static final Set<String> SAFE_AUTO_TOOLS = Set.of(
            "inspect",
            "pull_live_config",
            "refresh_device",
            "refresh_offline",
            "ack_noise",
            "dispose_incident",
            "ack_alarm",
            "get_device_summary",
            "get_topo_neighbors",
            "get_perf_snapshot",
            "get_config_diff_summary",
            "list_active_alarms_for_device",
            "run_path_hint",
            "navigate_hint",
            "search_devices",
            "get_network_overview",
            "explain_cli_output",
            "suggest_config_commands"
    );

    private final AiopsPolicyProperties policyProperties;
    private final AlarmCorrelationService alarmCorrelationService;
    private final AlarmRepository alarmRepository;
    private final DeviceRepository deviceRepository;
    private final AlarmService alarmService;
    private final LlmToolExecutor toolExecutor;
    private final LlmAssistantService llmAssistantService;
    private final AuditLogService auditLogService;
    private final AiopsUnattendedRunRepository runRepository;
    private final AiopsUnattendedStepRepository stepRepository;
    private final ObjectMapper objectMapper;
    private final AiopsPolicyStore policyStore;

    private final ConcurrentHashMap<Long, LocalDateTime> cooldownUntil = new ConcurrentHashMap<>();
    private final AtomicInteger llmFailStreak = new AtomicInteger(0);
    private volatile LocalDateTime circuitOpenUntil = null;

    public boolean isUnattended() {
        return policyProperties.isUnattendedMode();
    }

    public boolean isSafeAutoTool(String tool) {
        if (tool == null) return false;
        String t = tool.trim().toLowerCase();
        if ("restore_latest".equals(t)) return false;
        if ("backup".equals(t)) return policyProperties.isUnattendedAllowBackup();
        return SAFE_AUTO_TOOLS.contains(t);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("llmOpsMode", policyProperties.getLlmOpsMode());
        m.put("paused", policyProperties.isUnattendedPaused());
        m.put("circuitOpen", isCircuitOpen());
        m.put("circuitOpenUntil", circuitOpenUntil != null ? circuitOpenUntil.toString() : null);
        m.put("llmFailStreak", llmFailStreak.get());
        LocalDateTime day = LocalDateTime.now().minusHours(24);
        long total = runRepository.countByStartedAtAfter(day);
        long llm = runRepository.countByStartedAtAfterAndPlanSource(day, "llm");
        long rules = runRepository.countByStartedAtAfterAndPlanSource(day, "rules");
        long breaker = runRepository.countByStartedAtAfterAndPlanSource(day, "breaker");
        long success = runRepository.countByStartedAtAfterAndStatus(day, "success");
        m.put("last24hTotal", total);
        m.put("last24hLlm", llm);
        m.put("last24hRules", rules + breaker);
        m.put("last24hSuccess", success);
        m.put("last24hSuccessRate", total == 0 ? null : Math.round(success * 1000.0 / total) / 10.0);
        m.put("last24hLlmRate", total == 0 ? null : Math.round(llm * 1000.0 / total) / 10.0);
        return m;
    }

    public Map<String, Object> setPaused(boolean paused) {
        policyProperties.setUnattendedPaused(paused);
        try {
            policyStore.saveFromRuntime();
        } catch (Exception e) {
            log.warn("persist pause flag failed: {}", e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("paused", paused);
        m.put("message", paused ? "已暂停无人值守自动执行" : "已恢复无人值守");
        try {
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("aiops")
                    .action(paused ? "llm_unattended_pause" : "llm_unattended_resume")
                    .operator("aiops-unattended")
                    .targetType("aiops")
                    .status("success")
                    .summary(String.valueOf(m.get("message")))
                    .build());
        } catch (Exception ignored) { /* ignore */ }
        return m;
    }

    public Map<String, Object> runAfterCorrelateIfEnabled() {
        if (!policyProperties.isUnattendedOnCorrelate()) {
            return Map.of("ok", true, "skipped", true, "reason", "unattendedOnCorrelate=false");
        }
        return runCycle("correlate");
    }

    public List<Map<String, Object>> recentActions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiopsUnattendedRun r : runRepository.findTop20ByOrderByStartedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
            row.put("source", r.getPlanSource());
            row.put("message", r.getReason() != null ? r.getReason() : r.getStatus());
            row.put("alarmId", r.getAlarmId());
            row.put("runId", r.getId());
            row.put("status", r.getStatus());
            list.add(row);
        }
        return list;
    }

    public Page<AiopsUnattendedRun> listRuns(String planSource, Long alarmId, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        if (alarmId != null) {
            return runRepository.findByAlarmIdOrderByStartedAtDesc(alarmId, pr);
        }
        if (planSource != null && !planSource.isBlank()) {
            return runRepository.findByPlanSourceOrderByStartedAtDesc(planSource.trim(), pr);
        }
        return runRepository.findByOrderByStartedAtDesc(pr);
    }

    public Map<String, Object> getRunDetail(Long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        AiopsUnattendedRun run = runRepository.findById(id).orElse(null);
        if (run == null) {
            m.put("ok", false);
            m.put("error", "记录不存在");
            return m;
        }
        m.put("ok", true);
        m.put("run", toRunMap(run));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (AiopsUnattendedStep s : stepRepository.findByRunIdOrderBySeqAsc(id)) {
            steps.add(toStepMap(s));
        }
        m.put("steps", steps);
        return m;
    }

    public Map<String, Object> retryRun(Long id) {
        AiopsUnattendedRun old = runRepository.findById(id).orElse(null);
        if (old == null || old.getAlarmId() == null) {
            return Map.of("ok", false, "error", "无法重试");
        }
        return runForAlarmApi(old.getAlarmId(), old.getDeviceId(), "retry");
    }

    public Map<String, Object> runCycle(String trigger) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trigger", trigger != null ? trigger : "manual");
        result.put("mode", policyProperties.getLlmOpsMode());
        if (!policyProperties.isUnattendedMode()) {
            result.put("ok", true);
            result.put("skipped", true);
            result.put("reason", "当前为人工运维模式");
            result.put("processed", 0);
            return result;
        }
        if (policyProperties.isUnattendedPaused()) {
            result.put("ok", true);
            result.put("skipped", true);
            result.put("reason", "无人值守已暂停");
            result.put("processed", 0);
            return result;
        }

        int max = Math.max(1, policyProperties.getUnattendedMaxPerCycle());
        int maxSteps = Math.max(1, policyProperties.getUnattendedMaxSteps());
        List<Map<String, Object>> incidents = alarmCorrelationService.listIncidents();
        List<Map<String, Object>> pending = incidents.stream()
                .filter(r -> "pending".equals(String.valueOf(r.get("phase")))
                        || "ACTIVE".equalsIgnoreCase(String.valueOf(r.get("status"))))
                .limit(max * 2L)
                .toList();

        List<Map<String, Object>> runs = new ArrayList<>();
        int processed = 0;
        for (Map<String, Object> row : pending) {
            if (processed >= max) break;
            Long alarmId = toLong(row.get("id"));
            if (alarmId == null || isCooling(alarmId)) continue;
            Long deviceId = toLong(row.get("deviceId"));
            Map<String, Object> one = runForAlarm(alarmId, deviceId, maxSteps, trigger != null ? trigger : "cycle");
            runs.add(one);
            if (Boolean.TRUE.equals(one.get("ran"))) {
                processed++;
                markCooldown(alarmId);
            }
        }

        result.put("ok", true);
        result.put("skipped", false);
        result.put("processed", processed);
        result.put("runs", runs);
        result.put("message", processed > 0
                ? String.format("无人值守已自动处理 %d 个事件", processed)
                : "无人值守：本轮无可自动处理的待处理事件");
        result.putAll(status());

        try {
            auditLogService.record(AuditLogService.AuditRecord.builder()
                    .module("aiops")
                    .action("llm_unattended_cycle")
                    .operator("aiops-unattended")
                    .targetType("aiops")
                    .status(processed > 0 ? "success" : "skipped")
                    .summary(String.valueOf(result.get("message")))
                    .detail("trigger=" + trigger + ", processed=" + processed)
                    .build());
        } catch (Exception ignored) { /* ignore */ }
        return result;
    }

    public Map<String, Object> runForAlarmApi(Long alarmId, Long deviceId) {
        return runForAlarmApi(alarmId, deviceId, "api");
    }

    public Map<String, Object> runForAlarmApi(Long alarmId, Long deviceId, String trigger) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", policyProperties.getLlmOpsMode());
        if (!policyProperties.isUnattendedMode()) {
            result.put("ok", true);
            result.put("skipped", true);
            result.put("reason", "当前为人工运维模式");
            result.put("processed", 0);
            return result;
        }
        if (policyProperties.isUnattendedPaused()) {
            result.put("ok", true);
            result.put("skipped", true);
            result.put("reason", "无人值守已暂停");
            result.put("processed", 0);
            return result;
        }
        if (alarmId == null) {
            result.put("ok", false);
            result.put("error", "缺少 alarmId");
            return result;
        }
        if (isCooling(alarmId) && !"retry".equals(trigger)) {
            result.put("ok", true);
            result.put("skipped", true);
            result.put("reason", "冷却中");
            result.put("processed", 0);
            return result;
        }
        int maxSteps = Math.max(1, policyProperties.getUnattendedMaxSteps());
        Map<String, Object> one = runForAlarm(alarmId, deviceId, maxSteps, trigger != null ? trigger : "api");
        result.putAll(one);
        result.put("processed", Boolean.TRUE.equals(one.get("ran")) ? 1 : 0);
        if (Boolean.TRUE.equals(one.get("ran"))) {
            markCooldown(alarmId);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> runForAlarm(Long alarmId, Long deviceId, int maxSteps, String trigger) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alarmId", alarmId);
        out.put("deviceId", deviceId);
        if (!policyProperties.isUnattendedMode()) {
            out.put("ok", false);
            out.put("error", "非无人值守模式");
            return out;
        }
        Alarm alarm = alarmId != null ? alarmRepository.findById(alarmId).orElse(null) : null;
        if (alarm == null || alarm.getStatus() == Alarm.Status.CLEARED) {
            out.put("ok", true);
            out.put("skipped", true);
            out.put("reason", "告警不存在或已关闭");
            return out;
        }
        Device device = deviceId != null
                ? deviceRepository.findById(deviceId).orElse(null)
                : (alarm.getDeviceId() != null ? deviceRepository.findById(alarm.getDeviceId()).orElse(null) : null);
        Long resolvedDeviceId = device != null ? device.getId() : alarm.getDeviceId();

        String planSource = "rules";
        String reason = "规则安全计划";
        List<Map<String, Object>> toolPlan = new ArrayList<>();

        boolean useLlm = !isCircuitOpen() && !policyProperties.isUnattendedPaused();
        if (useLlm) {
            Map<String, Object> planned = llmAssistantService.planForUnattended(alarmId, resolvedDeviceId, maxSteps);
            if (Boolean.TRUE.equals(planned.get("ok")) && planned.get("tools") instanceof List<?> list && !list.isEmpty()) {
                planSource = "llm";
                reason = String.valueOf(planned.getOrDefault("reason", "LLM 规划"));
                for (Object o : list) {
                    if (o instanceof Map<?, ?> tm) {
                        toolPlan.add(new LinkedHashMap<>((Map<String, Object>) tm));
                    }
                }
                llmFailStreak.set(0);
            } else {
                noteLlmFailure();
                if (isCircuitOpen()) {
                    planSource = "breaker";
                    reason = "LLM 熔断，回退规则：" + planned.getOrDefault("error", "规划失败");
                } else {
                    planSource = "rules";
                    reason = "LLM 不可用，回退规则：" + planned.getOrDefault("error", "规划失败");
                }
            }
        } else if (isCircuitOpen()) {
            planSource = "breaker";
            reason = "熔断窗口内使用规则计划";
        }

        if (toolPlan.isEmpty()) {
            for (String name : buildSafePlan(alarm, device)) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", name);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("alarmId", alarmId);
                if (resolvedDeviceId != null) args.put("deviceId", resolvedDeviceId);
                t.put("args", args);
                t.put("params", args);
                toolPlan.add(t);
            }
        }

        AiopsUnattendedRun run = new AiopsUnattendedRun();
        run.setTriggerSource(trigger);
        run.setAlarmId(alarmId);
        run.setDeviceId(resolvedDeviceId);
        run.setPlanSource(planSource);
        run.setStatus("running");
        run.setReason(truncate(reason, 2000));
        run.setOperator("aiops-unattended");
        run.setRoundNo(1);
        run = runRepository.save(run);

        List<Map<String, Object>> steps = new ArrayList<>();
        int ran = 0;
        int seq = 0;
        boolean allOk = true;
        for (Map<String, Object> t : toolPlan) {
            if (ran >= maxSteps) break;
            String tool = String.valueOf(t.getOrDefault("name", "")).trim().toLowerCase();
            if (!isSafeAutoTool(tool)) {
                seq++;
                Map<String, Object> skip = new LinkedHashMap<>();
                skip.put("tool", tool);
                skip.put("ok", false);
                skip.put("skipped", true);
                skip.put("reason", "非安全自动工具");
                steps.add(skip);
                persistStep(run.getId(), seq, tool, Map.of(), false, "非安全自动工具", 0L);
                continue;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            Object rawArgs = t.get("args") != null ? t.get("args") : t.get("params");
            if (rawArgs instanceof Map<?, ?> am) {
                for (Map.Entry<?, ?> e : am.entrySet()) {
                    if (e.getKey() != null) args.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            args.putIfAbsent("alarmId", alarmId);
            if (resolvedDeviceId != null) args.putIfAbsent("deviceId", resolvedDeviceId);
            args.put("autoUnattended", true);
            args.put("source", "unattended");

            long t0 = System.currentTimeMillis();
            Map<String, Object> exec = toolExecutor.execute(tool, args, true);
            long elapsed = System.currentTimeMillis() - t0;
            exec.put("tool", tool);
            exec.put("elapsedMs", elapsed);
            steps.add(exec);
            seq++;
            boolean ok = Boolean.TRUE.equals(exec.get("ok"));
            persistStep(run.getId(), seq, tool, args, ok,
                    String.valueOf(exec.getOrDefault("message", exec.getOrDefault("error", ""))), elapsed);
            ran++;
            if (!ok) {
                allOk = false;
                break;
            }
        }

        run.setStepsRan(ran);
        run.setStatus(ran == 0 ? "skipped" : (allOk ? "success" : "failed"));
        run.setFinishedAt(LocalDateTime.now());
        runRepository.save(run);

        out.put("ok", true);
        out.put("ran", ran > 0);
        out.put("runId", run.getId());
        out.put("planSource", planSource);
        out.put("reason", reason);
        out.put("steps", steps);
        out.put("message", ran > 0
                ? String.format("已自动执行 %d 步（来源=%s）", ran, planSource)
                : "无步骤执行");
        return out;
    }

    /** 兼容旧调用 */
    public Map<String, Object> runForAlarm(Long alarmId, Long deviceId, int maxSteps) {
        return runForAlarm(alarmId, deviceId, maxSteps, "api");
    }

    @Transactional
    public int purgeOldRuns() {
        int days = Math.max(1, policyProperties.getUnattendedRunRetentionDays());
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        // 简化：直接按时间删 run；step 可通过孤儿清理或级联（此处先删 step 再删 run）
        List<AiopsUnattendedRun> old = runRepository.findAll().stream()
                .filter(r -> r.getStartedAt() != null && r.getStartedAt().isBefore(cutoff))
                .limit(500)
                .toList();
        if (old.isEmpty()) return 0;
        List<Long> ids = old.stream().map(AiopsUnattendedRun::getId).toList();
        stepRepository.deleteByRunIdIn(ids);
        runRepository.deleteAll(old);
        return ids.size();
    }

    private void persistStep(Long runId, int seq, String tool, Map<String, Object> args,
                             boolean ok, String message, long elapsedMs) {
        try {
            AiopsUnattendedStep s = new AiopsUnattendedStep();
            s.setRunId(runId);
            s.setSeq(seq);
            s.setTool(tool);
            s.setArgsJson(objectMapper.writeValueAsString(args != null ? args : Map.of()));
            s.setOk(ok);
            s.setMessage(truncate(message, 2000));
            s.setElapsedMs(elapsedMs);
            stepRepository.save(s);
        } catch (Exception e) {
            log.warn("persist step failed: {}", e.getMessage());
        }
    }

    private List<String> buildSafePlan(Alarm alarm, Device device) {
        List<String> plan = new ArrayList<>();
        boolean offline = (device != null && "offline".equalsIgnoreCase(String.valueOf(device.getStatus())))
                || (alarm.getTrapType() != null && "DEVICE_OFFLINE".equalsIgnoreCase(alarm.getTrapType()))
                || (alarm.getTitle() != null && alarm.getTitle().contains("设备离线"));
        boolean ackCloses = alarmService.isAckClosesAlarm(alarm);

        if (ackCloses && alarm.getStatus() == Alarm.Status.ACTIVE) {
            plan.add("ack_alarm");
            return plan;
        }
        if (offline && device != null) {
            plan.add("refresh_device");
        }
        plan.add("ack_noise");
        if (alarm.getStatus() != Alarm.Status.CLEARED) {
            plan.add("dispose_incident");
        }
        return plan;
    }

    private boolean isCircuitOpen() {
        return circuitOpenUntil != null && LocalDateTime.now().isBefore(circuitOpenUntil);
    }

    private void noteLlmFailure() {
        int streak = llmFailStreak.incrementAndGet();
        int thr = Math.max(1, policyProperties.getLlmCircuitFailThreshold());
        if (streak >= thr) {
            int mins = Math.max(1, policyProperties.getLlmCircuitMinutes());
            circuitOpenUntil = LocalDateTime.now().plusMinutes(mins);
            log.warn("LLM unattended circuit open for {} minutes after {} failures", mins, streak);
        }
    }

    private boolean isCooling(Long alarmId) {
        LocalDateTime until = cooldownUntil.get(alarmId);
        return until != null && LocalDateTime.now().isBefore(until);
    }

    private void markCooldown(Long alarmId) {
        int mins = Math.max(1, policyProperties.getUnattendedCooldownMinutes());
        cooldownUntil.put(alarmId, LocalDateTime.now().plusMinutes(mins));
    }

    private Map<String, Object> toRunMap(AiopsUnattendedRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("triggerSource", r.getTriggerSource());
        m.put("alarmId", r.getAlarmId());
        m.put("deviceId", r.getDeviceId());
        m.put("planSource", r.getPlanSource());
        m.put("status", r.getStatus());
        m.put("reason", r.getReason());
        m.put("stepsRan", r.getStepsRan());
        m.put("roundNo", r.getRoundNo());
        m.put("operator", r.getOperator());
        m.put("startedAt", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
        m.put("finishedAt", r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toStepMap(AiopsUnattendedStep s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("seq", s.getSeq());
        m.put("tool", s.getTool());
        m.put("argsJson", s.getArgsJson());
        m.put("ok", s.getOk());
        m.put("message", s.getMessage());
        m.put("elapsedMs", s.getElapsedMs());
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
