package com.ensp.nms.service.aiops;

import com.ensp.nms.config.AiopsPolicyProperties;
import com.ensp.nms.entity.Alarm;
import com.ensp.nms.repository.AlarmRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验室级超时升级：待处理告警超过阈值后提升一级严重度，可选 Webhook。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmEscalationService {

    private static final String MARK = "[超时升级]";

    private final AiopsPolicyProperties policyProperties;
    private final AlarmRepository alarmRepository;
    private final WebhookNotifier webhookNotifier;

    @Transactional
    public Map<String, Object> runOnce() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", policyProperties.isEscalationEnabled());
        if (!policyProperties.isEscalationEnabled()) {
            out.put("escalated", 0);
            out.put("message", "超时升级未启用");
            return out;
        }
        int minutes = Math.max(5, policyProperties.getEscalationMinutes());
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        List<Alarm> open = alarmRepository.findByStatusOrderByOccurredAtDesc(Alarm.Status.ACTIVE);
        int escalated = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Alarm a : open) {
            if (a.getOccurredAt() == null || a.getOccurredAt().isAfter(cutoff)) {
                continue;
            }
            if (alreadyEscalated(a)) {
                continue;
            }
            Alarm.Severity from = a.getSeverity() != null ? a.getSeverity() : Alarm.Severity.WARNING;
            Alarm.Severity to = bump(from);
            if (to == from) {
                mark(a, from, from);
                alarmRepository.save(a);
                continue;
            }
            a.setSeverity(to);
            mark(a, from, to);
            alarmRepository.save(a);
            escalated++;
            if (samples.size() < 8) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", a.getId());
                row.put("title", a.getTitle());
                row.put("from", from.name());
                row.put("to", to.name());
                samples.add(row);
            }
        }
        out.put("escalated", escalated);
        out.put("thresholdMinutes", minutes);
        out.put("samples", samples);
        out.put("message", escalated > 0
                ? ("已升级 " + escalated + " 条超时待处理告警")
                : "无符合条件的超时告警");
        if (escalated > 0 && policyProperties.isEscalationNotify() && policyProperties.isWebhookEnabled()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "alarm_escalation");
            payload.put("escalated", escalated);
            payload.put("thresholdMinutes", minutes);
            payload.put("samples", samples);
            payload.put("at", LocalDateTime.now().toString());
            payload.put("message", "超时告警已升级 " + escalated + " 条，请值班员关注");
            webhookNotifier.sendDigestAsync(payload);
            out.put("webhookQueued", true);
        }
        if (escalated > 0) {
            log.info("告警超时升级完成 escalated={} thresholdMin={}", escalated, minutes);
        }
        return out;
    }

    private static boolean alreadyEscalated(Alarm a) {
        String note = a.getCorrelationNote();
        return note != null && note.contains(MARK);
    }

    private static void mark(Alarm a, Alarm.Severity from, Alarm.Severity to) {
        String extra = MARK + from.name() + "→" + to.name();
        String note = a.getCorrelationNote();
        if (note == null || note.isBlank()) {
            a.setCorrelationNote(extra);
        } else if (!note.contains(MARK)) {
            a.setCorrelationNote(note + " | " + extra);
        }
    }

    /** INFO → WARNING → MINOR → MAJOR → CRITICAL（CLEARED 保持不变） */
    private static Alarm.Severity bump(Alarm.Severity s) {
        if (s == null) {
            return Alarm.Severity.WARNING;
        }
        return switch (s) {
            case INFO -> Alarm.Severity.WARNING;
            case WARNING -> Alarm.Severity.MINOR;
            case MINOR -> Alarm.Severity.MAJOR;
            case MAJOR -> Alarm.Severity.CRITICAL;
            case CRITICAL, CLEARED -> s;
        };
    }
}
