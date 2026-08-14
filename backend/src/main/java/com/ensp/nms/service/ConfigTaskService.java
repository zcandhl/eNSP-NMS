package com.ensp.nms.service;

import com.ensp.nms.entity.ConfigTask;
import com.ensp.nms.repository.ConfigTaskRepository;
import com.ensp.nms.security.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配置恢复/批量下发异步任务：内存实时进度 + DB 持久化（重启可查，约 7 天）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigTaskService {

    private static final int RETENTION_DAYS = 7;

    private final DeviceConfigService deviceConfigService;
    private final AuditLogService auditLogService;
    private final ConfigTaskRepository configTaskRepository;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, ConfigTaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public ConfigTaskState submitRestore(Long deviceId, Long configId) {
        return submitRestore(deviceId, configId, true);
    }

    public ConfigTaskState submitRestore(Long deviceId, Long configId, boolean preBackup) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("deviceId", deviceId);
        req.put("configId", configId);
        req.put("preBackup", preBackup);

        ConfigTaskState task = newTask("restore", req);
        task.setLabel("恢复配置 #" + configId + " → 设备 " + deviceId);
        task.setTargetCount(1);
        task.setOperator(SecurityUtils.currentOperator());
        persist(task);

        runAsync(task, () -> {
            task.setStatus("RUNNING");
            task.setMessage(preBackup ? "正在创建恢复前备份..." : "正在连接设备并下发配置...");
            task.setProgress(2);
            persist(task);
            try {
                if (isCancelRequested(task)) {
                    markCancelled(task);
                    return;
                }
                Map<String, Object> result = deviceConfigService.restoreConfig(deviceId, configId, (done, total) -> {
                    int pct = total <= 0 ? 95 : Math.min(95, 8 + (done * 87 / total));
                    task.setProgress(pct);
                    task.setMessage("正在执行命令 " + done + "/" + total);
                    persist(task);
                }, preBackup);
                if (isCancelRequested(task)) {
                    markCancelled(task);
                    return;
                }
                task.setResult(result);
                task.setProgress(100);
                boolean ok = Boolean.TRUE.equals(result.get("success"));
                task.setStatus(ok ? "SUCCESS" : "PARTIAL");
                String msg = ok ? "恢复完成" : "恢复完成（存在失败命令）";
                if (result.get("preBackupVersion") != null) {
                    msg += "，预备份 " + result.get("preBackupVersion");
                }
                if (result.get("restoredVersion") != null) {
                    msg += "，恢复版本 " + result.get("restoredVersion");
                }
                task.setMessage(msg);
            } catch (Exception e) {
                log.error("异步恢复失败", e);
                task.setStatus("FAILED");
                task.setError(e.getMessage());
                task.setMessage("恢复失败: " + e.getMessage());
                task.setProgress(100);
            } finally {
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setFinishedAt(LocalDateTime.now());
                    persist(task);
                    recordTaskAudit(task, "restore");
                }
                purgeMemoryOld();
            }
        });
        return task;
    }

    public ConfigTaskState submitBatchApply(List<Long> deviceIds, String content,
                                            boolean enableVariables, boolean parallel) {
        return submitBatchApply(deviceIds, content, enableVariables, parallel, null, 0, null);
    }

    public ConfigTaskState submitBatchApply(List<Long> deviceIds, String content,
                                            boolean enableVariables, boolean parallel, String reason) {
        return submitBatchApply(deviceIds, content, enableVariables, parallel, reason, 0, null);
    }

    /**
     * @param waveSize 首波设备数；0 表示一次全部；&gt;0 时首波完成后进入 PAUSED，可 continue
     * @param resumeFromTaskId 继续任务时合并已有结果
     */
    public ConfigTaskState submitBatchApply(List<Long> deviceIds, String content,
                                            boolean enableVariables, boolean parallel, String reason,
                                            int waveSize, String resumeFromTaskId) {
        List<Long> ids = deviceIds != null ? new ArrayList<>(deviceIds) : new ArrayList<>();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("deviceIds", ids);
        req.put("content", content);
        req.put("enableVariables", enableVariables);
        req.put("parallel", parallel);
        req.put("reason", reason);
        req.put("waveSize", waveSize);
        req.put("pendingDeviceIds", ids);
        req.put("completedDeviceIds", new ArrayList<Long>());

        ConfigTaskState task = newTask("batch", req);
        if (resumeFromTaskId != null) {
            task.setLabel("继续批量下发（源自 " + resumeFromTaskId.substring(0, Math.min(8, resumeFromTaskId.length())) + "…）");
        } else {
            task.setLabel("批量下发 " + ids.size() + " 台设备" + (waveSize > 0 ? "（分波 " + waveSize + "）" : ""));
        }
        task.setTargetCount(ids.size());
        task.setOperator(SecurityUtils.currentOperator());
        persist(task);

        List<Map<String, Object>> priorResults = new ArrayList<>();
        if (resumeFromTaskId != null) {
            getTask(resumeFromTaskId).ifPresent(prev -> {
                Map<String, Object> m = prev.getResult();
                if (m != null && m.get("results") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> row) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            row.forEach((k, v) -> copy.put(String.valueOf(k), v));
                            priorResults.add(copy);
                        }
                    }
                }
            });
        }

        runAsync(task, () -> runBatchApply(task, ids, content, enableVariables, parallel, reason, waveSize, priorResults));
        return task;
    }

    private void runBatchApply(ConfigTaskState task, List<Long> allIds, String content,
                               boolean enableVariables, boolean parallel, String reason,
                               int waveSize, List<Map<String, Object>> priorResults) {
        task.setStatus("RUNNING");
        task.setMessage("开始批量下发...");
        task.setProgress(2);
        persist(task);
        try {
            final List<Long> waveIds;
            final List<Long> remaining;
            if (waveSize > 0 && waveSize < allIds.size()) {
                waveIds = allIds.subList(0, waveSize);
                remaining = new ArrayList<>(allIds.subList(waveSize, allIds.size()));
            } else {
                waveIds = allIds;
                remaining = List.of();
            }

            Map<String, Object> waveResult = deviceConfigService.batchApplyConfig(
                    waveIds, content, enableVariables, parallel,
                    (done, tot) -> {
                        if (isCancelRequested(task)) {
                            return;
                        }
                        int base = priorResults.size();
                        int totalAll = Math.max(base + allIds.size(), 1);
                        int pct = Math.min(95, 5 + ((base + done) * 90 / totalAll));
                        task.setProgress(pct);
                        task.setMessage("已完成设备 " + (base + done) + "/" + (base + tot + remaining.size()));
                        persist(task);
                    },
                    reason
            );

            if (isCancelRequested(task)) {
                mergeAndFinishPartial(task, priorResults, waveResult, "CANCELLED", "已取消");
                return;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> waveRows = (List<Map<String, Object>>) waveResult.getOrDefault("results", List.of());
            List<Map<String, Object>> merged = new ArrayList<>(priorResults);
            merged.addAll(waveRows);

            Map<String, Object> req = readRequest(task);
            req.put("pendingDeviceIds", remaining);
            List<Long> completed = new ArrayList<>();
            Object rawCompleted = req.get("completedDeviceIds");
            if (rawCompleted instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Number n) completed.add(n.longValue());
                    else if (o instanceof String s && !s.isBlank()) completed.add(Long.parseLong(s));
                }
            }
            completed.addAll(waveIds);
            req.put("completedDeviceIds", completed);
            writeRequest(task, req);

            if (!remaining.isEmpty()) {
                Map<String, Object> pausedResult = new LinkedHashMap<>();
                pausedResult.put("results", merged);
                long ok = merged.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
                pausedResult.put("successCount", ok);
                pausedResult.put("totalCount", merged.size());
                pausedResult.put("pendingCount", remaining.size());
                pausedResult.put("pendingDeviceIds", remaining);
                task.setResult(pausedResult);
                task.setStatus("PAUSED");
                task.setProgress(Math.min(95, 5 + (merged.size() * 90 / Math.max(merged.size() + remaining.size(), 1))));
                task.setMessage("首波完成，待继续 " + remaining.size() + " 台（可点继续或取消）");
                persist(task);
                recordTaskAudit(task, "batch_apply_wave");
                return;
            }

            finishBatchMerged(task, merged);
        } catch (Exception e) {
            log.error("异步批量下发失败", e);
            task.setStatus("FAILED");
            task.setError(e.getMessage());
            task.setMessage("批量下发失败: " + e.getMessage());
            task.setProgress(100);
            task.setFinishedAt(LocalDateTime.now());
            persist(task);
            recordTaskAudit(task, "batch_apply");
        } finally {
            purgeMemoryOld();
        }
    }

    private void finishBatchMerged(ConfigTaskState task, List<Map<String, Object>> merged) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", merged);
        long ok = merged.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
        result.put("successCount", ok);
        result.put("totalCount", merged.size());
        task.setResult(result);
        task.setProgress(100);
        if (ok >= merged.size() && !merged.isEmpty()) {
            task.setStatus("SUCCESS");
            task.setMessage("批量下发完成");
        } else if (ok > 0) {
            task.setStatus("PARTIAL");
            task.setMessage("批量下发完成（部分失败）");
        } else {
            task.setStatus("FAILED");
            task.setMessage("批量下发失败");
        }
        task.setFinishedAt(LocalDateTime.now());
        persist(task);
        recordTaskAudit(task, "batch_apply");
    }

    private void mergeAndFinishPartial(ConfigTaskState task, List<Map<String, Object>> prior,
                                       Map<String, Object> waveResult, String status, String message) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> waveRows = (List<Map<String, Object>>) waveResult.getOrDefault("results", List.of());
        List<Map<String, Object>> merged = new ArrayList<>(prior);
        merged.addAll(waveRows);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", merged);
        long ok = merged.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
        result.put("successCount", ok);
        result.put("totalCount", merged.size());
        task.setResult(result);
        task.setStatus(status);
        task.setMessage(message);
        task.setProgress(100);
        task.setFinishedAt(LocalDateTime.now());
        persist(task);
        recordTaskAudit(task, "batch_apply");
    }

    /** 继续 PAUSED 的分波任务 */
    public ConfigTaskState continueBatch(String taskId, Integer nextWaveSize) {
        ConfigTaskState task = getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (!"PAUSED".equals(task.getStatus())) {
            throw new IllegalArgumentException("仅暂停中的分波任务可继续");
        }
        Map<String, Object> req = readRequest(task);
        List<Long> pending = toLongList(req.get("pendingDeviceIds"));
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("没有待下发设备");
        }
        String content = req.get("content") != null ? req.get("content").toString() : "";
        boolean enableVariables = Boolean.TRUE.equals(req.get("enableVariables"));
        boolean parallel = Boolean.TRUE.equals(req.get("parallel"));
        String reason = req.get("reason") != null ? req.get("reason").toString() : null;
        int wave = nextWaveSize != null && nextWaveSize > 0 ? nextWaveSize : pending.size();

        // 在同一任务上继续，保留 id
        task.setCancelRequested(false);
        task.setFinishedAt(null);
        task.setError(null);
        writeRequest(task, req);
        persist(task);

        List<Map<String, Object>> priorResults = new ArrayList<>();
        Map<String, Object> existing = task.getResult();
        if (existing != null && existing.get("results") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> row) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    row.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    priorResults.add(copy);
                }
            }
        }

        final List<Long> pendingFinal = pending;
        final int waveFinal = wave;
        runAsync(task, () -> runBatchApply(task, pendingFinal, content, enableVariables, parallel, reason, waveFinal, priorResults));
        return task;
    }

    /** 对失败设备重新提交新任务 */
    public ConfigTaskState retryFailed(String taskId) {
        ConfigTaskState prev = getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (!"batch".equals(prev.getType())) {
            throw new IllegalArgumentException("仅批量下发任务支持失败重试");
        }
        List<Long> failedIds = new ArrayList<>();
        Map<String, Object> prevResult = prev.getResult();
        if (prevResult != null && prevResult.get("results") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> row && !Boolean.TRUE.equals(row.get("success"))) {
                    Object id = row.get("deviceId");
                    if (id instanceof Number n) failedIds.add(n.longValue());
                    else if (id != null) failedIds.add(Long.parseLong(id.toString()));
                }
            }
        }
        if (failedIds.isEmpty()) {
            throw new IllegalArgumentException("没有失败设备可重试");
        }
        Map<String, Object> req = readRequest(prev);
        String content = req.get("content") != null ? req.get("content").toString() : "";
        boolean enableVariables = Boolean.TRUE.equals(req.get("enableVariables"));
        boolean parallel = Boolean.TRUE.equals(req.get("parallel"));
        String reason = (req.get("reason") != null ? req.get("reason").toString() : "批量配置下发") + "（失败重试）";
        return submitBatchApply(failedIds, content, enableVariables, parallel, reason, 0, null);
    }

    public ConfigTaskState cancel(String taskId) {
        ConfigTaskState task = getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        String st = task.getStatus();
        if ("SUCCESS".equals(st) || "FAILED".equals(st) || "CANCELLED".equals(st)) {
            throw new IllegalArgumentException("任务已结束，无法取消");
        }
        task.setCancelRequested(true);
        if ("PAUSED".equals(st) || "PENDING".equals(st)) {
            markCancelled(task);
        } else {
            task.setMessage("取消请求已提交…");
            persist(task);
        }
        return task;
    }

    public ConfigTaskState submitBatchBackup(List<Long> deviceIds, String configType, String description) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("deviceIds", deviceIds);
        req.put("configType", configType);
        req.put("description", description);

        ConfigTaskState task = newTask("backup", req);
        int total = Math.max(deviceIds != null ? deviceIds.size() : 0, 1);
        task.setTargetCount(deviceIds != null ? deviceIds.size() : 0);
        task.setLabel("批量备份 " + task.getTargetCount() + " 台设备");
        task.setOperator(SecurityUtils.currentOperator());
        persist(task);

        String type = configType;
        String desc = description;
        runAsync(task, () -> {
            task.setStatus("RUNNING");
            task.setMessage("开始批量备份...");
            task.setProgress(2);
            persist(task);
            try {
                Map<String, Object> result = deviceConfigService.batchBackup(
                        deviceIds, type, desc, task.getOperator(),
                        (done, tot) -> {
                            if (isCancelRequested(task)) return;
                            int pct = Math.min(95, 5 + (done * 90 / Math.max(tot, 1)));
                            task.setProgress(pct);
                            task.setMessage("已备份 " + done + "/" + tot);
                            persist(task);
                        }
                );
                if (isCancelRequested(task)) {
                    markCancelled(task);
                    return;
                }
                task.setResult(result);
                task.setProgress(100);
                long ok = result.get("successCount") instanceof Number n ? n.longValue() : 0;
                long all = result.get("totalCount") instanceof Number n ? n.longValue() : total;
                if (ok >= all && all > 0) {
                    task.setStatus("SUCCESS");
                    task.setMessage("批量备份完成");
                } else if (ok > 0) {
                    task.setStatus("PARTIAL");
                    task.setMessage("批量备份完成（部分失败）");
                } else {
                    task.setStatus("FAILED");
                    task.setMessage("批量备份失败");
                }
            } catch (Exception e) {
                log.error("异步批量备份失败", e);
                task.setStatus("FAILED");
                task.setError(e.getMessage());
                task.setMessage("批量备份失败: " + e.getMessage());
                task.setProgress(100);
            } finally {
                if (!"CANCELLED".equals(task.getStatus())) {
                    task.setFinishedAt(LocalDateTime.now());
                    persist(task);
                    recordTaskAudit(task, "batch_backup");
                }
                purgeMemoryOld();
            }
        });
        return task;
    }

    public Optional<ConfigTaskState> getTask(String taskId) {
        ConfigTaskState mem = tasks.get(taskId);
        if (mem != null) return Optional.of(mem);
        return configTaskRepository.findById(taskId).map(this::fromEntity);
    }

    public List<ConfigTaskState> listTasks() {
        Map<String, ConfigTaskState> merged = new LinkedHashMap<>();
        LocalDateTime after = LocalDateTime.now().minusDays(RETENTION_DAYS);
        for (ConfigTask e : configTaskRepository.findByCreatedAtAfterOrderByCreatedAtDesc(after)) {
            merged.put(e.getId(), fromEntity(e));
        }
        // 内存中的进行中任务覆盖 DB 快照
        for (ConfigTaskState t : tasks.values()) {
            merged.put(t.getId(), t);
        }
        return merged.values().stream()
                .sorted((a, b) -> {
                    LocalDateTime ca = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime cb = b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN;
                    return cb.compareTo(ca);
                })
                .limit(100)
                .toList();
    }

    private void runAsync(ConfigTaskState task, Runnable action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        executor.execute(() -> {
            try {
                if (auth != null) {
                    SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                    ctx.setAuthentication(auth);
                    SecurityContextHolder.setContext(ctx);
                }
                action.run();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    private ConfigTaskState newTask(String type, Map<String, Object> request) {
        ConfigTaskState task = new ConfigTaskState();
        task.setId(UUID.randomUUID().toString().replace("-", ""));
        task.setType(type);
        task.setRequest(request != null ? new HashMap<>(request) : new HashMap<>());
        tasks.put(task.getId(), task);
        return task;
    }

    private boolean isCancelRequested(ConfigTaskState task) {
        if (task.isCancelRequested()) return true;
        return configTaskRepository.findById(task.getId())
                .map(e -> Boolean.TRUE.equals(e.getCancelRequested()))
                .orElse(false);
    }

    private void markCancelled(ConfigTaskState task) {
        task.setStatus("CANCELLED");
        task.setMessage("已取消");
        task.setProgress(100);
        task.setFinishedAt(LocalDateTime.now());
        persist(task);
        recordTaskAudit(task, task.getType() != null ? task.getType() : "cancel");
    }

    private void persist(ConfigTaskState task) {
        try {
            ConfigTask entity = configTaskRepository.findById(task.getId()).orElseGet(ConfigTask::new);
            entity.setId(task.getId());
            entity.setType(task.getType());
            entity.setLabel(task.getLabel());
            entity.setOperator(task.getOperator());
            entity.setTargetCount(task.getTargetCount());
            entity.setStatus(task.getStatus());
            entity.setProgress(task.getProgress());
            entity.setMessage(truncate(task.getMessage(), 500));
            entity.setError(truncate(task.getError(), 4000));
            entity.setResultJson(toJson(task.getResult()));
            entity.setRequestJson(toJson(task.getRequest()));
            entity.setCancelRequested(task.isCancelRequested());
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(task.getCreatedAt() != null ? task.getCreatedAt() : LocalDateTime.now());
            }
            entity.setFinishedAt(task.getFinishedAt());
            entity.setUpdatedAt(LocalDateTime.now());
            configTaskRepository.save(entity);
            try {
                configTaskRepository.deleteByFinishedAtBefore(LocalDateTime.now().minusDays(RETENTION_DAYS));
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.warn("持久化配置任务失败 {}: {}", task.getId(), e.getMessage());
        }
    }

    private void purgeMemoryOld() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        tasks.entrySet().removeIf(e -> {
            ConfigTaskState t = e.getValue();
            return t.getFinishedAt() != null && t.getFinishedAt().isBefore(cutoff);
        });
    }

    private ConfigTaskState fromEntity(ConfigTask e) {
        ConfigTaskState t = new ConfigTaskState();
        t.setId(e.getId());
        t.setType(e.getType());
        t.setLabel(e.getLabel());
        t.setOperator(e.getOperator());
        t.setTargetCount(e.getTargetCount() != null ? e.getTargetCount() : 0);
        t.setStatus(e.getStatus());
        t.setProgress(e.getProgress() != null ? e.getProgress() : 0);
        t.setMessage(e.getMessage());
        t.setError(e.getError());
        t.setResult(fromJsonMap(e.getResultJson()));
        t.setRequest(fromJsonMap(e.getRequestJson()));
        t.setCancelRequested(Boolean.TRUE.equals(e.getCancelRequested()));
        t.setCreatedAt(e.getCreatedAt());
        t.setFinishedAt(e.getFinishedAt());
        return t;
    }

    private Map<String, Object> readRequest(ConfigTaskState task) {
        if (task.getRequest() != null) return new LinkedHashMap<>(task.getRequest());
        return new LinkedHashMap<>();
    }

    private void writeRequest(ConfigTaskState task, Map<String, Object> req) {
        task.setRequest(req);
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Long> toLongList(Object raw) {
        List<Long> ids = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return ids;
        for (Object id : list) {
            if (id instanceof Number n) ids.add(n.longValue());
            else if (id instanceof String s && !s.isBlank()) ids.add(Long.parseLong(s.trim()));
        }
        return ids;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void recordTaskAudit(ConfigTaskState task, String action) {
        if (task == null) return;
        String status = switch (task.getStatus() != null ? task.getStatus() : "") {
            case "SUCCESS" -> "success";
            case "PARTIAL", "PAUSED" -> "partial";
            case "FAILED", "CANCELLED" -> "failed";
            default -> "success";
        };
        String summary = task.getMessage() != null ? task.getMessage() : task.getLabel();
        Map<String, Object> result = task.getResult();
        if (result != null) {
            Object ok = result.get("successCount");
            Object total = result.get("totalCount");
            if (ok instanceof Number && total instanceof Number) {
                summary = (task.getLabel() != null ? task.getLabel() : action)
                        + "，成功 " + ok + "/" + total;
            }
        }
        auditLogService.record(AuditLogService.AuditRecord.builder()
                .module("config")
                .action(action)
                .operator(task.getOperator())
                .targetType("config_task")
                .targetId(task.getId())
                .targetName(task.getLabel())
                .status(status)
                .summary(summary)
                .refType("config_task")
                .refId(task.getId())
                .detail(task.getError())
                .build());
    }
}
