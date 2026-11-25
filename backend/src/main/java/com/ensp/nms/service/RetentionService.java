package com.ensp.nms.service;

import com.ensp.nms.repository.AlarmRepository;
import com.ensp.nms.repository.PerformanceDataRepository;
import com.ensp.nms.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 时序/告警数据保留策略：定期清理过期记录。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Slf4j
@Service
public class RetentionService {

    public static final int PERFORMANCE_RETENTION_DAYS = 7;
    public static final int CLEARED_ALARM_RETENTION_DAYS = 30;
    public static final int ALARM_MAX_RETENTION_DAYS = 90;

    @Autowired
    private PerformanceDataRepository performanceDataRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private DeviceConfigService deviceConfigService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.ensp.nms.service.aiops.LlmUnattendedOpsService unattendedOpsService;

    @Scheduled(cron = "0 15 2 * * ?")
    @Transactional
    public void cleanupOldPerformanceData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(PERFORMANCE_RETENTION_DAYS);
        int deleted = performanceDataRepository.deleteByTimestampBefore(cutoff);
        if (deleted > 0) {
            log.info("性能数据保留清理：删除 {} 条早于 {} 的记录", deleted, cutoff);
        } else {
            log.debug("性能数据保留清理：无过期记录（保留 {} 天）", PERFORMANCE_RETENTION_DAYS);
        }
    }

    @Scheduled(cron = "0 30 2 * * ?")
    @Transactional
    public void cleanupOldAlarms() {
        // 先清孤儿告警：设备已删除/不在库中，却仍计入告警统计的记录
        int orphans = alarmRepository.deleteOrphanAlarms();
        if (orphans > 0) {
            log.info("告警保留清理：删除孤儿告警（设备已不存在）{} 条", orphans);
        }
        LocalDateTime clearedCutoff = LocalDateTime.now().minusDays(CLEARED_ALARM_RETENTION_DAYS);
        int cleared = alarmRepository.deleteClearedBefore(com.ensp.nms.entity.Alarm.Status.CLEARED, clearedCutoff);
        LocalDateTime maxCutoff = LocalDateTime.now().minusDays(ALARM_MAX_RETENTION_DAYS);
        int old = alarmRepository.deleteOccurredBefore(maxCutoff);
        if (cleared > 0 || old > 0) {
            log.info("告警保留清理：已清除超期 {} 条（>{}天），超龄历史 {} 条（>{}天）",
                    cleared, CLEARED_ALARM_RETENTION_DAYS, old, ALARM_MAX_RETENTION_DAYS);
        } else {
            log.debug("告警保留清理：无过期记录");
        }
    }

    @Scheduled(cron = "0 45 2 * * ?")
    @Transactional
    public void cleanupExcessDeviceConfigs() {
        int deleted = deviceConfigService.enforceRetentionForAllDevices();
        if (deleted > 0) {
            log.info("配置备份保留清理：共删除 {} 条旧版本（每设备最多 {} 份）",
                    deleted, DeviceConfigService.MAX_BACKUPS_PER_DEVICE);
        } else {
            log.debug("配置备份保留清理：无需删除");
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupAuditLogs() {
        int deleted = auditLogService.cleanupExpired();
        if (deleted > 0) {
            log.info("操作日志保留清理：删除 {} 条（保留 {} 天 / 最多 {} 条）",
                    deleted, AuditLogService.RETENTION_DAYS, AuditLogService.MAX_RECORDS);
        } else {
            log.debug("操作日志保留清理：无过期记录（保留 {} 天 / 最多 {} 条）",
                    AuditLogService.RETENTION_DAYS, AuditLogService.MAX_RECORDS);
        }
    }

    @Scheduled(cron = "0 20 3 * * ?")
    @Transactional
    public void cleanupUnattendedRuns() {
        try {
            int deleted = unattendedOpsService.purgeOldRuns();
            if (deleted > 0) {
                log.info("无人值守运行记录清理：删除 {} 条", deleted);
            }
        } catch (Exception e) {
            log.warn("无人值守运行记录清理失败: {}", e.getMessage());
        }
    }
}
