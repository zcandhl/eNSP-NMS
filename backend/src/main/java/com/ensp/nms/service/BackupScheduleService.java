package com.ensp.nms.service;

import com.ensp.nms.entity.BackupSchedule;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceConfig;
import com.ensp.nms.repository.BackupScheduleRepository;
import com.ensp.nms.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackupScheduleService {

    private final BackupScheduleRepository backupScheduleRepository;
    private final DeviceConfigService deviceConfigService;
    private final DeviceRepository deviceRepository;
    private final ConfigChangeLogService configChangeLogService;

    public List<BackupSchedule> getAllSchedules() {
        return backupScheduleRepository.findAll();
    }

    public List<BackupSchedule> getActiveSchedules() {
        return backupScheduleRepository.findByIsActiveTrue();
    }

    public List<BackupSchedule> getSchedulesByDeviceId(Long deviceId) {
        return backupScheduleRepository.findByDeviceId(deviceId);
    }

    public Optional<BackupSchedule> getScheduleById(Long id) {
        return backupScheduleRepository.findById(id);
    }

    @Transactional
    public BackupSchedule createSchedule(BackupSchedule schedule) {
        BackupSchedule matched = findMatching(schedule.getDeviceId(), schedule.getScheduleType(),
                schedule.getConfigType(), schedule.getScheduleTime());
        if (matched != null) {
            applyTemplateFields(matched, schedule);
            if (schedule.getDeviceName() != null) {
                matched.setDeviceName(schedule.getDeviceName());
            }
            return backupScheduleRepository.save(matched);
        }
        return backupScheduleRepository.save(schedule);
    }

    /**
     * 备份策略：同一周期/类型为多台设备批量创建或更新定时任务（同设备+周期+配置类型去重）。
     */
    @Transactional
    public Map<String, Object> createPolicy(List<Long> deviceIds, BackupSchedule template) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new RuntimeException("请至少选择一台设备");
        }
        List<BackupSchedule> created = new ArrayList<>();
        List<BackupSchedule> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Long deviceId : deviceIds) {
            Device device = deviceRepository.findById(deviceId).orElse(null);
            if (device == null) {
                skipped.add("设备不存在: " + deviceId);
                continue;
            }
            String scheduleType = template.getScheduleType() != null ? template.getScheduleType() : "daily";
            String configType = template.getConfigType() != null ? template.getConfigType() : "running";
            String scheduleTime = template.getScheduleTime() != null ? template.getScheduleTime() : "02:00";

            BackupSchedule existing = findMatching(deviceId, scheduleType, configType, null);
            if (existing != null) {
                applyTemplateFields(existing, template);
                existing.setDeviceName(device.getName());
                existing.setScheduleTime(scheduleTime);
                if (template.getSourceGroupId() != null) {
                    existing.setSourceGroupId(template.getSourceGroupId());
                }
                updated.add(backupScheduleRepository.save(existing));
            } else {
                BackupSchedule schedule = new BackupSchedule();
                schedule.setDeviceId(device.getId());
                schedule.setDeviceName(device.getName());
                schedule.setScheduleType(scheduleType);
                schedule.setScheduleTime(scheduleTime);
                schedule.setDayOfWeek(template.getDayOfWeek());
                schedule.setDayOfMonth(template.getDayOfMonth());
                schedule.setConfigType(configType);
                schedule.setIsActive(template.getIsActive() == null || template.getIsActive());
                schedule.setSourceGroupId(template.getSourceGroupId());
                created.add(backupScheduleRepository.save(schedule));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("createdCount", created.size());
        result.put("updatedCount", updated.size());
        result.put("skipped", skipped);
        result.put("schedules", created);
        result.put("updated", updated);
        return result;
    }

    @Transactional
    public Map<String, Object> createPolicyForGroup(Long groupId, BackupSchedule template) {
        List<Device> devices = deviceRepository.findByGroupId(groupId);
        if (devices.isEmpty()) {
            throw new RuntimeException("该分组下没有设备");
        }
        template.setSourceGroupId(groupId);
        List<Long> ids = devices.stream().map(Device::getId).toList();
        Map<String, Object> result = createPolicy(ids, template);
        result.put("groupId", groupId);
        result.put("message", "已按当前分组成员展开为设备计划；成员变更后请使用「同步分组计划」");
        return result;
    }

    /**
     * 按分组当前成员同步计划：为成员补齐/更新，并清理已离开该组且标记为该组来源的计划。
     */
    @Transactional
    public Map<String, Object> syncGroupPolicy(Long groupId, BackupSchedule template, boolean removeOrphans) {
        template.setSourceGroupId(groupId);
        Map<String, Object> result = createPolicyForGroup(groupId, template);
        int removed = 0;
        if (removeOrphans) {
            List<Long> memberIds = deviceRepository.findByGroupId(groupId).stream().map(Device::getId).toList();
            List<BackupSchedule> fromGroup = backupScheduleRepository.findBySourceGroupId(groupId);
            for (BackupSchedule s : fromGroup) {
                if (s.getDeviceId() != null && !memberIds.contains(s.getDeviceId())) {
                    backupScheduleRepository.delete(s);
                    removed++;
                }
            }
        }
        result.put("removedOrphanCount", removed);
        result.put("message", "已同步当前分组成员计划"
                + (removeOrphans ? "，并清理离组成员计划 " + removed + " 条" : ""));
        return result;
    }

    /** 同设备 + 周期类型 + 配置类型视为同一策略槽位；scheduleTime 可选匹配 */
    private BackupSchedule findMatching(Long deviceId, String scheduleType, String configType, String scheduleTime) {
        if (deviceId == null) {
            return null;
        }
        String type = scheduleType != null ? scheduleType : "daily";
        String cfg = configType != null ? configType : "running";
        return backupScheduleRepository.findByDeviceId(deviceId).stream()
                .filter(s -> Objects.equals(normalize(s.getScheduleType(), "daily"), type)
                        && Objects.equals(normalize(s.getConfigType(), "running"), cfg)
                        && (scheduleTime == null || Objects.equals(normalize(s.getScheduleTime(), "02:00"), scheduleTime)))
                .findFirst()
                .orElse(null);
    }

    private void applyTemplateFields(BackupSchedule target, BackupSchedule template) {
        if (template.getScheduleType() != null) {
            target.setScheduleType(template.getScheduleType());
        }
        if (template.getScheduleTime() != null) {
            target.setScheduleTime(template.getScheduleTime());
        }
        if (template.getDayOfWeek() != null) {
            target.setDayOfWeek(template.getDayOfWeek());
        }
        if (template.getDayOfMonth() != null) {
            target.setDayOfMonth(template.getDayOfMonth());
        }
        if (template.getConfigType() != null) {
            target.setConfigType(template.getConfigType());
        }
        if (template.getIsActive() != null) {
            target.setIsActive(template.getIsActive());
        }
    }

    private static String normalize(String v, String def) {
        return v != null && !v.isBlank() ? v.trim() : def;
    }

    @Transactional
    public BackupSchedule updateSchedule(Long id, BackupSchedule schedule) {
        BackupSchedule existing = backupScheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found"));
        existing.setDeviceId(schedule.getDeviceId());
        existing.setDeviceName(schedule.getDeviceName());
        existing.setScheduleType(schedule.getScheduleType());
        existing.setScheduleTime(schedule.getScheduleTime());
        existing.setDayOfWeek(schedule.getDayOfWeek());
        existing.setDayOfMonth(schedule.getDayOfMonth());
        existing.setConfigType(schedule.getConfigType());
        existing.setIsActive(schedule.getIsActive());
        return backupScheduleRepository.save(existing);
    }

    @Transactional
    public void deleteSchedule(Long id) {
        backupScheduleRepository.deleteById(id);
    }

    public Map<String, Object> executeSchedule(BackupSchedule schedule) {
        log.info("Executing backup schedule for device: {}", schedule.getDeviceId());
        Map<String, Object> result = new HashMap<>();
        result.put("scheduleId", schedule.getId());
        result.put("deviceId", schedule.getDeviceId());
        try {
            DeviceConfig saved = deviceConfigService.backupConfig(
                    schedule.getDeviceId(),
                    schedule.getConfigType(),
                    "system-scheduled",
                    "定时备份: " + (schedule.getScheduleType() != null ? schedule.getScheduleType() : "daily")
            );
            schedule.setLastRun(LocalDateTime.now());
            schedule.setLastStatus("success");
            schedule.setLastResult("Backup completed successfully, version " + saved.getConfigVersion());
            result.put("success", true);
            result.put("configVersion", saved.getConfigVersion());
            result.put("message", schedule.getLastResult());
        } catch (Exception e) {
            log.error("Scheduled backup failed for device {}", schedule.getDeviceId(), e);
            schedule.setLastRun(LocalDateTime.now());
            schedule.setLastStatus("failed");
            schedule.setLastResult("Error: " + e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            try {
                var device = deviceRepository.findById(schedule.getDeviceId()).orElse(null);
                configChangeLogService.record(
                        schedule.getDeviceId(),
                        device != null ? device.getName() : null,
                        "backup",
                        "system-scheduled",
                        "定时备份失败: " + (schedule.getScheduleType() != null ? schedule.getScheduleType() : "daily"),
                        null,
                        e.getMessage(),
                        "failed",
                        null,
                        null
                );
            } catch (Exception ignore) {
                log.warn("写入定时备份失败变更记录时出错: {}", ignore.getMessage());
            }
        }
        backupScheduleRepository.save(schedule);
        return result;
    }
}
