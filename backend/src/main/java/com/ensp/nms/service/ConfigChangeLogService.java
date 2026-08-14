package com.ensp.nms.service;

import com.ensp.nms.entity.ConfigChangeLog;
import com.ensp.nms.repository.ConfigChangeLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigChangeLogService {

    private final ConfigChangeLogRepository configChangeLogRepository;

    public List<ConfigChangeLog> getAllLogs() {
        return configChangeLogRepository.findAllByOrderByCreatedAtDesc();
    }

    public Page<ConfigChangeLog> queryLogs(Long deviceId, String changeType, String status,
                                           String keyword, LocalDateTime from, LocalDateTime to,
                                           Pageable pageable) {
        Specification<ConfigChangeLog> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
            if (deviceId != null) {
                preds.add(cb.equal(root.get("deviceId"), deviceId));
            }
            if (changeType != null && !changeType.isBlank()) {
                preds.add(cb.equal(root.get("changeType"), changeType.trim()));
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
                        cb.like(cb.lower(root.get("deviceName")), like),
                        cb.like(cb.lower(root.get("operator")), like),
                        cb.like(cb.lower(root.get("reason")), like)
                ));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return configChangeLogRepository.findAll(spec, pageable);
    }

    public List<ConfigChangeLog> getLogsByDeviceId(Long deviceId) {
        return configChangeLogRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    public Optional<ConfigChangeLog> getLogById(Long id) {
        return configChangeLogRepository.findById(id);
    }

    @Transactional
    public ConfigChangeLog createLog(ConfigChangeLog log) {
        return configChangeLogRepository.save(log);
    }

    /** 服务端统一写变更记录，避免依赖前端 POST */
    @Transactional
    public ConfigChangeLog record(Long deviceId, String deviceName, String changeType,
                                  String operator, String reason, String commands,
                                  String result, String status,
                                  String beforeVersion, String afterVersion) {
        ConfigChangeLog entry = new ConfigChangeLog();
        entry.setDeviceId(deviceId);
        entry.setDeviceName(deviceName);
        entry.setChangeType(changeType != null ? changeType : "unknown");
        entry.setOperator(operator != null && !operator.isBlank() ? operator : "system");
        entry.setReason(reason);
        entry.setCommands(truncate(commands, 8000));
        entry.setResult(truncate(result, 8000));
        entry.setStatus(status != null ? status : "success");
        entry.setBeforeVersion(beforeVersion);
        entry.setAfterVersion(afterVersion);
        return configChangeLogRepository.save(entry);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Transactional
    public ConfigChangeLog updateLogStatus(Long id, String status, String result) {
        ConfigChangeLog log = configChangeLogRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Log not found"));
        
        log.setStatus(status);
        log.setResult(result);
        
        return configChangeLogRepository.save(log);
    }

    @Transactional
    public void deleteLog(Long id) {
        configChangeLogRepository.deleteById(id);
    }
}
