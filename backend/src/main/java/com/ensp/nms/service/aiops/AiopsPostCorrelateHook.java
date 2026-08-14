package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 关联完成后的减负钩子：可选自动确认连带 + Webhook 摘要 + 无人值守。
 */
@Slf4j
@Service
public class AiopsPostCorrelateHook {

    private final AiopsPolicyProperties policyProperties;
    private final AiopsActionService actionService;
    private final WebhookNotifier webhookNotifier;
    private final AlarmCorrelationService correlationService;
    private final RcaService rcaService;
    private final HealthScoreService healthScoreService;
    private final LlmUnattendedOpsService unattendedOpsService;

    private final AtomicReference<String> lastFingerprint = new AtomicReference<>("");

    public AiopsPostCorrelateHook(
            AiopsPolicyProperties policyProperties,
            AiopsActionService actionService,
            WebhookNotifier webhookNotifier,
            @Lazy AlarmCorrelationService correlationService,
            @Lazy RcaService rcaService,
            @Lazy HealthScoreService healthScoreService,
            @Lazy LlmUnattendedOpsService unattendedOpsService) {
        this.policyProperties = policyProperties;
        this.actionService = actionService;
        this.webhookNotifier = webhookNotifier;
        this.correlationService = correlationService;
        this.rcaService = rcaService;
        this.healthScoreService = healthScoreService;
        this.unattendedOpsService = unattendedOpsService;
    }

    /** 在关联事务提交后调用（异步），避免拖慢关联主路径 */
    @Async
    public void afterCorrelate(Map<String, Object> correlationResult) {
        try {
            // 「自动确认连带」与无人值守独立；运营暂停时一律不自动改状态
            if (policyProperties.isAutoAckSecondary() && !policyProperties.isUnattendedPaused()) {
                int marked = toInt(correlationResult.get("secondaryMarked"));
                if (marked > 0) {
                    Map<String, Object> ack = actionService.ackSecondaryWithConfirm(null, true, "aiops-auto");
                    log.info("自动确认连带: {}", ack.get("message"));
                }
            } else if (policyProperties.isAutoAckSecondary() && policyProperties.isUnattendedPaused()) {
                log.debug("运营已暂停，跳过自动确认连带");
            }
            maybeNotify(correlationResult);
            if (policyProperties.isUnattendedOnCorrelate() && policyProperties.isUnattendedMode()) {
                Map<String, Object> u = unattendedOpsService.runAfterCorrelateIfEnabled();
                if (u != null && !Boolean.TRUE.equals(u.get("skipped"))) {
                    log.info("关联后无人值守: {}", u.get("message"));
                }
            }
        } catch (Exception e) {
            log.warn("关联后钩子失败: {}", e.getMessage());
        }
    }

    private void maybeNotify(Map<String, Object> correlationResult) {
        if (!policyProperties.isWebhookEnabled()) {
            return;
        }
        String url = policyProperties.getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        List<Map<String, Object>> incidents;
        Map<String, Object> rca;
        Map<String, Object> health;
        try {
            incidents = correlationService.listIncidents();
            rca = rcaService.analyze();
            health = healthScoreService.compute();
        } catch (Exception e) {
            log.warn("构建 Webhook 摘要失败: {}", e.getMessage());
            return;
        }

        String minSev = policyProperties.getWebhookMinSeverity() != null
                ? policyProperties.getWebhookMinSeverity().toUpperCase(Locale.ROOT) : "MAJOR";
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> inc : incidents) {
            if (severityAtLeast(String.valueOf(inc.get("severity")), minSev)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", inc.get("id"));
                row.put("title", inc.get("title"));
                row.put("severity", inc.get("severity"));
                row.put("deviceName", inc.get("deviceName"));
                row.put("secondary", inc.get("secondary"));
                row.put("childCount", inc.get("childCount"));
                filtered.add(row);
            }
        }
        // 无达标事件且无连带/抑制时跳过（避免空刷）
        int suppressed = toInt(correlationResult.get("suppressedCount"));
        int secondary = toInt(correlationResult.get("secondaryMarked"));
        if (filtered.isEmpty() && suppressed == 0 && secondary == 0) {
            return;
        }

        String fp = filtered.size() + "|" + suppressed + "|" + secondary + "|"
                + (filtered.isEmpty() ? "-" : String.valueOf(filtered.get(0).get("id")));
        if (fp.equals(lastFingerprint.get())) {
            return;
        }
        lastFingerprint.set(fp);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "aiops_digest");
        payload.put("at", LocalDateTime.now().toString());
        payload.put("incidentTotal", incidents.size());
        payload.put("suppressedCount", suppressed);
        payload.put("secondaryMarked", secondary);
        payload.put("stormGroups", correlationResult.get("stormGroups"));
        payload.put("topIncidents", filtered.stream().limit(8).toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) rca.getOrDefault("candidates", List.of());
        List<Map<String, Object>> topRca = new ArrayList<>();
        for (Map<String, Object> c : candidates.stream().limit(3).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", c.get("name"));
            row.put("score", c.get("score"));
            row.put("category", c.get("category"));
            row.put("reason", c.get("reason"));
            topRca.add(row);
        }
        payload.put("topRca", topRca);
        payload.put("healthScore", health.get("networkScore"));
        payload.put("healthLevel", health.get("level"));
        webhookNotifier.sendDigestAsync(payload);
    }

    private static boolean severityAtLeast(String actual, String min) {
        return severityRank(actual) >= severityRank(min);
    }

    private static int severityRank(String s) {
        if (s == null) return 0;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 4;
            case "MAJOR" -> 3;
            case "WARNING" -> 2;
            case "MINOR" -> 1;
            case "INFO" -> 0;
            default -> 0;
        };
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }
}
