package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import com.ensp.nms.config.NmsProbeProperties;
import com.ensp.nms.config.PerformanceThresholdProperties;
import com.ensp.nms.entity.AiopsFeedback;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.PerformanceData;
import com.ensp.nms.repository.AiopsFeedbackRepository;
import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.service.AlarmService;
import com.ensp.nms.service.PerformanceMonitorService;
import com.ensp.nms.service.llm.LlmSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiopsService {

    private final AlarmCorrelationService alarmCorrelationService;
    private final HealthScoreService healthScoreService;
    private final AnomalyDetectService anomalyDetectService;
    private final RcaService rcaService;
    private final OpsAssistantService opsAssistantService;
    private final AiopsActionService aiopsActionService;
    private final AiopsPlaybookService playbookService;
    private final AlarmRepository alarmRepository;
    private final DeviceRepository deviceRepository;
    private final AiopsFeedbackRepository feedbackRepository;
    private final AiopsPolicyProperties policyProperties;
    private final NmsProbeProperties probeProperties;
    private final PerformanceThresholdProperties thresholdProperties;
    private final PerformanceMonitorService performanceMonitorService;
    private final AiopsPolicyStore policyStore;
    private final WebhookNotifier webhookNotifier;
    private final AlarmService alarmService;
    private final LlmUnattendedOpsService unattendedOpsService;
    private final LlmSettingsService llmSettingsService;

    /** 最近一次巡检结构化证据（工作台/大屏展示） */
    private volatile Map<String, Object> lastInspectEvidence = Map.of();
    private volatile String lastScreenBrief = "";
    private volatile LocalDateTime lastScreenBriefAt = null;
    private volatile Map<String, Object> cachedScreenBrief = null;
    private static final int SCREEN_BRIEF_TTL_SECONDS = 90;

    private volatile Map<String, Object> cachedOverview = null;
    private volatile LocalDateTime lastOverviewAt = null;
    private static final int OVERVIEW_TTL_SECONDS = 45;

    /**
     * 总览：只读组装（带短缓存）。阅知类清理改由定时任务 / 巡检执行，避免每次进入页面同步写库。
     */
    public Map<String, Object> overview() {
        return overview(false);
    }

    public Map<String, Object> overview(boolean forceRefresh) {
        if (!forceRefresh && cachedOverview != null && lastOverviewAt != null
                && ChronoUnit.SECONDS.between(lastOverviewAt, LocalDateTime.now()) < OVERVIEW_TTL_SECONDS) {
            Map<String, Object> hit = deepCopyOverview(cachedOverview);
            hit.put("cached", true);
            hit.put("cacheAgeSeconds", ChronoUnit.SECONDS.between(lastOverviewAt, LocalDateTime.now()));
            return hit;
        }
        Map<String, Object> result = buildOverviewPayload();
        result.put("cached", false);
        this.cachedOverview = deepCopyOverview(result);
        this.lastOverviewAt = LocalDateTime.now();
        return result;
    }

    public void invalidateOverviewCache() {
        cachedOverview = null;
        lastOverviewAt = null;
        cachedScreenBrief = null;
        lastScreenBriefAt = null;
    }

    /** 定时清理阅知类，不阻塞页面 GET */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 120000, initialDelay = 60000)
    public void scheduledCloseAckCloses() {
        try {
            int n = alarmService.closeOpenAckClosesAlarms("aiops-schedule", "定时：阅知类自动办结");
            if (n > 0) {
                invalidateOverviewCache();
                log.info("定时阅知办结 {} 条", n);
            }
        } catch (Exception e) {
            log.warn("定时阅知办结失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> buildOverviewPayload() {
        Map<String, Object> correlation = new LinkedHashMap<>(alarmCorrelationService.getCorrelationSnapshot());
        enrichAckableCounts(correlation);
        Map<String, Object> health = healthScoreService.compute();
        List<Map<String, Object>> incidents = alarmCorrelationService.listIncidents();
        Map<String, Object> rca = rcaService.analyze();
        List<Map<String, Object>> trends = anomalyDetectService.capacityTrends();

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        long todayEvents = alarmRepository.countByOccurredAtBetween(dayStart, dayEnd);

        Object rawOpen = correlation.get("openAlarms");
        if (correlation.get("correlatedAt") == null) {
            rawOpen = alarmRepository.countByStatusIn(List.of(Alarm.Status.ACTIVE, Alarm.Status.ACKNOWLEDGED));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("health", health);
        result.put("correlation", correlation);
        result.put("incidents", incidents.stream().limit(30).toList());
        result.put("incidentTotal", incidents.size());
        result.put("rawOpenAlarms", rawOpen);
        result.put("suppressedCount", correlation.getOrDefault("suppressedCount", 0));
        result.put("todayAlarmCount", todayEvents);
        result.put("rca", rca);
        result.put("capacityTrends", trends);
        result.put("suggestions", health.get("suggestions"));
        result.put("feedbackStats", feedbackStats());
        result.put("policy", getPolicy());
        result.put("insights", buildInsights(health, correlation, incidents, rca, trends, todayEvents));
        result.put("story", buildStory(health, correlation, incidents, rca, trends));
        result.put("recentAnomalies", anomalyDetectService.listRecentAnomalies(12));
        result.put("lastInspectEvidence", lastInspectEvidence != null ? lastInspectEvidence : Map.of());
        result.put("llmOpsMode", policyProperties.getLlmOpsMode());
        return result;
    }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> deepCopyOverview(Map<String, Object> src) {
    return new LinkedHashMap<>(src);
  }

    /** 大屏简报：规则 story + 90s 缓存；含事件/根因/异常供大屏渲染 */
    public Map<String, Object> screenBrief() {
        if (cachedScreenBrief != null && lastScreenBriefAt != null
                && ChronoUnit.SECONDS.between(lastScreenBriefAt, LocalDateTime.now()) < SCREEN_BRIEF_TTL_SECONDS) {
            Map<String, Object> hit = new LinkedHashMap<>(cachedScreenBrief);
            hit.put("cached", true);
            return hit;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> health = healthScoreService.compute();
        List<Map<String, Object>> incidents = alarmCorrelationService.listIncidents();
        Map<String, Object> correlation = new LinkedHashMap<>(alarmCorrelationService.getCorrelationSnapshot());
        Map<String, Object> rca = rcaService.analyze();
        List<Map<String, Object>> trends = anomalyDetectService.capacityTrends();
        Map<String, Object> story = buildStory(health, correlation, incidents, rca, trends);
        String brief = story.get("headline") != null ? String.valueOf(story.get("headline")) : "运维态势正常";
        if (story.get("detail") != null && !String.valueOf(story.get("detail")).isBlank()) {
            brief = brief + " — " + story.get("detail");
        }
        this.lastScreenBrief = brief;
        this.lastScreenBriefAt = LocalDateTime.now();
        m.put("ok", true);
        m.put("brief", brief);
        m.put("source", "rules");
        m.put("cached", false);
        m.put("generatedAt", lastScreenBriefAt.toString());
        m.put("llmOpsMode", policyProperties.getLlmOpsMode());
        boolean llmEnabled = false;
        try {
            llmEnabled = Boolean.TRUE.equals(llmSettingsService.resolve().enabled());
        } catch (Exception ignored) {
            /* ignore */
        }
        m.put("llmEnabled", llmEnabled);
        m.put("health", health);
        m.put("story", story);
        Map<String, Object> corrLite = new LinkedHashMap<>();
        corrLite.put("suppressedCount", correlation.getOrDefault("suppressedCount", 0));
        corrLite.put("stormGroups", correlation.getOrDefault("stormGroups", 0));
        corrLite.put("secondaryMarked", correlation.getOrDefault("secondaryMarked", 0));
        corrLite.put("representativeCount", correlation.getOrDefault("representativeCount", incidents.size()));
        m.put("correlation", corrLite);
        m.put("lastInspectEvidence", lastInspectEvidence != null ? lastInspectEvidence : Map.of());
        m.put("incidentTotal", incidents.size());
        long pending = incidents.stream()
                .filter(r -> "pending".equals(String.valueOf(r.get("phase")))).count();
        long inProgress = incidents.stream()
                .filter(r -> "in_progress".equals(String.valueOf(r.get("phase")))).count();
        m.put("pendingCount", pending);
        m.put("inProgressCount", inProgress);
        m.put("incidents", incidents.stream().limit(12).toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) rca.getOrDefault("candidates", List.of());
        m.put("rcaCandidates", candidates.stream().limit(5).toList());
        m.put("recentAnomalies", anomalyDetectService.listRecentAnomalies(8));
        m.put("capacityTrends", trends.stream().limit(5).toList());
        m.put("recentActions", unattendedOpsService.recentActions());
        this.cachedScreenBrief = m;
        return m;
    }

    public Map<String, Object> runUnattendedCycle() {
        return unattendedOpsService.runCycle("api");
    }

    public Map<String, Object> runUnattendedForAlarm(Long alarmId, Long deviceId) {
        return unattendedOpsService.runForAlarmApi(alarmId, deviceId);
    }

    public Map<String, Object> unattendedStatus() {
        return unattendedOpsService.status();
    }

    public Map<String, Object> setUnattendedPaused(boolean paused) {
        return unattendedOpsService.setPaused(paused);
    }

    public Map<String, Object> listUnattendedRuns(String planSource, Long alarmId, int page, int size) {
        var p = unattendedOpsService.listRuns(planSource, alarmId, page, size);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", p.getContent().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("triggerSource", r.getTriggerSource());
            row.put("alarmId", r.getAlarmId());
            row.put("deviceId", r.getDeviceId());
            row.put("planSource", r.getPlanSource());
            row.put("status", r.getStatus());
            row.put("reason", r.getReason());
            row.put("stepsRan", r.getStepsRan());
            row.put("startedAt", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
            row.put("finishedAt", r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
            return row;
        }).toList());
        m.put("totalElements", p.getTotalElements());
        m.put("totalPages", p.getTotalPages());
        m.put("number", p.getNumber());
        m.put("size", p.getSize());
        return m;
    }

    public Map<String, Object> getUnattendedRun(Long id) {
        return unattendedOpsService.getRunDetail(id);
    }

    public Map<String, Object> retryUnattendedRun(Long id) {
        return unattendedOpsService.retryRun(id);
    }

    public Map<String, Object> playbook(Long deviceId, Long alarmId) {
        return playbookService.buildPlaybook(deviceId, alarmId);
    }

    /** 工作台焦点：场景、推荐工具、影响、证据 */
    public Map<String, Object> workbenchFocus(Long alarmId, Long deviceId) {
        return playbookService.buildWorkbenchFocus(alarmId, deviceId);
    }

    public Map<String, Object> timeline(Long alarmId, Long deviceId) {
        return playbookService.buildTimeline(alarmId, deviceId);
    }

    public Map<String, Object> runPlaybookStep(String action, Long deviceId, Long alarmId,
                                              boolean confirmed, String question) {
        return playbookService.executeStep(action, deviceId, alarmId, confirmed, question);
    }

    /**
     * 一键智能巡检：立即重算关联 + 基线检测，返回可读报告（让用户「感觉到」智能在干活）。
     */
    public Map<String, Object> inspectNow() {
        int closedAck = 0;
        try {
            closedAck = alarmService.closeOpenAckClosesAlarms("aiops-inspect", "智能巡检：阅知类自动办结");
        } catch (Exception e) {
            log.warn("巡检阅知办结失败: {}", e.getMessage());
        }
        Map<String, Object> correlation;
        try {
            correlation = alarmCorrelationService.correlateOpenAlarms();
        } catch (Exception e) {
            log.warn("巡检关联失败: {}", e.getMessage());
            correlation = alarmCorrelationService.getCorrelationSnapshot();
        }
        Map<String, Object> anomaly = anomalyDetectService.detectAll();
        Map<String, Object> health = healthScoreService.compute();
        List<Map<String, Object>> incidents = alarmCorrelationService.listIncidents();
        Map<String, Object> rca = rcaService.analyze();
        List<Map<String, Object>> trends = anomalyDetectService.capacityTrends();

        List<String> lines = new ArrayList<>();
        lines.add("【巡检完成】" + LocalDateTime.now());
        lines.add(String.format("健康分 %.1f（%s），在线 %s/%s",
                toDouble(health.get("networkScore")),
                String.valueOf(health.get("level")),
                String.valueOf(health.get("onlineCount")),
                String.valueOf(health.get("deviceTotal"))));
        lines.add(String.format("告警关联：活跃 %s → 代表事件 %s，收敛隐藏 %s，风暴组 %s，连带标记 %s",
                correlation.getOrDefault("openAlarms", 0),
                correlation.getOrDefault("representativeCount", incidents.size()),
                correlation.getOrDefault("suppressedCount", 0),
                correlation.getOrDefault("stormGroups", 0),
                correlation.getOrDefault("secondaryMarked", 0)));
        if (closedAck > 0) {
            lines.add("阅知类办结：已自动关闭 " + closedAck + " 条（登录/退出、恢复类提示等）");
        }
        lines.add(String.format("基线检测：检查 %s 台，发现异常 %s 条，跳过仿真 %s，样本不足 %s（最少 %s 点）",
                anomaly.getOrDefault("checkedDevices", 0),
                anomaly.getOrDefault("anomalies", 0),
                anomaly.getOrDefault("skippedSimulated", 0),
                anomaly.getOrDefault("skippedInsufficientSamples", 0),
                anomaly.getOrDefault("minSamples", policyProperties.getAnomalyMinSamples())));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) rca.getOrDefault("candidates", List.of());
        if (candidates.isEmpty()) {
            lines.add("根因：当前无离线/性能/配置变更类根因候选");
        } else {
            Map<String, Object> top = candidates.get(0);
            lines.add(String.format("根因首选：%s（得分 %s，%s）— %s",
                    top.get("name"), top.get("score"), top.get("category"), top.get("reason")));
        }
        if (trends.isEmpty()) {
            lines.add("容量：近 3 日无显著 CPU 上升趋势（或仅有仿真数据已被忽略）");
        } else {
            Map<String, Object> t0 = trends.get(0);
            lines.add("容量：" + t0.getOrDefault("message", "存在上升趋势"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) health.getOrDefault("suggestions", List.of());
        if (!suggestions.isEmpty()) {
            lines.add("建议：" + suggestions.get(0).get("text"));
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("generatedAt", LocalDateTime.now().toString());
        evidence.put("engineLabel", "规则关联 · 统计基线 · 拓扑打分");
        evidence.put("ackClosesAutoClosed", closedAck);
        evidence.put("openAlarms", correlation.getOrDefault("openAlarms", 0));
        evidence.put("representativeCount", correlation.getOrDefault("representativeCount", incidents.size()));
        evidence.put("suppressedCount", correlation.getOrDefault("suppressedCount", 0));
        evidence.put("stormGroups", correlation.getOrDefault("stormGroups", 0));
        evidence.put("secondaryMarked", correlation.getOrDefault("secondaryMarked", 0));
        evidence.put("baselineChecked", anomaly.getOrDefault("checkedDevices", 0));
        evidence.put("baselineAnomalies", anomaly.getOrDefault("anomalies", 0));
        evidence.put("baselineSkippedSim", anomaly.getOrDefault("skippedSimulated", 0));
        evidence.put("baselineSkippedSamples", anomaly.getOrDefault("skippedInsufficientSamples", 0));
        evidence.put("rcaTop", candidates.isEmpty() ? null : candidates.get(0));
        evidence.put("networkScore", health.get("networkScore"));
        evidence.put("healthLevel", health.get("level"));
        this.lastInspectEvidence = evidence;
        invalidateOverviewCache();

        Map<String, Object> unattended = Map.of();
        try {
            unattended = unattendedOpsService.runCycle("inspect");
            if (unattended != null && Boolean.TRUE.equals(unattended.get("ok"))
                    && !Boolean.TRUE.equals(unattended.get("skipped"))) {
                Object msg = unattended.get("message");
                if (msg != null) {
                    lines.add("无人值守：" + msg);
                }
            }
        } catch (Exception e) {
            log.warn("无人值守周期失败: {}", e.getMessage());
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", true);
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("lines", lines);
        report.put("summary", String.join("；", lines.subList(1, Math.min(lines.size(), 4))));
        report.put("evidence", evidence);
        report.put("correlation", correlation);
        report.put("anomaly", anomaly);
        report.put("rca", rca);
        report.put("health", health);
        report.put("unattended", unattended);
        report.put("policy", getPolicy());
        report.put("insights", buildInsights(health, correlation, incidents, rca, trends, 0));
        return report;
    }

    /** 始终返回可读洞察卡片，网络空闲时也说明「智能在做什么」 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildInsights(
            Map<String, Object> health,
            Map<String, Object> correlation,
            List<Map<String, Object>> incidents,
            Map<String, Object> rca,
            List<Map<String, Object>> trends,
            long todayEvents) {
        List<Map<String, Object>> list = new ArrayList<>();
        int suppressed = toInt(correlation.get("suppressedCount"));
        int storm = toInt(correlation.get("stormGroups"));
        int secondary = toInt(correlation.get("secondaryMarked"));
        int open = toInt(correlation.get("openAlarms"));
        if (open == 0 && !incidents.isEmpty()) {
            open = incidents.size() + suppressed;
        }

        if (suppressed > 0 || storm > 0) {
            list.add(insight("correlation", "warning", "告警风暴已收敛",
                    String.format("将 %d 条活跃告警收敛为 %d 个代表事件，隐藏重复 %d 条（风暴组 %d）",
                            Math.max(open, incidents.size() + suppressed), incidents.size(), suppressed, storm),
                    "/aiops"));
        } else if (open > 0) {
            list.add(insight("correlation", "info", "告警关联运行中",
                    String.format("当前 %d 条活跃告警，暂无风暴折叠；关联任务每 60 秒自动重算", open),
                    "/alarms"));
        } else {
            list.add(insight("correlation", "success", "告警关联待命",
                    "当前无活跃告警。可在 eNSP 关闭一台设备制造离线风暴，再点「一键智能巡检」观察收敛效果",
                    "/alarms"));
        }

        if (secondary > 0) {
            list.add(insight("secondary", "warning", "发现拓扑连带告警",
                    secondary + " 条告警被标为可能连带，建议优先处理上游根因而非逐条确认",
                    "/aiops"));
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) rca.getOrDefault("candidates", List.of());
        if (!candidates.isEmpty()) {
            Map<String, Object> top = candidates.get(0);
            list.add(insight("rca", "danger", "根因候选：" + top.get("name"),
                    String.valueOf(top.get("reason")),
                    "/topology?rca=1"));
        } else {
            list.add(insight("rca", "success", "暂无根因候选",
                    "离线影响域、性能严重越限、变更时间窗均未命中。可在拓扑高亮中验证联动态",
                    "/topology"));
        }

        double score = toDouble(health.get("networkScore"));
        String level = String.valueOf(health.getOrDefault("level", "good"));
        list.add(insight("health", score >= 85 ? "success" : (score >= 70 ? "warning" : "danger"),
                "全网健康分 " + (score > 0 ? String.format("%.1f", score) : "-"),
                String.format("等级 %s · 在线 %s/%s · 风险设备 %s 台",
                        level,
                        health.getOrDefault("onlineCount", 0),
                        health.getOrDefault("deviceTotal", 0),
                        ((List<?>) health.getOrDefault("riskDevices", List.of())).size()),
                "/aiops"));

        int simCpu = 0;
        int snmpCpu = 0;
        for (Device d : deviceRepository.findAll()) {
            if (!"online".equalsIgnoreCase(d.getStatus())) {
                continue;
            }
            PerformanceData latest = performanceMonitorService.getLatestPerformance(d.getId());
            if (latest == null) {
                continue;
            }
            if (latest.isCpuSimulated()) {
                simCpu++;
            } else if (PerformanceData.SOURCE_SNMP.equalsIgnoreCase(latest.getCpuSource())) {
                snmpCpu++;
            }
        }
        if (simCpu > 0) {
            list.add(insight("trust", "warning", "部分性能为仿真数据",
                    String.format("在线设备中 SNMP 真采 CPU %d 台、仿真 %d 台；仿真不参与阈值/基线，避免假告警",
                            snmpCpu, simCpu),
                    "/performance"));
        } else if (snmpCpu > 0) {
            list.add(insight("trust", "success", "性能指标可信",
                    "在线设备 CPU 均为 SNMP 真采，基线与阈值告警基于真实数据",
                    "/performance"));
        }

        if (!trends.isEmpty()) {
            Map<String, Object> t0 = trends.get(0);
            list.add(insight("capacity", "warning", "容量趋势预警",
                    String.valueOf(t0.getOrDefault("message", "CPU 呈上升趋势")),
                    "/performance"));
        }

        list.add(insight("policy", "info", "当前策略生效中",
                String.format("风暴窗 %d 分钟 · 基线 k=%.1f · 最少样本 %d · CPU 阈值 %.0f/%.0f · 分析仿真=%s",
                        policyProperties.getStormWindowMinutes(),
                        policyProperties.getAnomalyKSigma(),
                        policyProperties.getAnomalyMinSamples(),
                        thresholdProperties.getCpu().getWarning(),
                        thresholdProperties.getCpu().getDanger(),
                        policyProperties.isAnalyzeSimulatedMetrics() ? "是" : "否"),
                "/aiops"));

        if (todayEvents > 0) {
            list.add(insight("today", "info", "今日已产生 " + todayEvents + " 条告警事件",
                    "可在告警中心按设备过滤，或在智能中心查看收敛后的代表事件",
                    "/alarms"));
        }

        return list;
    }

    private Map<String, Object> buildStory(
            Map<String, Object> health,
            Map<String, Object> correlation,
            List<Map<String, Object>> incidents,
            Map<String, Object> rca,
            List<Map<String, Object>> trends) {
        int suppressed = toInt(correlation.get("suppressedCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) rca.getOrDefault("candidates", List.of());
        String headline;
        String detail;
        if (!candidates.isEmpty()) {
            Map<String, Object> top = candidates.get(0);
            headline = "建议优先处理：" + top.get("name");
            detail = String.valueOf(top.getOrDefault("reason", "左侧点开对应事件，按中间「下一步」处置"));
        } else if (suppressed > 0) {
            headline = "已合并 " + suppressed + " 条重复告警";
            detail = "左侧展示 " + incidents.size() + " 条待处理事件，选中后按中间「下一步」操作";
        } else if (toDouble(health.get("networkScore")) >= 85) {
            headline = "网络整体健康";
            detail = "可点右上角「智能巡检」立即刷新事件队列与分析结果";
        } else {
            headline = "健康分偏低，请先处理左侧事件";
            detail = "选中一条待处理事件，按中间「下一步」确认执行";
        }
        Map<String, Object> story = new LinkedHashMap<>();
        story.put("headline", headline);
        story.put("detail", detail);
        story.put("hasRisk", !candidates.isEmpty() || toDouble(health.get("networkScore")) < 70);
        story.put("trendCount", trends.size());
        story.put("incidentCount", incidents.size());
        story.put("suppressedCount", suppressed);
        return story;
    }

    private Map<String, Object> insight(String code, String level, String title, String text, String link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("level", level);
        m.put("title", title);
        m.put("text", text);
        m.put("link", link);
        return m;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return 0;
    }

    public Map<String, Object> health() {
        return healthScoreService.compute();
    }

    /** 实验室健康报表（JSON，可供前端下载） */
    public Map<String, Object> healthReport() {
        Map<String, Object> health = healthScoreService.compute();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("title", "eNSP NMS 健康报表");
        report.put("health", health);
        report.put("policy", Map.of(
                "escalationEnabled", policyProperties.isEscalationEnabled(),
                "escalationMinutes", policyProperties.getEscalationMinutes(),
                "webhookEnabled", policyProperties.isWebhookEnabled(),
                "llmOpsMode", policyProperties.getLlmOpsMode()
        ));
        List<Map<String, Object>> topIncidents = new ArrayList<>();
        for (Map<String, Object> row : incidents()) {
            topIncidents.add(row);
            if (topIncidents.size() >= 20) break;
        }
        report.put("topIncidents", topIncidents);
        report.put("rca", rca());
        return report;
    }

    public List<Map<String, Object>> incidents() {
        return alarmCorrelationService.listIncidents();
    }

    public Map<String, Object> rca() {
        return rcaService.analyze();
    }

    public Map<String, Object> runAnomalyDetect() {
        return anomalyDetectService.detectAll();
    }

    public List<Map<String, Object>> capacityTrends() {
        return anomalyDetectService.capacityTrends();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> alarmContext(Long alarmId) {
        Optional<Alarm> opt = alarmRepository.findById(alarmId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            result.put("error", "告警不存在");
            return result;
        }
        Alarm alarm = opt.get();
        result.put("alarmId", alarm.getId());
        result.put("title", alarm.getTitle());
        result.put("deviceId", alarm.getDeviceId());
        result.put("status", alarm.getStatus() != null ? alarm.getStatus().name() : null);
        result.put("trapType", alarm.getTrapType());
        result.put("correlationType", alarm.getCorrelationType());
        result.put("secondary", alarm.isSecondaryAlarm());
        result.put("correlationNote", alarm.getCorrelationNote());
        result.put("parentAlarmId", alarm.getParentAlarmId());
        result.put("childAlarms", alarmRepository.findByParentAlarmId(alarm.getId()).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("occurredAt", a.getOccurredAt());
            m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
            m.put("severity", a.getSeverity() != null ? a.getSeverity().name() : null);
            return m;
        }).toList());
        result.put("relatedChanges", opsAssistantService.relatedConfigChanges(
                alarm.getDeviceId(),
                alarm.getOccurredAt() != null ? alarm.getOccurredAt() : LocalDateTime.now(),
                60));
        if (alarm.getDeviceId() != null) {
            PerformanceData latest = performanceMonitorService.getLatestPerformance(alarm.getDeviceId());
            if (latest != null) {
                Map<String, Object> perf = new LinkedHashMap<>();
                perf.put("cpuUsage", latest.getCpuUsage());
                perf.put("memoryUsage", latest.getMemoryUsage());
                perf.put("cpuSource", latest.getCpuSource());
                perf.put("memorySource", latest.getMemorySource());
                perf.put("timestamp", latest.getTimestamp());
                result.put("performance", perf);
            }
        }
        return result;
    }

    public Map<String, Object> ask(String question, Long deviceId, Long alarmId) {
        return opsAssistantService.ask(question, deviceId, alarmId);
    }

    @Transactional
    public AiopsFeedback saveFeedback(String targetType, String targetId, Boolean useful, String comment) {
        AiopsFeedback fb = new AiopsFeedback();
        fb.setTargetType(targetType != null ? targetType : "suggestion");
        fb.setTargetId(targetId);
        fb.setUseful(Boolean.TRUE.equals(useful));
        fb.setComment(comment);
        try {
            fb.setCreatedBy(SecurityUtils.currentOperator());
        } catch (Exception e) {
            fb.setCreatedBy("anonymous");
        }
        return feedbackRepository.save(fb);
    }

    public List<AiopsFeedback> recentFeedback() {
        return feedbackRepository.findTop50ByOrderByCreatedAtDesc();
    }

    /** 反馈命中率统计：有用/(有用+无用) */
    public Map<String, Object> feedbackStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (String type : List.of("suggestion", "rca", "assistant")) {
            long useful = 0;
            long useless = 0;
            try {
                for (Object[] row : feedbackRepository.aggregateByTargetType(type)) {
                    if (row == null || row.length < 3) {
                        continue;
                    }
                    useful += row[1] instanceof Number n ? n.longValue() : 0L;
                    useless += row[2] instanceof Number n ? n.longValue() : 0L;
                }
            } catch (Exception e) {
                log.debug("反馈统计跳过 {}: {}", type, e.getMessage());
            }
            long total = useful + useless;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("useful", useful);
            one.put("useless", useless);
            one.put("total", total);
            one.put("hitRate", total == 0 ? null : Math.round(useful * 1000.0 / total) / 10.0);
            stats.put(type, one);
        }
        return stats;
    }

    public Map<String, Object> getPolicy() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stormWindowMinutes", policyProperties.getStormWindowMinutes());
        m.put("linkWindowMinutes", policyProperties.getLinkWindowMinutes());
        m.put("anomalyLookbackHours", policyProperties.getAnomalyLookbackHours());
        m.put("anomalyKSigma", policyProperties.getAnomalyKSigma());
        m.put("anomalyMinAbsDelta", policyProperties.getAnomalyMinAbsDelta());
        m.put("anomalySuppressMinutes", policyProperties.getAnomalySuppressMinutes());
        m.put("analyzeSimulatedMetrics", policyProperties.isAnalyzeSimulatedMetrics());
        m.put("anomalyMinSamples", policyProperties.getAnomalyMinSamples());
        m.put("autoAckSecondary", policyProperties.isAutoAckSecondary());
        m.put("webhookEnabled", policyProperties.isWebhookEnabled());
        m.put("webhookUrl", policyProperties.getWebhookUrl() != null ? policyProperties.getWebhookUrl() : "");
        m.put("webhookMinSeverity", policyProperties.getWebhookMinSeverity() != null
                ? policyProperties.getWebhookMinSeverity() : "MAJOR");
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("warning", thresholdProperties.getCpu().getWarning());
        cpu.put("danger", thresholdProperties.getCpu().getDanger());
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("warning", thresholdProperties.getMemory().getWarning());
        memory.put("danger", thresholdProperties.getMemory().getDanger());
        m.put("cpuThreshold", cpu);
        m.put("memoryThreshold", memory);
        m.put("onlineAfterSuccesses", probeProperties.getOnlineAfterSuccesses());
        m.put("offlineAfterFailures", probeProperties.getOfflineAfterFailures());
        m.put("llmOpsMode", policyProperties.getLlmOpsMode() != null ? policyProperties.getLlmOpsMode() : "manual");
        m.put("unattendedMaxPerCycle", policyProperties.getUnattendedMaxPerCycle());
        m.put("unattendedMaxSteps", policyProperties.getUnattendedMaxSteps());
        m.put("unattendedCooldownMinutes", policyProperties.getUnattendedCooldownMinutes());
        m.put("unattendedOnCorrelate", policyProperties.isUnattendedOnCorrelate());
        m.put("unattendedAllowBackup", policyProperties.isUnattendedAllowBackup());
        m.put("unattendedPaused", policyProperties.isUnattendedPaused());
        m.put("llmCircuitFailThreshold", policyProperties.getLlmCircuitFailThreshold());
        m.put("llmCircuitMinutes", policyProperties.getLlmCircuitMinutes());
        m.put("unattendedRunRetentionDays", policyProperties.getUnattendedRunRetentionDays());
        m.put("escalationEnabled", policyProperties.isEscalationEnabled());
        m.put("escalationMinutes", policyProperties.getEscalationMinutes());
        m.put("escalationNotify", policyProperties.isEscalationNotify());
        m.put("persisted", true);
        return m;
    }

    public Map<String, Object> updatePolicy(Map<String, Object> body) {
        if (body == null) {
            body = Map.of();
        }
        if (body.get("stormWindowMinutes") instanceof Number n) {
            policyProperties.setStormWindowMinutes(Math.max(1, n.intValue()));
        }
        if (body.get("linkWindowMinutes") instanceof Number n) {
            policyProperties.setLinkWindowMinutes(Math.max(1, n.intValue()));
        }
        if (body.get("anomalyLookbackHours") instanceof Number n) {
            policyProperties.setAnomalyLookbackHours(Math.max(1, n.intValue()));
        }
        if (body.get("anomalyKSigma") instanceof Number n) {
            policyProperties.setAnomalyKSigma(Math.max(0.5, n.doubleValue()));
        }
        if (body.get("anomalyMinAbsDelta") instanceof Number n) {
            policyProperties.setAnomalyMinAbsDelta(Math.max(0, n.doubleValue()));
        }
        if (body.get("anomalySuppressMinutes") instanceof Number n) {
            policyProperties.setAnomalySuppressMinutes(Math.max(1, n.intValue()));
        }
        if (body.get("analyzeSimulatedMetrics") instanceof Boolean b) {
            policyProperties.setAnalyzeSimulatedMetrics(b);
        }
        if (body.get("anomalyMinSamples") instanceof Number n) {
            policyProperties.setAnomalyMinSamples(Math.max(3, Math.min(60, n.intValue())));
        }
        if (body.get("autoAckSecondary") instanceof Boolean b) {
            policyProperties.setAutoAckSecondary(b);
        } else if (body.get("autoAckSecondary") != null) {
            policyProperties.setAutoAckSecondary("true".equalsIgnoreCase(String.valueOf(body.get("autoAckSecondary"))));
        }
        if (body.get("webhookEnabled") instanceof Boolean b) {
            policyProperties.setWebhookEnabled(b);
        } else if (body.get("webhookEnabled") != null) {
            policyProperties.setWebhookEnabled("true".equalsIgnoreCase(String.valueOf(body.get("webhookEnabled"))));
        }
        if (body.containsKey("webhookUrl")) {
            Object u = body.get("webhookUrl");
            policyProperties.setWebhookUrl(u == null ? "" : String.valueOf(u).trim());
        }
        if (body.get("webhookMinSeverity") != null) {
            String sev = String.valueOf(body.get("webhookMinSeverity")).trim().toUpperCase();
            if (!sev.isEmpty()) {
                policyProperties.setWebhookMinSeverity(sev);
            }
        }
        applyThreshold(body.get("cpuThreshold"), thresholdProperties.getCpu());
        applyThreshold(body.get("memoryThreshold"), thresholdProperties.getMemory());
        if (body.get("onlineAfterSuccesses") instanceof Number n) {
            probeProperties.setOnlineAfterSuccesses(Math.max(1, Math.min(10, n.intValue())));
        }
        if (body.get("offlineAfterFailures") instanceof Number n) {
            probeProperties.setOfflineAfterFailures(Math.max(1, Math.min(10, n.intValue())));
        }
        if (body.get("llmOpsMode") != null) {
            String mode = String.valueOf(body.get("llmOpsMode")).trim().toLowerCase();
            if ("unattended".equals(mode) || "manual".equals(mode)) {
                policyProperties.setLlmOpsMode(mode);
            }
        }
        if (body.get("unattendedMaxPerCycle") instanceof Number n) {
            policyProperties.setUnattendedMaxPerCycle(Math.max(1, Math.min(20, n.intValue())));
        }
        if (body.get("unattendedMaxSteps") instanceof Number n) {
            policyProperties.setUnattendedMaxSteps(Math.max(1, Math.min(10, n.intValue())));
        }
        if (body.get("unattendedCooldownMinutes") instanceof Number n) {
            policyProperties.setUnattendedCooldownMinutes(Math.max(1, Math.min(120, n.intValue())));
        }
        if (body.get("unattendedOnCorrelate") instanceof Boolean b) {
            policyProperties.setUnattendedOnCorrelate(b);
        } else if (body.get("unattendedOnCorrelate") != null) {
            policyProperties.setUnattendedOnCorrelate(
                    "true".equalsIgnoreCase(String.valueOf(body.get("unattendedOnCorrelate"))));
        }
        if (body.get("unattendedAllowBackup") instanceof Boolean b) {
            policyProperties.setUnattendedAllowBackup(b);
        } else if (body.get("unattendedAllowBackup") != null) {
            policyProperties.setUnattendedAllowBackup(
                    "true".equalsIgnoreCase(String.valueOf(body.get("unattendedAllowBackup"))));
        }
        if (body.get("unattendedPaused") instanceof Boolean b) {
            policyProperties.setUnattendedPaused(b);
        } else if (body.get("unattendedPaused") != null) {
            policyProperties.setUnattendedPaused(
                    "true".equalsIgnoreCase(String.valueOf(body.get("unattendedPaused"))));
        }
        if (body.get("llmCircuitFailThreshold") instanceof Number n) {
            policyProperties.setLlmCircuitFailThreshold(Math.max(1, Math.min(20, n.intValue())));
        }
        if (body.get("llmCircuitMinutes") instanceof Number n) {
            policyProperties.setLlmCircuitMinutes(Math.max(1, Math.min(240, n.intValue())));
        }
        if (body.get("unattendedRunRetentionDays") instanceof Number n) {
            policyProperties.setUnattendedRunRetentionDays(Math.max(1, Math.min(365, n.intValue())));
        }
        if (body.get("escalationEnabled") instanceof Boolean b) {
            policyProperties.setEscalationEnabled(b);
        } else if (body.get("escalationEnabled") != null) {
            policyProperties.setEscalationEnabled(
                    "true".equalsIgnoreCase(String.valueOf(body.get("escalationEnabled"))));
        }
        if (body.get("escalationMinutes") instanceof Number n) {
            policyProperties.setEscalationMinutes(Math.max(5, Math.min(24 * 60, n.intValue())));
        }
        if (body.get("escalationNotify") instanceof Boolean b) {
            policyProperties.setEscalationNotify(b);
        } else if (body.get("escalationNotify") != null) {
            policyProperties.setEscalationNotify(
                    "true".equalsIgnoreCase(String.valueOf(body.get("escalationNotify"))));
        }
        try {
            policyStore.saveFromRuntime();
        } catch (Exception e) {
            log.warn("持久化 AIOps 策略失败: {}", e.getMessage());
        }
        Map<String, Object> result = getPolicy();
        result.put("updated", true);
        result.put("note", "已写入数据库，重启后仍生效");
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyThreshold(Object raw, PerformanceThresholdProperties.MetricThreshold target) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        Object w = map.get("warning");
        Object d = map.get("danger");
        if (w instanceof Number n) {
            target.setWarning(n.doubleValue());
        }
        if (d instanceof Number n) {
            target.setDanger(n.doubleValue());
        }
        if (target.getDanger() < target.getWarning()) {
            target.setDanger(target.getWarning());
        }
    }

    public Map<String, Object> ackSecondaryAction(Long deviceId, Long alarmId, boolean confirmed) {
        return aiopsActionService.ackSecondaryWithConfirm(deviceId, alarmId, confirmed, null);
    }

    public Map<String, Object> disposeIncidentAction(Long alarmId, Long deviceId, boolean confirmed) {
        return aiopsActionService.disposeIncidentWithConfirm(alarmId, deviceId, confirmed);
    }

    public Map<String, Object> refreshOfflineAction(boolean confirmed) {
        return aiopsActionService.refreshOfflineDevices(confirmed);
    }

    public Map<String, Object> backupAction(Long deviceId, boolean confirmed, String reason) {
        return aiopsActionService.backupWithConfirm(deviceId, confirmed, reason);
    }

    public Map<String, Object> testWebhook() {
        return webhookNotifier.testWebhook();
    }

    public Map<String, Object> restoreAction(Long deviceId, boolean confirmed, String reason) {
        return aiopsActionService.restoreLatestWithConfirm(deviceId, confirmed, reason);
    }

    /** 写入可确认噪音数，避免 UI 用「已标记含已 ACK」误导 */
    private void enrichAckableCounts(Map<String, Object> correlation) {
        List<Alarm> active = alarmRepository.findByStatusInOrderByOccurredAtDesc(List.of(Alarm.Status.ACTIVE));
        long activeSecondary = active.stream().filter(Alarm::isSecondaryAlarm).count();
        long activeChildren = active.stream().filter(a -> a.getParentAlarmId() != null).count();
        correlation.put("activeSecondaryCount", activeSecondary);
        correlation.put("activeChildCount", activeChildren);
        correlation.put("ackableNoiseCount", activeSecondary + activeChildren);
    }
}
