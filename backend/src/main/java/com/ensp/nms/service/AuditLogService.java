package com.ensp.nms.service;

import com.ensp.nms.entity.AuditLog;
import com.ensp.nms.repository.AuditLogRepository;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.util.HttpRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    public static final int RETENTION_DAYS = 90;
    public static final int MAX_RECORDS = 10_000;

    private final AuditLogRepository auditLogRepository;

    @Builder
    public record AuditRecord(
            String module,
            String action,
            String operator,
            String targetType,
            String targetId,
            String targetName,
            String status,
            String summary,
            String clientIp,
            String detail,
            String refType,
            String refId
    ) {
    }

    public static String currentClientIp() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return HttpRequestUtils.resolveClientIp(sra.getRequest());
        }
        return null;
    }

    public static String currentClientIp(HttpServletRequest request) {
        return HttpRequestUtils.resolveClientIp(request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(AuditRecord rec) {
        if (rec == null || rec.module() == null || rec.action() == null) {
            return null;
        }
        try {
            AuditLog entry = new AuditLog();
            String op = rec.operator();
            entry.setOperator(op != null && !op.isBlank() ? op.trim() : SecurityUtils.currentOperator());
            entry.setModule(rec.module().trim());
            entry.setAction(rec.action().trim());
            entry.setTargetType(rec.targetType());
            entry.setTargetId(rec.targetId());
            entry.setTargetName(rec.targetName());
            entry.setStatus(rec.status() != null && !rec.status().isBlank() ? rec.status().trim() : "success");
            entry.setSummary(truncate(rec.summary(), 500));
            entry.setClientIp(rec.clientIp());
            entry.setDetail(truncate(rec.detail(), 8000));
            entry.setRefType(rec.refType());
            entry.setRefId(rec.refId());
            return auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("写入操作日志失败: {} {} - {}", rec.module(), rec.action(), e.getMessage());
            return null;
        }
    }

    public Optional<AuditLog> getById(Long id) {
        return auditLogRepository.findById(id);
    }

    public Page<AuditLog> queryLogs(String operator, String module, String action, String status,
                                    String keyword, LocalDateTime from, LocalDateTime to,
                                    Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
            if (operator != null && !operator.isBlank()) {
                preds.add(cb.like(cb.lower(root.get("operator")), "%" + operator.trim().toLowerCase() + "%"));
            }
            if (module != null && !module.isBlank()) {
                preds.add(cb.equal(root.get("module"), module.trim()));
            }
            if (action != null && !action.isBlank()) {
                preds.add(cb.equal(root.get("action"), action.trim()));
            }
            if (status != null && !status.isBlank()) {
                preds.add(cb.equal(root.get("status"), status.trim()));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("summary")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("targetName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("targetId"), "")), like),
                        cb.like(cb.lower(root.get("operator")), like)
                ));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return auditLogRepository.findAll(spec, pageable);
    }

    @Transactional
    public int cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int deleted = auditLogRepository.deleteByCreatedAtBefore(cutoff);
        long count = auditLogRepository.count();
        int trimmed = 0;
        while (count > MAX_RECORDS) {
            int excess = (int) Math.min(count - MAX_RECORDS, 500);
            List<Long> oldest = auditLogRepository.findOldestIds(
                    org.springframework.data.domain.PageRequest.of(0, excess));
            if (oldest.isEmpty()) {
                break;
            }
            auditLogRepository.deleteAllById(oldest);
            trimmed += oldest.size();
            count = auditLogRepository.count();
        }
        return deleted + trimmed;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
