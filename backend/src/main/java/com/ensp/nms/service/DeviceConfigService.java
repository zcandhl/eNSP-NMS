package com.ensp.nms.service;

import com.ensp.nms.dto.ConfigHealthOverview;
import com.ensp.nms.dto.DeviceBackupHealth;
import com.ensp.nms.dto.DeviceBackupLatestView;
import com.ensp.nms.dto.DeviceBackupStatsView;
import com.ensp.nms.dto.DeviceConfigSummary;
import com.ensp.nms.entity.BackupSchedule;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DeviceConfig;
import com.ensp.nms.entity.ConfigChangeLog;
import com.ensp.nms.repository.BackupScheduleRepository;
import com.ensp.nms.repository.DeviceConfigRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.security.SecurityUtils;
import com.ensp.nms.ssh.SshClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceConfigService {

    /** 超过该天数未备份视为过期 */
    public static final int STALE_BACKUP_DAYS = 7;

    private final DeviceConfigRepository deviceConfigRepository;
    private final DeviceRepository deviceRepository;
    private final BackupScheduleRepository backupScheduleRepository;
    private final SshClient sshClient;
    private final ConfigChangeLogService configChangeLogService;
    private final AuditLogService auditLogService;

    public List<DeviceConfigSummary> getConfigSummariesByDeviceId(Long deviceId) {
        return deviceConfigRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId).stream()
                .map(this::toSummary)
                .toList();
    }

    public Page<DeviceConfigSummary> getConfigSummariesByDeviceId(Long deviceId, Pageable pageable) {
        return deviceConfigRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId, pageable)
                .map(this::toSummary);
    }

    public Optional<DeviceConfig> getConfigById(Long id) {
        return deviceConfigRepository.findById(id);
    }

    /** 配置健康总览：仅聚合元数据，不加载备份全文 */
    public ConfigHealthOverview getBackupHealthOverview() {
        List<Device> devices = deviceRepository.findAll();

        Map<Long, DeviceBackupStatsView> statsByDevice = new HashMap<>();
        for (DeviceBackupStatsView s : deviceConfigRepository.aggregateBackupStats()) {
            if (s.getDeviceId() != null) {
                statsByDevice.put(s.getDeviceId(), s);
            }
        }
        Map<Long, DeviceBackupLatestView> latestByDevice = new HashMap<>();
        for (DeviceBackupLatestView latest : deviceConfigRepository.findLatestMetaPerDevice()) {
            if (latest.getDeviceId() == null) continue;
            DeviceBackupLatestView prev = latestByDevice.get(latest.getDeviceId());
            if (prev == null || (latest.getCreatedAt() != null && (prev.getCreatedAt() == null
                    || latest.getCreatedAt().isAfter(prev.getCreatedAt())))) {
                latestByDevice.put(latest.getDeviceId(), latest);
            }
        }

        Map<Long, BackupSchedule> scheduleByDevice = new HashMap<>();
        for (BackupSchedule s : backupScheduleRepository.findAll()) {
            if (s.getDeviceId() == null) continue;
            BackupSchedule prev = scheduleByDevice.get(s.getDeviceId());
            if (prev == null || (s.getLastRun() != null && (prev.getLastRun() == null || s.getLastRun().isAfter(prev.getLastRun())))) {
                scheduleByDevice.put(s.getDeviceId(), s);
            }
        }

        LocalDateTime staleBefore = LocalDateTime.now().minusDays(STALE_BACKUP_DAYS);
        ConfigHealthOverview overview = new ConfigHealthOverview();
        overview.setStaleDays(STALE_BACKUP_DAYS);
        overview.setDeviceTotal(devices.size());

        int never = 0, stale = 0, failed = 0;
        List<DeviceBackupHealth> rows = new ArrayList<>();
        for (Device d : devices) {
            DeviceBackupHealth row = new DeviceBackupHealth();
            row.setDeviceId(d.getId());
            row.setDeviceName(d.getName());
            row.setIpAddress(d.getIpAddress());
            row.setStatus(d.getStatus());

            DeviceBackupStatsView stats = statsByDevice.get(d.getId());
            DeviceBackupLatestView latest = latestByDevice.get(d.getId());
            int count = stats != null && stats.getBackupCount() != null ? stats.getBackupCount().intValue() : 0;
            row.setBackupCount(count);
            if (latest != null) {
                row.setLastBackupAt(latest.getCreatedAt());
                row.setLastBackupType(latest.getConfigType());
                row.setLastBackupVersion(latest.getConfigVersion());
            } else if (stats != null) {
                row.setLastBackupAt(stats.getLastBackupAt());
            }

            BackupSchedule sch = scheduleByDevice.get(d.getId());
            if (sch != null) {
                row.setScheduleStatus(sch.getLastStatus());
                row.setScheduleLastRun(sch.getLastRun());
            }

            String health;
            String label;
            if (sch != null && "failed".equalsIgnoreCase(sch.getLastStatus())) {
                health = "failed";
                label = "计划失败";
                failed++;
            } else if (count == 0) {
                health = "never";
                label = "从未备份";
                never++;
            } else if (row.getLastBackupAt() != null && row.getLastBackupAt().isBefore(staleBefore)) {
                health = "stale";
                label = "超过" + STALE_BACKUP_DAYS + "天";
                stale++;
            } else {
                health = "ok";
                label = "正常";
            }
            row.setHealth(health);
            row.setHealthLabel(label);
            rows.add(row);
        }

        overview.setNeverBackedUp(never);
        overview.setStaleOverDays(stale);
        overview.setScheduleFailed(failed);
        overview.setDevices(rows);
        return overview;
    }

    /** 拉取设备当前运行/启动配置（不入库，用于对比） */
    public String pullLiveConfig(Long deviceId, String configType) throws Exception {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        if (device.getSshUsername() == null || device.getSshPassword() == null) {
            throw new RuntimeException("SSH credentials not configured for device");
        }
        boolean startup = "startup".equalsIgnoreCase(configType);
        int port = device.getSshPort() != null ? device.getSshPort() : 22;
        return sshClient.getHuaweiConfig(
                device.getIpAddress(), port, device.getSshUsername(), device.getSshPassword(), startup);
    }

    /**
     * 受控只读 show：白名单命令，禁止进入 system-view / 改配。
     */
    public String runReadOnlyShow(Long deviceId, String command) throws Exception {
        String cmd = normalizeShowCommand(command);
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        if (device.getSshUsername() == null || device.getSshPassword() == null) {
            throw new RuntimeException("SSH credentials not configured for device");
        }
        int port = device.getSshPort() != null ? device.getSshPort() : 22;
        return sshClient.executeCommand(
                device.getIpAddress(), port, device.getSshUsername(), device.getSshPassword(), cmd);
    }

    public static String normalizeShowCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("缺少 command");
        }
        String cmd = command.trim().replaceAll("\\s+", " ");
        String lower = cmd.toLowerCase();
        if (lower.contains("system-view") || lower.startsWith("system")
                || lower.contains("undo") || lower.contains("reboot")
                || lower.contains("save") || lower.contains("reset")
                || lower.contains("delete") || lower.contains("commit")
                || lower.contains("quit")) {
            throw new IllegalArgumentException("禁止写操作命令");
        }
        if (lower.contains("|") && !lower.matches(".*\\|\\s*(include|exclude)\\s+.+")) {
            throw new IllegalArgumentException("仅允许 display 后接 | include/exclude 过滤");
        }
        boolean allowed = lower.equals("display version")
                || lower.equals("display device")
                || lower.equals("display interface brief")
                || lower.equals("display ip interface brief")
                || lower.equals("display cpu-usage")
                || lower.equals("display memory-usage")
                || lower.equals("display clock")
                || lower.startsWith("display current-configuration | include ")
                || lower.startsWith("display current-configuration | exclude ");
        if (!allowed) {
            throw new IllegalArgumentException("命令不在只读白名单：" + cmd);
        }
        return cmd;
    }

    private DeviceConfigSummary toSummary(DeviceConfig c) {
        DeviceConfigSummary s = new DeviceConfigSummary();
        s.setId(c.getId());
        s.setDeviceId(c.getDeviceId());
        s.setConfigType(c.getConfigType());
        s.setConfigVersion(c.getConfigVersion());
        s.setDescription(c.getDescription());
        s.setCreatedAt(c.getCreatedAt());
        s.setCreatedBy(c.getCreatedBy());
        return s;
    }

    @Transactional
    public DeviceConfig backupConfig(Long deviceId, String configType, String createdBy) throws Exception {
        return backupConfig(deviceId, configType, createdBy, null);
    }

    @Transactional
    public DeviceConfig backupConfig(Long deviceId, String configType, String createdBy, String description) throws Exception {
        return backupConfig(deviceId, configType, createdBy, description, false);
    }

    @Transactional
    public DeviceConfig backupConfig(Long deviceId, String configType, String createdBy, String description,
                                     boolean writeAudit) throws Exception {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        if (device.getSshUsername() == null || device.getSshPassword() == null) {
            throw new RuntimeException("SSH credentials not configured for device");
        }

        boolean isStartup = "startup".equalsIgnoreCase(configType);
        int port = device.getSshPort() != null ? device.getSshPort() : 22;
        String configContent = sshClient.getHuaweiConfig(
                device.getIpAddress(),
                port,
                device.getSshUsername(),
                device.getSshPassword(),
                isStartup
        );

        DeviceConfig config = new DeviceConfig();
        config.setDeviceId(deviceId);
        config.setConfigType(configType);
        config.setContent(configContent);
        config.setConfigVersion(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        if (description != null && !description.isBlank()) {
            config.setDescription(description.trim());
        } else {
            config.setDescription("Auto backup at " + LocalDateTime.now());
        }
        String operator = createdBy != null && !createdBy.isBlank() ? createdBy : "system";
        config.setCreatedBy(operator);

        DeviceConfig saved = deviceConfigRepository.save(config);
        ConfigChangeLog changeLog = configChangeLogService.record(
                deviceId,
                device.getName(),
                "backup",
                operator,
                saved.getDescription(),
                null,
                "备份成功，版本 " + saved.getConfigVersion(),
                "success",
                null,
                saved.getConfigVersion()
        );
        if (writeAudit) {
            auditConfigChange("backup", deviceId, device.getName(), operator, "success",
                    "备份配置，版本 " + saved.getConfigVersion(), changeLog);
        }
        enforceRetention(deviceId);
        return saved;
    }

    /** 每设备最多保留份数，超出删除最旧备份 */
    public static final int MAX_BACKUPS_PER_DEVICE = 30;

    @Transactional
    public int enforceRetention(Long deviceId) {
        List<DeviceConfig> all = deviceConfigRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId);
        if (all.size() <= MAX_BACKUPS_PER_DEVICE) {
            return 0;
        }
        List<DeviceConfig> excess = all.subList(MAX_BACKUPS_PER_DEVICE, all.size());
        int n = excess.size();
        deviceConfigRepository.deleteAll(excess);
        log.info("设备 {} 备份保留清理：删除 {} 条旧版本（保留最多 {} 份）",
                deviceId, n, MAX_BACKUPS_PER_DEVICE);
        return n;
    }

    @Transactional
    public int enforceRetentionForAllDevices() {
        int total = 0;
        for (Long deviceId : deviceConfigRepository.findDistinctDeviceIds()) {
            total += enforceRetention(deviceId);
        }
        return total;
    }

    @Transactional
    public void deleteConfig(Long id) {
        deviceConfigRepository.deleteById(id);
    }

    /** 批量删除备份（可选校验均属同一设备） */
    @Transactional
    public Map<String, Object> batchDeleteConfigs(List<Long> ids, Long expectedDeviceId) {
        List<Long> idList = ids != null ? ids : List.of();
        List<Map<String, Object>> failures = new ArrayList<>();
        int deleted = 0;
        for (Long id : idList) {
            if (id == null) continue;
            try {
                DeviceConfig config = deviceConfigRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("配置不存在: " + id));
                if (expectedDeviceId != null && !expectedDeviceId.equals(config.getDeviceId())) {
                    throw new RuntimeException("配置不属于当前设备");
                }
                deviceConfigRepository.deleteById(id);
                deleted++;
            } catch (Exception e) {
                Map<String, Object> fail = new HashMap<>();
                fail.put("id", id);
                fail.put("message", e.getMessage());
                failures.add(fail);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("success", failures.isEmpty() && deleted > 0);
        out.put("deletedCount", deleted);
        out.put("failCount", failures.size());
        out.put("totalCount", idList.size());
        out.put("failures", failures);
        return out;
    }

    /** 批量导出为 ZIP（UTF-8） */
    public byte[] exportConfigsAsZip(List<Long> ids) throws Exception {
        List<Long> idList = ids != null ? ids.stream().filter(Objects::nonNull).distinct().toList() : List.of();
        if (idList.isEmpty()) {
            throw new RuntimeException("请选择要导出的配置");
        }
        List<DeviceConfig> configs = new ArrayList<>();
        for (Long id : idList) {
            DeviceConfig config = deviceConfigRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("配置不存在: " + id));
            configs.add(config);
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.Set<String> usedNames = new java.util.HashSet<>();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (DeviceConfig config : configs) {
                String deviceName = deviceRepository.findById(config.getDeviceId())
                        .map(Device::getName).orElse("device" + config.getDeviceId());
                String safeDevice = deviceName.replaceAll("[\\\\/:*?\"<>|]", "_");
                String base = String.format("%s_config_%s_%s.txt",
                        safeDevice, config.getConfigVersion(), config.getConfigType());
                String entryName = base;
                int dup = 1;
                while (usedNames.contains(entryName)) {
                    entryName = base.replace(".txt", "_" + dup++ + ".txt");
                }
                usedNames.add(entryName);
                zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                String content = config.getContent() != null ? config.getContent() : "";
                zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    public boolean testSshConnection(Long deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        if (device.getSshUsername() == null || device.getSshPassword() == null) {
            return false;
        }

        int port = device.getSshPort() != null ? device.getSshPort() : 22;
        return sshClient.testConnection(
                device.getIpAddress(),
                port,
                device.getSshUsername(),
                device.getSshPassword()
        );
    }

    public Map<String, Object> restoreConfig(Long deviceId, Long configId) throws Exception {
        return restoreConfig(deviceId, configId, null, false, true);
    }

    public Map<String, Object> restoreConfig(Long deviceId, Long configId,
                                             java.util.function.BiConsumer<Integer, Integer> progress) throws Exception {
        return restoreConfig(deviceId, configId, progress, false, false);
    }

    /**
     * @param preBackup 恢复前先备份当前 running，写入变更记录
     */
    public Map<String, Object> restoreConfig(Long deviceId, Long configId,
                                             java.util.function.BiConsumer<Integer, Integer> progress,
                                             boolean preBackup) throws Exception {
        return restoreConfig(deviceId, configId, progress, preBackup, false);
    }

    public Map<String, Object> restoreConfig(Long deviceId, Long configId,
                                             java.util.function.BiConsumer<Integer, Integer> progress,
                                             boolean preBackup, boolean writeAudit) throws Exception {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        DeviceConfig config = deviceConfigRepository.findById(configId)
                .orElseThrow(() -> new RuntimeException("Config not found"));

        if (config.getDeviceId() == null || !config.getDeviceId().equals(deviceId)) {
            throw new RuntimeException("配置不属于当前设备，禁止跨设备恢复");
        }

        if (device.getSshUsername() == null || device.getSshPassword() == null) {
            throw new RuntimeException("SSH credentials not configured");
        }

        String operator = SecurityUtils.currentOperator();
        String preBackupVersion = null;
        if (preBackup) {
            if (progress != null) {
                progress.accept(0, 100);
            }
            DeviceConfig snapshot = backupConfig(deviceId, "running", operator, "恢复前自动备份");
            preBackupVersion = snapshot.getConfigVersion();
        }

        int port = device.getSshPort() != null ? device.getSshPort() : 22;
        List<String> lines = SshClient.prepareRestoreCommands(config.getContent());
        SshClient.CommandBatchResult batch = sshClient.executeConfigCommands(
                device.getIpAddress(), port, device.getSshUsername(), device.getSshPassword(),
                lines, "恢复配置", progress
        );

        ConfigChangeLog changeLog = configChangeLogService.record(
                deviceId,
                device.getName(),
                "restore",
                operator,
                "恢复配置版本 " + config.getConfigVersion()
                        + (preBackupVersion != null ? "（预备份 " + preBackupVersion + "）" : ""),
                truncate(config.getContent(), 4000),
                batch.summary(),
                batch.ok() ? "success" : "partial",
                preBackupVersion,
                config.getConfigVersion()
        );
        if (writeAudit) {
            auditConfigChange("restore", deviceId, device.getName(), operator,
                    batch.ok() ? "success" : "partial",
                    "恢复配置版本 " + config.getConfigVersion(), changeLog);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", batch.ok());
        response.put("result", batch.summary());
        response.put("successCount", batch.successCount());
        response.put("failCount", batch.failCount());
        response.put("sourceConfigType", config.getConfigType());
        // 华为侧：将备份内容写入运行配置，文末 save 会同步写入启动配置
        response.put("applyMode", "running_then_save");
        response.put("note", "startup".equalsIgnoreCase(config.getConfigType())
                ? "启动配置备份已下发到运行配置，并已执行 save 写入启动配置"
                : "运行配置已下发，并已执行 save 写入启动配置");
        if (preBackupVersion != null) {
            response.put("preBackupVersion", preBackupVersion);
        }
        response.put("restoredVersion", config.getConfigVersion());
        return response;
    }

    /**
     * 批量备份多台设备（串行 SSH），供异步任务调用。
     */
    public Map<String, Object> batchBackup(List<Long> deviceIds, String configType, String description,
                                           String operator,
                                           java.util.function.BiConsumer<Integer, Integer> progress) {
        List<Long> ids = deviceIds != null ? deviceIds : List.of();
        String type = (configType == null || configType.isBlank()) ? "running" : configType;
        String op = operator != null && !operator.isBlank() ? operator : SecurityUtils.currentOperator();
        String desc = description != null && !description.isBlank()
                ? description.trim()
                : "批量备份";

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int index = 0;
        int total = Math.max(ids.size(), 1);
        if (progress != null) {
            progress.accept(0, total);
        }
        for (Long deviceId : ids) {
            index++;
            Map<String, Object> one = new HashMap<>();
            one.put("deviceId", deviceId);
            try {
                Device device = deviceRepository.findById(deviceId).orElse(null);
                one.put("deviceName", device != null ? device.getName() : null);
                DeviceConfig saved = backupConfig(deviceId, type, op, desc);
                one.put("success", true);
                one.put("configId", saved.getId());
                one.put("configVersion", saved.getConfigVersion());
                one.put("message", "备份成功");
                successCount++;
            } catch (Exception e) {
                one.put("success", false);
                one.put("message", e.getMessage());
                try {
                    Device device = deviceRepository.findById(deviceId).orElse(null);
                    configChangeLogService.record(
                            deviceId,
                            device != null ? device.getName() : null,
                            "backup",
                            op,
                            desc,
                            null,
                            e.getMessage(),
                            "failed",
                            null,
                            null
                    );
                } catch (Exception ignore) {
                    // ignore audit failure
                }
            }
            results.add(one);
            if (progress != null) {
                progress.accept(index, total);
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("success", successCount == ids.size() && !ids.isEmpty());
        out.put("successCount", successCount);
        out.put("failCount", ids.size() - successCount);
        out.put("totalCount", ids.size());
        out.put("results", results);
        return out;
    }

    public Map<String, Object> applyConfigTemplate(Long deviceId, String templateContent) throws Exception {
        return applyConfigTemplate(deviceId, templateContent, null);
    }

    public Map<String, Object> applyConfigTemplate(Long deviceId, String templateContent, String reason) throws Exception {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        if (device.getSshUsername() == null || device.getSshPassword() == null) {
            throw new RuntimeException("SSH credentials not configured");
        }

        int port = device.getSshPort() != null ? device.getSshPort() : 22;
        List<String> lines = Arrays.asList(templateContent != null ? templateContent.split("\n") : new String[0]);
        SshClient.CommandBatchResult batch = sshClient.executeConfigCommands(
                device.getIpAddress(), port, device.getSshUsername(), device.getSshPassword(),
                lines, "应用配置"
        );

        String operator = SecurityUtils.currentOperator();
        String reasonText = (reason != null && !reason.isBlank()) ? reason.trim() : "应用配置模板/命令";
        ConfigChangeLog changeLog = configChangeLogService.record(
                deviceId,
                device.getName(),
                "apply",
                operator,
                reasonText,
                truncate(templateContent, 4000),
                batch.summary(),
                batch.ok() ? "success" : "partial",
                null,
                null
        );
        auditConfigChange("apply", deviceId, device.getName(), operator,
                batch.ok() ? "success" : "partial", reasonText, changeLog);

        Map<String, Object> response = new HashMap<>();
        response.put("success", batch.ok());
        response.put("result", batch.summary());
        response.put("successCount", batch.successCount());
        response.put("failCount", batch.failCount());
        response.put("message", batch.ok() ? "应用成功" : "部分或全部命令执行失败");
        return response;
    }

    public Map<String, Object> batchApplyConfig(List<Long> deviceIds, String templateContent,
                                                boolean enableVariables, boolean parallel) {
        return batchApplyConfig(deviceIds, templateContent, enableVariables, parallel, null, null);
    }

    public Map<String, Object> batchApplyConfig(List<Long> deviceIds, String templateContent,
                                                boolean enableVariables, boolean parallel,
                                                java.util.function.BiConsumer<Integer, Integer> deviceProgress) {
        return batchApplyConfig(deviceIds, templateContent, enableVariables, parallel, deviceProgress, null);
    }

    public Map<String, Object> batchApplyConfig(List<Long> deviceIds, String templateContent,
                                                boolean enableVariables, boolean parallel,
                                                java.util.function.BiConsumer<Integer, Integer> deviceProgress,
                                                String reason) {
        Map<String, Object> results = new HashMap<>();
        List<Map<String, Object>> deviceResults = new ArrayList<>();
        String operator = SecurityUtils.currentOperator();
        String reasonText = (reason != null && !reason.isBlank()) ? reason.trim() : "批量配置下发";
        int totalDevices = Math.max(deviceIds != null ? deviceIds.size() : 0, 1);

        if (parallel) {
            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(deviceIds.size(), 2));
            java.util.List<java.util.concurrent.Future<Map<String, Object>>> futures = new java.util.ArrayList<>();

            int index = 1;
            for (Long deviceId : deviceIds) {
                final int deviceIndex = index++;
                futures.add(executor.submit(() ->
                        processDeviceConfig(deviceId, templateContent, enableVariables, deviceIndex, operator, reasonText)));
            }

            int done = 0;
            for (java.util.concurrent.Future<Map<String, Object>> future : futures) {
                try {
                    deviceResults.add(future.get());
                } catch (Exception e) {
                    log.error("批量下发任务失败", e);
                }
                done++;
                if (deviceProgress != null) {
                    deviceProgress.accept(done, totalDevices);
                }
            }
            executor.shutdown();
        } else {
            int index = 1;
            for (Long deviceId : deviceIds) {
                deviceResults.add(processDeviceConfig(deviceId, templateContent, enableVariables, index++, operator, reasonText));
                if (deviceProgress != null) {
                    deviceProgress.accept(index - 1, totalDevices);
                }
            }
        }

        results.put("results", deviceResults);
        long successCount = deviceResults.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
        results.put("successCount", successCount);
        results.put("totalCount", deviceResults.size());
        return results;
    }

    private Map<String, Object> processDeviceConfig(Long deviceId, String templateContent,
                                                    boolean enableVariables, int index, String operator,
                                                    String reason) {
        Map<String, Object> deviceResult = new HashMap<>();
        try {
            Device device = deviceRepository.findById(deviceId).orElse(null);
            if (device == null) {
                deviceResult.put("deviceId", deviceId);
                deviceResult.put("success", false);
                deviceResult.put("message", "Device not found");
                return deviceResult;
            }

            String content = templateContent;
            if (enableVariables) {
                content = replaceVariables(content, device, index);
            }

            int port = device.getSshPort() != null ? device.getSshPort() : 22;
            List<String> lines = Arrays.asList(content != null ? content.split("\n") : new String[0]);
            SshClient.CommandBatchResult batch = sshClient.executeConfigCommands(
                    device.getIpAddress(), port, device.getSshUsername(), device.getSshPassword(),
                    lines, "批量下发"
            );

            configChangeLogService.record(
                    deviceId,
                    device.getName(),
                    "batch",
                    operator,
                    reason != null ? reason : "批量配置下发",
                    truncate(content, 4000),
                    batch.summary(),
                    batch.ok() ? "success" : "partial",
                    null,
                    null
            );

            deviceResult.put("deviceId", deviceId);
            deviceResult.put("deviceName", device.getName());
            deviceResult.put("success", batch.ok());
            deviceResult.put("result", batch.summary());
            if (!batch.ok()) {
                deviceResult.put("message", "部分或全部命令执行失败");
            }
        } catch (Exception e) {
            deviceResult.put("deviceId", deviceId);
            deviceResult.put("success", false);
            deviceResult.put("message", e.getMessage());
            log.error("设备配置下发失败: deviceId={}, error={}", deviceId, e.getMessage());
        }
        return deviceResult;
    }

    /**
     * 下发前预检：解析变量后的命令行 vs 当前 running，估算新增行数（不真正下发）。
     */
    public Map<String, Object> previewBatchApply(List<Long> deviceIds, String templateContent,
                                                 boolean enableVariables) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> devices = new ArrayList<>();
        int index = 1;
        for (Long deviceId : deviceIds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", deviceId);
            Device device = deviceRepository.findById(deviceId).orElse(null);
            if (device == null) {
                row.put("success", false);
                row.put("message", "设备不存在");
                devices.add(row);
                continue;
            }
            row.put("deviceName", device.getName());
            row.put("deviceIp", device.getIpAddress());
            row.put("offline", !"online".equalsIgnoreCase(device.getStatus()));
            String content = templateContent;
            if (enableVariables) {
                content = replaceVariables(content, device, index);
            }
            index++;
            List<String> planned = Arrays.stream(content != null ? content.split("\n") : new String[0])
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#") && !s.startsWith("!"))
                    .toList();
            row.put("commandCount", planned.size());
            row.put("commandsPreview", String.join("\n", planned.size() > 40 ? planned.subList(0, 40) : planned)
                    + (planned.size() > 40 ? "\n...(" + (planned.size() - 40) + " more)" : ""));

            String live = null;
            String liveError = null;
            try {
                live = pullLiveConfig(deviceId, "running");
            } catch (Exception e) {
                liveError = e.getMessage();
            }
            if (live == null) {
                row.put("liveAvailable", false);
                row.put("liveError", liveError);
                row.put("newLineEstimate", planned.size());
                row.put("alreadyPresentEstimate", 0);
            } else {
                java.util.Set<String> liveSet = Arrays.stream(live.split("\n"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                int already = 0;
                List<String> sampleNew = new ArrayList<>();
                for (String line : planned) {
                    String key = line.toLowerCase(java.util.Locale.ROOT);
                    // 视图切换命令不计入差异
                    if (key.equals("system-view") || key.equals("return") || key.equals("quit")
                            || key.equals("save") || key.equals("y") || key.equals("n")) {
                        continue;
                    }
                    if (liveSet.contains(key)) {
                        already++;
                    } else if (sampleNew.size() < 8) {
                        sampleNew.add(line);
                    }
                }
                int significant = (int) planned.stream()
                        .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                        .filter(k -> !k.equals("system-view") && !k.equals("return") && !k.equals("quit")
                                && !k.equals("save") && !k.equals("y") && !k.equals("n"))
                        .count();
                row.put("liveAvailable", true);
                row.put("alreadyPresentEstimate", already);
                row.put("newLineEstimate", Math.max(0, significant - already));
                row.put("sampleNewLines", sampleNew);
            }
            row.put("success", true);
            devices.add(row);
        }
        out.put("devices", devices);
        out.put("deviceCount", devices.size());
        long withLive = devices.stream().filter(d -> Boolean.TRUE.equals(d.get("liveAvailable"))).count();
        out.put("liveOkCount", withLive);
        int totalNew = devices.stream()
                .mapToInt(d -> d.get("newLineEstimate") instanceof Number n ? n.intValue() : 0)
                .sum();
        out.put("totalNewLineEstimate", totalNew);
        out.put("message", "预检完成：可拉取 running " + withLive + "/" + devices.size()
                + "，估算新增配置行约 " + totalNew);
        return out;
    }

    private String replaceVariables(String content, Device device, int index) {
        return content
                .replace("${deviceId}", String.valueOf(device.getId()))
                .replace("${name}", device.getName())
                .replace("${ipAddress}", device.getIpAddress())
                .replace("${model}", device.getModel() != null ? device.getModel() : "")
                .replace("${index}", String.valueOf(index));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void auditConfigChange(String action, Long deviceId, String deviceName, String operator,
                                   String status, String summary, ConfigChangeLog changeLog) {
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("config")
                .action(action)
                .operator(operator)
                .targetType("device")
                .targetId(deviceId != null ? String.valueOf(deviceId) : null)
                .targetName(deviceName)
                .status(status)
                .summary(summary)
                .clientIp(AuditLogService.currentClientIp())
                .refType(changeLog != null && changeLog.getId() != null ? "config_change_log" : null)
                .refId(changeLog != null && changeLog.getId() != null ? String.valueOf(changeLog.getId()) : null)
                .build());
    }
}
