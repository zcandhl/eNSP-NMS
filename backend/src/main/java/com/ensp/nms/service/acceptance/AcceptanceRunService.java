package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptanceBatch;
import com.ensp.nms.entity.AcceptancePlan;
import com.ensp.nms.entity.Device;
import com.ensp.nms.repository.AcceptanceBatchRepository;
import com.ensp.nms.repository.AcceptancePlanRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.TopologyLinkRepository;
import com.ensp.nms.repository.TopologyNodeRepository;
import com.ensp.nms.service.ConfigComplianceService;
import com.ensp.nms.service.DeviceConfigService;
import com.ensp.nms.ssh.SshClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验收计划执行、干跑、确认、作废与批次回收。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceRunService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExpressionParser SPEL = new SpelExpressionParser();

    private final AcceptancePlanRepository planRepository;
    private final AcceptanceBatchRepository batchRepository;
    private final com.ensp.nms.repository.AcceptanceProjectRepository projectRepository;
    private final DeviceRepository deviceRepository;
    private final TopologyNodeRepository topologyNodeRepository;
    private final TopologyLinkRepository topologyLinkRepository;
    private final DeviceConfigService deviceConfigService;
    private final ConfigComplianceService complianceService;
    private final SshClient sshClient;
    private final ObjectMapper objectMapper;
    private final AcceptanceSeqService seqService;

    private final ExecutorDevicePool pool = new ExecutorDevicePool();

    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    private final Map<String, String> cancelReasons = new ConcurrentHashMap<>();

    private final Set<String> liveBatches = ConcurrentHashMap.newKeySet();

    @Value("${nms.acceptance.stale-minutes:30}")
    private long staleMinutes;

    @Value("${nms.acceptance.orphan-grace-seconds:90}")
    private long orphanGraceSeconds;

    public Map<String, Object> runPlan(Long planId, List<Long> deviceIds) {
        AcceptancePlan plan = requirePlan(planId);
        List<Map<String, Object>> checks = enabledChecks(plan);
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("方案中没有启用的检查项");
        }
        List<Device> devices = pickDevices(deviceIds);

        long seq = nextBatchSeq();
        String batchId = "B-" + String.format("%03d", seq);
        AcceptanceBatch batch = new AcceptanceBatch();
        batch.setBatchId(batchId);
        batch.setPlanId(plan.getId());
        batch.setNo(reportNo(plan, seq));
        batch.setStatus("RUNNING");
        batch.setStartedAt(LocalDateTime.now());
        batch.setItemsJson(toJson(itemsOf(checks)));
        batchRepository.save(batch);

        Thread worker = new Thread(() -> executeBatch(batchId, plan.getId(), checks, devices), "acceptance-" + batchId);
        worker.setDaemon(true);
        worker.start();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("no", batch.getNo());
        out.put("status", "RUNNING");
        return out;
    }

    public Map<String, Object> dryRun(Long planId, Long checkId, Long deviceId) {
        AcceptancePlan plan = requirePlan(planId);
        Map<String, Object> check = enabledChecks(plan).stream()
                .filter(c -> numLong(c.get("id")).equals(checkId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("检查项不存在或未启用"));
        List<Device> devices = pickDevices(deviceId == null ? null : List.of(deviceId));
        long t0 = System.currentTimeMillis();
        Map<String, Object> result = executeCheck(check, devices);
        result.put("checkId", checkId);
        result.put("code", str(check.get("code")));
        result.put("title", str(check.get("title")));
        result.put("type", str(check.get("type")));
        result.put("owner", str(check.get("owner")));
        result.put("severity", str(check.get("severity")));
        result.put("durationMs", System.currentTimeMillis() - t0);
        if (deviceId != null) {
            result.put("message", String.valueOf(result.get("message")) + "（试运行设备 " + deviceId + "）");
        }
        return result;
    }

    public Map<String, Object> confirmItem(String batchId, Long checkId, String decision, String note, String operator) {
        AcceptanceBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        List<Map<String, Object>> items = fromJson(batch.getItemsJson());
        Map<String, Object> item = items.stream()
                .filter(i -> numLong(i.get("checkId")).equals(checkId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("批次中没有该检查项"));
        if (!"PENDING".equals(item.get("status"))) {
            throw new IllegalArgumentException("该项不是待确认状态（当前 " + item.get("status") + "）");
        }
        String who = operator == null || operator.isBlank() ? "人工" : operator;
        String tail = note == null || note.isBlank() ? "" : "：" + note;
        if ("skip".equalsIgnoreCase(decision)) {
            item.put("status", "SKIPPED");
            item.put("message", "人工跳过（" + who + "，未执行故障预演）");
        } else if ("pass".equalsIgnoreCase(decision) || "fail".equalsIgnoreCase(decision)) {
            // 人工实测后记录结论：通过记PASS，不通过记FAIL，报告按此判定
            item.put("status", "pass".equalsIgnoreCase(decision) ? "PASS" : "FAIL");
            item.put("message", "人工执行确认（" + who + "，判定" + ("pass".equalsIgnoreCase(decision) ? "通过" : "不通过") + "）" + tail);
            item.put("manual", true);
        } else {
            AcceptancePlan plan = planRepository.findById(batch.getPlanId())
                    .orElseThrow(() -> new IllegalArgumentException("方案不存在"));
            Map<String, Object> check = enabledChecks(plan).stream()
                    .filter(c -> numLong(c.get("id")).equals(checkId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("检查项不存在"));
            long t0 = System.currentTimeMillis();
            Map<String, Object> result = executeCheck(check, pickDevices(null));
            item.putAll(result);
            item.put("durationMs", System.currentTimeMillis() - t0);
            item.put("message", orEmpty(item.get("message")) + "（人工发起，" + who + "）");
        }
        batch.setItemsJson(toJson(items));
        settle(batch, items);
        return getBatch(batchId);
    }

    private static String orEmpty(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public Map<String, Object> stopBatch(String batchId) {
        AcceptanceBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        if (!"RUNNING".equals(batch.getStatus())) {
            throw new IllegalArgumentException("批次不在运行中（当前 " + batch.getStatus() + "）");
        }
        List<Map<String, Object>> items = fromJson(batch.getItemsJson());
        boolean hasWaiting = items.stream().anyMatch(i -> "WAITING".equals(i.get("status")));
        if (!hasWaiting) {
            // 后台采集已跑完，批次只是停在"等待人工确认"：没有线程可感知标记，直接落定
            cancelFlags.remove(batchId);
            for (Map<String, Object> i : items) {
                if ("PENDING".equals(i.get("status"))) {
                    i.put("status", "SKIPPED");
                    i.put("message", "人工终止批次，确认项未执行");
                }
            }
            batch.setItemsJson(toJson(items));
            batch.setStatus("INTERRUPTED");
            batch.setFinishedAt(LocalDateTime.now());
            batch.setStatusMessage("人工终止（等待确认项已跳过）");
            batchRepository.save(batch);
            return getBatch(batchId);
        }
        cancelFlags.put(batchId, true);
        batch.setStatusMessage("正在终止：当前检查项结束后停止，未执行项将标记跳过");
        batchRepository.save(batch);
        return getBatch(batchId);
    }

    public Map<String, Object> voidBatch(String batchId, String reason) {
        AcceptanceBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        if (!"DONE".equals(batch.getStatus())) {
            throw new IllegalArgumentException("只有已完成的批次才能作废（当前 " + batch.getStatus() + "）");
        }
        batch.setStatus("VOIDED");
        batch.setFinishedAt(LocalDateTime.now());
        batch.setStatusMessage("已作废：" + (reason == null || reason.isBlank() ? "未填写原因" : reason));
        batchRepository.save(batch);
        return getBatch(batchId);
    }

    public void deleteBatch(String batchId) {
        AcceptanceBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        String st = batch.getStatus();
        if (!"INTERRUPTED".equals(st) && !"FAILED".equals(st)) {
            throw new IllegalArgumentException(
                    "仅「已中断/执行失败」的批次可删除；已完成批次请使用作废，以保留验收追溯记录");
        }
        cancelFlags.remove(batchId);
        batchRepository.delete(batch);
    }

    public Map<String, Object> getBatch(String batchId) {
        AcceptanceBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        List<Map<String, Object>> items = fromJson(batch.getItemsJson());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batch.getBatchId());
        out.put("planId", batch.getPlanId());
        out.put("no", batch.getNo());
        out.put("status", batch.getStatus());
        out.put("statusMessage", batch.getStatusMessage());
        out.put("startedAt", fmt(batch.getStartedAt()));
        out.put("finishedAt", fmt(batch.getFinishedAt()));
        out.put("progress", progressOf(items));
        out.put("items", items);
        return out;
    }

    public List<Map<String, Object>> listBatches(Long planId) {
        return batchRepository.findByPlanIdOrderByStartedAtDesc(planId).stream()
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("batchId", b.getBatchId());
                    m.put("planId", b.getPlanId());
                    m.put("no", b.getNo());
                    m.put("status", b.getStatus());
                    m.put("statusMessage", b.getStatusMessage());
                    m.put("startedAt", fmt(b.getStartedAt()));
                    m.put("finishedAt", fmt(b.getFinishedAt()));
                    m.put("items", fromJson(b.getItemsJson()));
                    return m;
                })
                .toList();
    }

    public List<Map<String, Object>> listRunningBatches() {
        return batchRepository.findByStatus("RUNNING").stream()
                .sorted(java.util.Comparator.comparing(AcceptanceBatch::getStartedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(b -> {
                    List<Map<String, Object>> items = fromJson(b.getItemsJson());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("batchId", b.getBatchId());
                    m.put("planId", b.getPlanId());
                    m.put("no", b.getNo());
                    m.put("status", b.getStatus());
                    m.put("statusMessage", b.getStatusMessage());
                    m.put("startedAt", fmt(b.getStartedAt()));
                    m.put("progress", progressOf(items));
                    planRepository.findById(b.getPlanId()).ifPresent(plan -> {
                        m.put("planName", plan.getName());
                        m.put("planVersion", plan.getVersion());
                        m.put("planProjectId", plan.getProjectId());
                        projectRepository.findById(plan.getProjectId())
                                .ifPresent(p -> m.put("projectName", p.getName()));
                    });
                    return m;
                })
                .toList();
    }

    private Map<String, Object> progressOf(List<Map<String, Object>> items) {
        int done = (int) items.stream()
                .filter(i -> !"WAITING".equals(i.get("status")) && !"PENDING".equals(i.get("status")))
                .count();
        int pending = (int) items.stream().filter(i -> "PENDING".equals(i.get("status"))).count();
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("done", done);
        progress.put("total", items.size());
        progress.put("pending", pending);
        String current = items.stream()
                .filter(i -> "WAITING".equals(i.get("status")))
                .map(i -> str(i.get("title")))
                .findFirst()
                .orElse("");
        if (current.isEmpty() && pending > 0) {
            current = "等待人工确认";
        }
        progress.put("current", current);
        return progress;
    }

    private void executeBatch(String batchId, Long planId, List<Map<String, Object>> checks, List<Device> devices) {
        liveBatches.add(batchId);
        try {
            AcceptanceBatch batch = batchRepository.findById(batchId).orElse(null);
            if (batch == null) {
                return;
            }
            List<Map<String, Object>> items = fromJson(batch.getItemsJson());
            for (Map<String, Object> check : checks) {
                if (cancelFlags.remove(batchId) != null) {
                    String reason = cancelReasons.remove(batchId);
                    abortBatch(batch, items, reason != null ? reason : "人工终止：后续检查项未执行");
                    log.info("验收批次 {} 已被终止（{}）", batchId, reason);
                    return;
                }
                Map<String, Object> item = items.stream()
                        .filter(i -> numLong(i.get("checkId")).equals(numLong(check.get("id"))))
                        .findFirst()
                        // 兜底：方案被前端整体回写过checks时id可能丢失，用code再匹配一次
                        // 否则该项会被静默跳过、批次停在"无执行线程"被看门狗误判中断
                        .orElseGet(() -> items.stream()
                                .filter(i -> str(i.get("code")).equals(str(check.get("code"))))
                                .findFirst()
                                .orElse(null));
                if (item == null || !"WAITING".equals(item.get("status"))) {
                    continue; // PENDING或已被处理
                }
                if (Boolean.TRUE.equals(check.get("needConfirm"))) {
                    item.put("status", "PENDING");
                    item.put("message", "需人工确认后执行（故障预演会中断接口）");
                } else {
                    long t0 = System.currentTimeMillis();
                    Map<String, Object> result = executeCheck(check, devices);
                    item.putAll(result);
                    item.put("durationMs", System.currentTimeMillis() - t0);
                }
                batch.setItemsJson(toJson(items));
                batchRepository.saveAndFlush(batch); // 逐项冻结，前端轮询即时可见
            }
            settle(batch, items);
            log.info("验收批次 {} 执行完成，状态 {}", batchId, batch.getStatus());
        } catch (Exception e) {
            log.error("验收批次 {} 执行异常", batchId, e);
            markFailed(batchId, e);
        } finally {
            // 无论正常结束还是异常，都要从"在跑集合"里摘掉，否则看门狗会一直认为它还活着
            liveBatches.remove(batchId);
            cancelFlags.remove(batchId);
            cancelReasons.remove(batchId);
        }
    }

    @Scheduled(fixedDelayString = "${nms.acceptance.watchdog-ms:60000}",
            initialDelayString = "${nms.acceptance.watchdog-initial-delay-ms:120000}")
    public void reapStaleBatches() {
        List<AcceptanceBatch> running = batchRepository.findByStatus("RUNNING");
        if (running.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (AcceptanceBatch batch : running) {
            try {
                List<Map<String, Object>> items = fromJson(batch.getItemsJson());
                boolean hasWaiting = items.stream().anyMatch(i -> "WAITING".equals(i.get("status")));
                boolean hasPending = items.stream().anyMatch(i -> "PENDING".equals(i.get("status")));
                long elapsedSec = Duration.between(batch.getStartedAt(), now).getSeconds();
                boolean live = liveBatches.contains(batch.getBatchId());

                if (!live) {
                    // 刚发起、线程还没登记：给一个宽限窗口
                    if (elapsedSec < orphanGraceSeconds) {
                        continue;
                    }
                    // 仍在执行中的项（WAITING）：SSH采集13台设备可能超过90秒宽限期
                    // 单凭"进程内无登记"就中断会把正常采集误杀，故留足采集窗口
                    if (hasWaiting && elapsedSec < orphanGraceSeconds * 6) {
                        continue;
                    }
                    if (!hasWaiting && hasPending) {
                        continue; // 等人工确认，允许长时间停留
                    }
                    interruptStale(batch, items, hasWaiting
                            ? "执行线程已退出，批次自动中断：未执行的检查项已标记跳过，如需完整结论请重新发起验收"
                            : "批次已无可执行项但状态未收敛，已自动中断");
                    log.warn("看门狗：批次 {} 无执行线程（已运行 {}），自动中断", batch.getBatchId(), elapsedText(elapsedSec));
                    continue;
                }

                if (elapsedSec > staleMinutes * 60) {
                    cancelFlags.put(batch.getBatchId(), true);
                    cancelReasons.put(batch.getBatchId(),
                            "批次运行超过 " + staleMinutes + " 分钟，已自动中断：当前检查项结束后停止，未执行项标记跳过");
                    log.warn("看门狗：批次 {} 运行超时（{} 分钟），已下达终止指令", batch.getBatchId(), staleMinutes);
                }
            } catch (Exception e) {
                log.warn("看门狗巡检批次 {} 失败：{}", batch.getBatchId(), e.getMessage());
            }
        }
    }

    private void interruptStale(AcceptanceBatch batch, List<Map<String, Object>> items, String reason) {
        for (Map<String, Object> item : items) {
            if ("WAITING".equals(item.get("status"))) {
                item.put("status", "SKIPPED");
                item.put("message", "批次自动中断，该项未执行");
            }
        }
        batch.setItemsJson(toJson(items));
        batch.setStatus("INTERRUPTED");
        batch.setFinishedAt(LocalDateTime.now());
        batch.setStatusMessage(reason);
        batchRepository.save(batch);
    }

    private static String elapsedText(long sec) {
        if (sec >= 3600) {
            return (sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分";
        }
        return sec >= 60 ? (sec / 60) + " 分" : sec + " 秒";
    }

    private void abortBatch(AcceptanceBatch batch, List<Map<String, Object>> items, String reason) {        for (Map<String, Object> item : items) {
            if ("WAITING".equals(item.get("status"))) {
                item.put("status", "SKIPPED");
                item.put("message", "人工终止，该项未执行");
            } else if ("PENDING".equals(item.get("status"))) {
                item.put("status", "SKIPPED");
                item.put("message", "人工终止，确认项未执行");
            }
        }
        batch.setItemsJson(toJson(items));
        batch.setStatus("INTERRUPTED");
        batch.setFinishedAt(LocalDateTime.now());
        batch.setStatusMessage(reason);
        batchRepository.save(batch);
    }

    private void settle(AcceptanceBatch batch, List<Map<String, Object>> items) {
        // 覆盖窗口期：终止指令在最后一项采集完成之后、收尾之前到达
        if (cancelFlags.remove(batch.getBatchId()) != null) {
            String reason = cancelReasons.remove(batch.getBatchId());
            abortBatch(batch, items, reason != null ? reason : "人工终止：后续检查项未执行");
            return;
        }
        if (allSettled(items)) {
            batch.setStatus("DONE");
            batch.setFinishedAt(LocalDateTime.now());
            batch.setStatusMessage(null);
            batchRepository.save(batch);
            return;
        }
        if (!"INTERRUPTED".equals(batch.getStatus())) {
            batch.setStatus("RUNNING");
        }
        List<String> pending = items.stream()
                .filter(i -> "PENDING".equals(i.get("status")))
                .map(i -> str(i.get("code")) + " " + str(i.get("title")))
                .toList();
        if (!pending.isEmpty()) {
            batch.setStatusMessage("等待人工确认：" + String.join("、", pending));
        }
        batchRepository.save(batch);
    }

    private void markFailed(String batchId, Exception cause) {
        try {
            batchRepository.findById(batchId).ifPresent(batch -> {
                try {
                    List<Map<String, Object>> items = fromJson(batch.getItemsJson());
                    for (Map<String, Object> item : items) {
                        if ("WAITING".equals(item.get("status"))) {
                            item.put("status", "SKIPPED");
                            item.put("message", "批次异常中止，该项未执行");
                        }
                    }
                    batch.setItemsJson(toJson(items));
                } catch (Exception snapshotError) {
                    // 快照本身损坏时也不能连累状态落库，否则批次又变成僵尸
                    log.warn("批次 {} 快照不可解析，仅更新批次状态", batchId, snapshotError);
                }
                batch.setStatus("FAILED");
                batch.setFinishedAt(LocalDateTime.now());
                String msg = cause.getMessage();
                batch.setStatusMessage("执行异常："
                        + (msg == null || msg.isBlank() ? cause.getClass().getSimpleName() : msg));
                batchRepository.save(batch);
            });
        } catch (Exception e) {
            log.error("批次 {} 失败状态落库失败", batchId, e);
        }
    }

    private long nextBatchSeq() {
        long initFrom = 0;
        String maxId = batchRepository.findMaxBatchId();
        if (maxId != null && maxId.length() > 2) {
            try {
                initFrom = Long.parseLong(maxId.substring(2));
            } catch (NumberFormatException ignored) {
                // 历史数据不规范时从0起算，序号表本身只增不减
            }
        }
        return seqService.next(AcceptanceSeqService.SCOPE_BATCH, initFrom);
    }

    private static boolean allSettled(List<Map<String, Object>> items) {
        return items.stream().noneMatch(i -> "WAITING".equals(i.get("status")) || "PENDING".equals(i.get("status")));
    }

    private Map<String, Object> executeCheck(Map<String, Object> check, List<Device> devices) {
        String type = str(check.get("type"));
        Map<String, Object> params = castMap(check.get("params"));
        Map<String, Object> ctx = new HashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        StringBuilder raw = new StringBuilder();
        List<Map<String, Object>> findings = new ArrayList<>();
        String message = null;

        // 设备范围过滤：路由类巡检不应下发到二层接入交换机
        List<Device> scoped = scopeDevices(params, devices);
        if (scoped.size() < devices.size()) {
            raw.append("# 设备范围：按方案范围执行 ").append(scoped.size())
                    .append(" 台，跳过范围外 ").append(devices.size() - scoped.size()).append(" 台\n");
        }

        try {
            switch (type) {
                case "TOPOLOGY_MATCH" -> checkTopology(params, scoped, ctx, metrics, raw);
                case "REACHABILITY" -> checkReachability(params, scoped, ctx, metrics, raw);
                case "OSPF_NEIGHBOR" -> checkOspf(params, scoped, ctx, metrics, raw);
                case "VRRP_ROLE" -> checkVrrp(params, scoped, ctx, metrics, raw);
                case "DHCP_POOL" -> checkDhcp(params, scoped, ctx, metrics, raw);
                case "VLAN_MATCH" -> checkVlan(params, scoped, ctx, metrics, raw);
                case "COMPLIANCE_SCORE" -> checkCompliance(params, scoped, ctx, metrics, raw, findings);
                case "CONFIG_DIFF" -> checkConfigDiff(params, scoped, ctx, metrics, raw);
                case "CUSTOM_COMMAND" -> checkCustom(params, scoped, ctx, metrics, raw);
                default -> throw new IllegalArgumentException("未知检查原语：" + type);
            }
        } catch (Exception e) {
            log.warn("检查项 {} 执行异常", check.get("code"), e);
            metrics.put("error", e.getMessage());
            raw.append("# 执行异常\n").append(e.getMessage());
        }

        metrics.put("scopedDevices", scoped.size());
        metrics.put("excludedDevices", devices.size() - scoped.size());
        ctx.putAll(params);
        ctx.putAll(metrics);
        ctx.put("device", scoped.stream().map(Device::getName).toList());

        boolean pass;
        String assertExpr = str(check.get("assertExpr"));
        if (metrics.containsKey("error")) {
            pass = false;
            message = "执行异常：" + metrics.get("error");
        } else {
            try {
                pass = Boolean.TRUE.equals(evaluate(normalizeAssert(assertExpr), ctx));
            } catch (Exception e) {
                pass = false;
                message = "判定表达式求值失败：" + e.getMessage();
            }
        }
        if (pass) {
            message = "判定准则满足";
        } else if (message == null || message.isBlank()) {
            message = "未达判定准则";
        }

        String status;
        if (pass) {
            status = "PASS";
        } else if (Boolean.TRUE.equals(check.get("allowFinding"))) {
            status = "FINDING";
            if (findings.isEmpty()) {
                findings.add(Map.of("code", "P-001", "device", "-",
                        "detail", message + "（实测 " + metricsJson(metrics) + "）", "severity", "medium"));
            }
        } else {
            status = "FAIL";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("metrics", metrics);
        out.put("rawOutput", raw.toString());
        out.put("message", message);
        out.put("findings", findings);
        return out;
    }

    private Object evaluate(String expr, Map<String, Object> root) {
        StandardEvaluationContext ctx = new StandardEvaluationContext(root);
        ctx.addPropertyAccessor(new MapAccessor());
        return SPEL.parseExpression(expr).getValue(ctx);
    }

    private static String normalizeAssert(String expr) {
        return expr == null ? "true" : expr.replace("min(device.complianceScore)", "minComplianceScore");
    }

    private void checkTopology(Map<String, Object> params, List<Device> scoped, Map<String, Object> ctx,
                               Map<String, Object> metrics, StringBuilder raw) {
        // 口径与拓扑视图保持一致：节点 = 本批次范围内的纳管设备。
        // topology_node只存"被拖拽过"的坐标，未拖拽的设备没有记录
        // 若拿它当节点数，会出现拓扑页画着12个节点、检查却说0个的矛盾。
        Set<Long> scopedIds = new java.util.HashSet<>();
        for (Device d : scoped) {
            if (d.getId() != null) {
                scopedIds.add(d.getId());
            }
        }
        long nodes = scopedIds.size();
        long links = 0;
        for (var link : topologyLinkRepository.findAll()) {
            if (link.getSourceNodeId() != null && link.getTargetNodeId() != null
                    && scopedIds.contains(link.getSourceNodeId())
                    && scopedIds.contains(link.getTargetNodeId())) {
                links++;
            }
        }
        long matched = nodes;
        metrics.put("total", nodes);
        metrics.put("matched", matched);
        metrics.put("links", links);
        ctx.put("topology", metrics);
        raw.append("# 拓扑采集 · 汇总\n")
                .append("拓扑节点 ").append(nodes).append(" / 链路 ").append(links).append("\n")
                .append("已关联纳管设备 ").append(matched).append(" 台\n");
        int expected = numInt(params.get("expectedNodes"), 0);
        if (nodes != expected) {
            raw.append("注意：拓扑节点数 ").append(nodes).append(" 与方案期望 ").append(expected).append(" 不一致\n");
        }
    }

    private void checkReachability(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                                   Map<String, Object> metrics, StringBuilder raw) {
        int timeoutMs = numInt(params.get("timeoutMs"), 2000);
        int up = 0;
        int total = devices.size();
        raw.append("# 连通性探测 · SSH 端口 TCP 握手\n");
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Device d : devices) {
            int port = mgmtPort(d);
            boolean ok;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(d.getIpAddress(), port), timeoutMs);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            if (ok) {
                up++;
            }
            raw.append(String.format("%-10s %-15s:%-5d %s%n", d.getName(), d.getIpAddress(), port, ok ? "UP" : "DOWN"));
            detail.add(Map.of("name", d.getName(), "up", ok));
        }
        ctx.put("detail", detail);
        metrics.put("up", up);
        metrics.put("total", total);
        ctx.put("reachability", metrics);
        if (up == 0 && total > 0) {
            raw.append("全部不可达：请检查网络桥接、设备远程管理是否开启（SSH: stelnet server enable / Telnet: telnet server enable）\n");
        }
    }

    private void checkOspf(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                           Map<String, Object> metrics, StringBuilder raw) {
        int neighborCount = 0;
        List<Device> sshDevices = devices.stream().filter(this::hasSsh).toList();
        Map<String, String> outputs = runShowParallel(sshDevices, "display ospf peer brief");
        raw.append("# OSPF 邻居采集 · display ospf peer brief\n");
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            int full = countMatches(e.getValue(), "\\bFull\\b");
            neighborCount += full;
            raw.append("\n── ").append(e.getKey()).append(" · Full 邻居 ").append(full).append("\n")
                    .append(truncate(e.getValue(), 1500));
        }
        if (sshDevices.isEmpty()) {
            raw.append("（无已配置 SSH 凭据的设备，请先在设备管理中录入）\n");
        }
        metrics.put("neighborCount", neighborCount);
        metrics.put("expected", numInt(params.get("expectedNeighbors"), 0));
        ctx.put("ospf", metrics);
    }

    private void checkVrrp(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                           Map<String, Object> metrics, StringBuilder raw) {
        int master = 0;
        int backup = 0;
        List<Device> sshDevices = devices.stream().filter(this::hasSsh).toList();
        Map<String, String> outputs = runShowParallel(sshDevices, "display vrrp brief");
        raw.append("# VRRP 角色采集 · display vrrp brief\n");
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            int m = countMatches(e.getValue(), "\\bMaster\\b");
            int b = countMatches(e.getValue(), "\\bBackup\\b");
            master += m;
            backup += b;
            raw.append("\n── ").append(e.getKey())
                    .append(" · Master ").append(m).append(" / Backup ").append(b).append("\n")
                    .append(truncate(e.getValue(), 1500));
        }
        if (sshDevices.isEmpty()) {
            raw.append("（无已配置 SSH 凭据的设备）\n");
        }
        metrics.put("masterCount", master);
        metrics.put("backupCount", backup);
        ctx.put("vrrp", metrics);
    }

    private void checkDhcp(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                           Map<String, Object> metrics, StringBuilder raw) {
        int poolCount = 0;
        raw.append("# DHCP 地址池统计 · 解析运行配置\n");
        Map<String, String> configs = pullConfigsParallel(devices);
        for (Map.Entry<String, String> e : configs.entrySet()) {
            int n = countMatches(e.getValue(), "(?m)^\\s*ip pool\\s");
            poolCount += n;
            raw.append("── ").append(e.getKey()).append(" · 地址池 ").append(n).append("\n");
        }
        metrics.put("poolCount", poolCount);
        metrics.put("expected", numInt(params.get("expectedPools"), 0));
        ctx.put("dhcp", metrics);
    }

    private void checkVlan(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                           Map<String, Object> metrics, StringBuilder raw) {
        int vlanCount = 0;
        String suffix = null;
        boolean consistent = true;
        raw.append("# VLAN 与网关统计 · 解析运行配置\n");
        Map<String, String> configs = pullConfigsParallel(devices);
        for (Map.Entry<String, String> e : configs.entrySet()) {
            String cfg = e.getValue();
            int n = countMatches(cfg, "(?m)^\\s*interface Vlanif\\d+");
            vlanCount += n;
            List<String> lastOctets = new ArrayList<>();
            Matcher m = Pattern.compile("virtual-ip\\s+\\d+\\.\\d+\\.\\d+\\.(\\d+)").matcher(cfg);
            while (m.find()) {
                lastOctets.add(m.group(1));
            }
            String local = lastOctets.isEmpty() ? null : lastOctets.get(0);
            if (local != null && suffix != null && !local.equals(suffix)) {
                consistent = false;
            }
            if (local != null && suffix == null) {
                suffix = local;
            }
            raw.append("── ").append(e.getKey()).append(" · Vlanif ").append(n)
                    .append(local != null ? " · 虚网关末位 ." + local : "").append("\n");
        }
        metrics.put("matched", vlanCount);
        metrics.put("total", vlanCount);
        metrics.put("actualSuffix", consistent ? (suffix == null ? "none" : suffix) : "mixed");
        ctx.put("vlan", metrics);
    }

    private void checkCompliance(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                                 Map<String, Object> metrics, StringBuilder raw, List<Map<String, Object>> findings) {
        int threshold = numInt(params.get("minScore"), 90);
        raw.append("# 合规扫描 · ").append(devices.size()).append(" 台设备\n");
        // 并行评估 + 无凭据设备直接跳过：逐台串行时一台失败/超时就拖慢整个批次
        List<Device> sshDevices = devices.stream().filter(this::hasSsh).toList();
        List<Device> noSsh = devices.stream().filter(d -> !hasSsh(d)).toList();
        Map<String, Map<String, Object>> byName = new HashMap<>();
        if (!sshDevices.isEmpty()) {
            List<Map.Entry<String, Map<String, Object>>> results = pool.submitAll(sshDevices, d -> {
                Map<String, Object> r;
                try {
                    r = complianceService.evaluate(d.getId(), "auto");
                } catch (Exception e) {
                    r = Map.of("deviceName", d.getName(), "score", 0, "level", "unknown",
                            "message", "评估失败：" + e.getMessage());
                }
                return Map.entry(d.getName(), r);
            });
            results.forEach(e -> byName.put(e.getKey(), e.getValue()));
        }
        int minScore = 100;
        int below = 0;
        int idx = 1;
        for (Device d : devices) {
            if (!hasSsh(d)) {
                raw.append(String.format("%-10s 未配置 SSH 凭据，跳过%n", d.getName()));
                continue;
            }
            Map<String, Object> r = byName.getOrDefault(d.getName(),
                    Map.of("deviceName", d.getName(), "score", 0, "level", "unknown", "message", "评估失败"));
            int score = numInt(r.get("score"), 0);
            if (score < minScore) {
                minScore = score;
            }
            if (score < threshold) {
                below++;
                Object failed = ((List<?>) r.getOrDefault("rules", List.of())).stream()
                        .filter(x -> x instanceof Map<?, ?> m2 && !Boolean.TRUE.equals(((Map<?, ?>) m2).get("passed")))
                        .map(x -> ((Map<?, ?>) x).get("code"))
                        .toList();
                findings.add(Map.of(
                        "code", "P-" + String.format("%03d", idx++),
                        "device", d.getName(),
                        "detail", "合规分 " + score + "，低于阈值 " + threshold + "；未通过规则 " + failed,
                        "severity", score < 70 ? "high" : "medium"));
            }
            raw.append(String.format("%-10s score %-4d %s%n", d.getName(), score, r.getOrDefault("level", "")));
        }
        if (!noSsh.isEmpty()) {
            raw.append("\n跳过 ").append(noSsh.size()).append(" 台未配置 SSH 凭据的设备\n");
        }
        // 注意：指标名不能与参数名撞车，实测最低分用lowestScore
        metrics.put("lowestScore", minScore);
        metrics.put("devices", sshDevices.size());
        metrics.put("failed", below);
        metrics.put("minComplianceScore", minScore);
        raw.append("\n最低分 ").append(minScore).append("，阈值 ").append(threshold).append("\n");
        ctx.put("compliance", metrics);
    }

    private void checkConfigDiff(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                                 Map<String, Object> metrics, StringBuilder raw) {
        int diffLines = 0;
        int noBaseline = 0;
        raw.append("# 配置基线比对 · 当前运行配置 vs 最新备份\n");
        Map<String, String> configs = pullConfigsParallel(devices);
        for (Map.Entry<String, String> e : configs.entrySet()) {
            Device d = devices.stream().filter(x -> x.getName().equals(e.getKey())).findFirst().orElse(null);
            String baseline = latestBackup(d);
            if (baseline == null || baseline.isBlank()) {
                noBaseline++;
                raw.append("── ").append(e.getKey()).append(" · 无备份基线（请先执行配置备份）\n");
                continue;
            }
            int diff = diffCount(e.getValue(), baseline);
            diffLines += diff;
            raw.append("── ").append(e.getKey()).append(" · 差异行 ").append(diff).append("\n");
        }
        metrics.put("diffLines", diffLines);
        metrics.put("devices", devices.size());
        metrics.put("noBaseline", noBaseline);
        raw.append("\n合计差异行 ").append(diffLines).append(noBaseline > 0 ? "；" + noBaseline + " 台缺基线" : "").append("\n");
        ctx.put("config", metrics);
    }

    private void checkCustom(Map<String, Object> params, List<Device> devices, Map<String, Object> ctx,
                             Map<String, Object> metrics, StringBuilder raw) {
        String command = str(params.get("command")).trim();
        String matchMode = str(params.getOrDefault("matchMode", "contains"));
        String matchValue = str(params.getOrDefault("matchValue", ""));
        if (!command.toLowerCase().startsWith("display")) {
            throw new IllegalArgumentException("自定义检查只允许 display 只读命令");
        }
        String guard = command.toLowerCase();
        for (String bad : List.of("system-view", "undo", "reboot", "save", "reset", "delete", "commit", "quit")) {
            if (guard.contains(bad)) {
                throw new IllegalArgumentException("命令含写操作关键字，已拒绝：" + bad);
            }
        }
        boolean matched = false;
        List<Device> sshDevices = devices.stream().filter(this::hasSsh).toList();
        Map<String, String> outputs = runShowParallel(sshDevices, command);
        raw.append("# 自定义命令检查 · ").append(command).append("\n");
        int matchedDevices = 0;
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            boolean m = matchOutput(e.getValue(), matchMode, matchValue);
            if (m) {
                matchedDevices++;
            }
            raw.append("\n── ").append(e.getKey()).append(" · ").append(m ? "匹配" : "不匹配").append("\n")
                    .append(truncate(e.getValue(), 1200));
        }
        matched = !outputs.isEmpty() && matchedDevices == outputs.size();
        metrics.put("matched", matched);
        metrics.put("command", command);
        metrics.put("matchedDevices", matchedDevices);
        metrics.put("totalDevices", outputs.size());
        ctx.put("custom", metrics);
    }

    private static boolean matchOutput(String output, String mode, String value) {
        if (output == null) {
            return false;
        }
        return switch (mode) {
            case "contains" -> output.contains(value);
            case "notContains" -> !output.contains(value);
            case "regex" -> Pattern.compile(value, Pattern.DOTALL).matcher(output).find();
            case "gt" -> firstNumber(output) > parseDouble(value);
            case "lt" -> firstNumber(output) < parseDouble(value);
            default -> false;
        };
    }

    private static double firstNumber(String s) {
        Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(s);
        return m.find() ? Double.parseDouble(m.group()) : Double.NEGATIVE_INFINITY;
    }

    private boolean hasSsh(Device d) {
        return d.getSshUsername() != null && !d.getSshUsername().isBlank()
                && d.getSshPassword() != null && !d.getSshPassword().isBlank();
    }

    private List<Device> scopeDevices(Map<String, Object> params, List<Device> devices) {
        java.util.Set<String> types = csvSet(params.get("deviceTypes"));
        java.util.Set<String> names = csvSet(params.get("deviceNames"));
        if (types.isEmpty() && names.isEmpty()) {
            return devices;
        }
        return devices.stream().filter(d ->
                (types.isEmpty() || types.contains(lower(d.getDeviceType())))
                        && (names.isEmpty() || names.contains(lower(d.getName())))).toList();
    }

    private static java.util.Set<String> csvSet(Object v) {
        if (v == null) {
            return java.util.Set.of();
        }
        String text = v instanceof List<?> list
                ? String.join(",", list.stream().map(String::valueOf).toList())
                : String.valueOf(v);
        if (text.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Arrays.stream(text.split("[,，;；\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).map(AcceptanceRunService::lower)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String lower(String v) {
        return v == null ? "" : v.trim().toLowerCase();
    }

    private Map<String, String> runShowParallel(List<Device> devices, String command) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Map.Entry<String, String>> results = pool.submitAll(devices, d -> {
            try {
                String text = sshClient.executeDisplayCommand(
                        d.getIpAddress(), mgmtPort(d), d.getSshUsername(), d.getSshPassword(),
                        command, mgmtProtocol(d));
                return Map.entry(d.getName(), text == null ? "" : text);
            } catch (Exception e) {
                return Map.entry(d.getName(), "# 采集失败：" + e.getMessage());
            }
        });
        for (Map.Entry<String, String> e : results) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static int mgmtPort(Device d) {
        if (d.isTelnetManagement()) {
            return 23;
        }
        return d.getSshPort() != null ? d.getSshPort() : 22;
    }

    private static String mgmtProtocol(Device d) {
        return d.isTelnetManagement() ? "telnet" : "ssh";
    }

    private Map<String, String> pullConfigsParallel(List<Device> devices) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Map.Entry<String, String>> results = pool.submitAll(devices, d -> {
            try {
                return Map.entry(d.getName(), deviceConfigService.pullLiveConfig(d.getId(), "running"));
            } catch (Exception e) {
                String backup = latestBackup(d);
                if (backup != null && !backup.isBlank()) {
                    return Map.entry(d.getName(), backup);
                }
                return Map.entry(d.getName(), "# 配置获取失败：" + e.getMessage());
            }
        });
        for (Map.Entry<String, String> e : results) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private String latestBackup(Device d) {
        if (d == null) {
            return null;
        }
        try {
            return deviceConfigService.getConfigSummariesByDeviceId(d.getId()).stream()
                    .findFirst()
                    .flatMap(s -> deviceConfigService.getConfigById(s.getId()))
                    .map(c -> c.getContent())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static int diffCount(String live, String baseline) {
        var a = linesOf(live);
        var b = linesOf(baseline);
        int diff = 0;
        for (String line : a) {
            if (!b.contains(line)) {
                diff++;
            }
        }
        for (String line : b) {
            if (!a.contains(line)) {
                diff++;
            }
        }
        return diff;
    }

    private static List<String> linesOf(String text) {
        if (text == null) {
            return List.of();
        }
        return text.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
    }

    private static int countMatches(String text, String regex) {
        if (text == null) {
            return 0;
        }
        Matcher m = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n…（截断，全文见报告附录）";
    }

    private AcceptancePlan requirePlan(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在"));
    }

    private List<Map<String, Object>> enabledChecks(AcceptancePlan plan) {
        try {
            List<Map<String, Object>> checks = plan.getChecksJson() == null
                    ? List.of()
                    : objectMapper.readValue(plan.getChecksJson(), new TypeReference<>() {
                    });
            return checks.stream()
                    .filter(c -> !Boolean.FALSE.equals(c.get("enabled")))
                    .sorted((a, b) -> Integer.compare(numInt(a.get("seq"), 0), numInt(b.get("seq"), 0)))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("方案检查项解析失败：" + e.getMessage(), e);
        }
    }

    private List<Device> pickDevices(List<Long> deviceIds) {
        List<Device> all = deviceRepository.findAll();
        if (deviceIds == null || deviceIds.isEmpty()) {
            return all;
        }
        return all.stream().filter(d -> deviceIds.contains(d.getId())).toList();
    }

    private List<Map<String, Object>> itemsOf(List<Map<String, Object>> checks) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> c : checks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("checkId", numLong(c.get("id")));
            item.put("code", str(c.get("code")));
            item.put("title", str(c.get("title")));
            item.put("type", str(c.get("type")));
            item.put("owner", str(c.get("owner")));
            item.put("severity", str(c.get("severity")));
            item.put("status", "WAITING");
            item.put("durationMs", 0);
            item.put("metrics", Map.of());
            item.put("rawOutput", "");
            item.put("message", "排队中");
            item.put("findings", List.of());
            items.add(item);
        }
        return items;
    }

    private String reportNo(AcceptancePlan plan, long seq) {
        String rule = plan.getReportNoRule() == null ? "YS-{date}-{batch}" : plan.getReportNoRule();
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return rule.replace("{date}", date).replace("{batch}", String.format("%03d", seq));
    }

    private static String metricsJson(Map<String, Object> metrics) {
        try {
            return new ObjectMapper().writeValueAsString(metrics);
        } catch (Exception e) {
            return String.valueOf(metrics);
        }
    }

    private String toJson(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (IOException e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }

    private List<Map<String, Object>> fromJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("快照解析失败", e);
        }
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static Long numLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int numInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object v) {
        return v == null ? new LinkedHashMap<>() : (Map<String, Object>) v;
    }

/** 简单并行工具：4路设备级并发 */
    private static class ExecutorDevicePool {
        private final ExecutorService pool = Executors.newFixedThreadPool(4);

        <T> List<Map.Entry<String, T>> submitAll(List<Device> devices, java.util.function.Function<Device, Map.Entry<String, T>> task) {
            var futures = devices.stream().map(d -> pool.submit(() -> task.apply(d))).toList();
            List<Map.Entry<String, T>> out = new ArrayList<>();
            for (var f : futures) {
                try {
                    out.add(f.get());
                } catch (Exception e) {
                    log.warn("并行采集任务失败", e);
                }
            }
            return out;
        }
    }
}
