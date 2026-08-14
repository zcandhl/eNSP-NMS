package com.ensp.nms.service;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** 配置异步任务状态（内存实时 + DB 持久化视图） */
@Data
public class ConfigTaskState {
    private String id;
    private String type;
    private String label;
    private String operator;
    private int targetCount;
    private String status = "PENDING";
    private int progress;
    private String message = "排队中";
    private Map<String, Object> result;
    private Map<String, Object> request;
    private String error;
    private boolean cancelRequested;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime finishedAt;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("label", label != null ? label : type);
        m.put("operator", operator != null ? operator : "");
        m.put("targetCount", targetCount);
        m.put("status", status);
        m.put("progress", progress);
        m.put("message", message != null ? message : "");
        m.put("result", result);
        m.put("error", error);
        m.put("cancelRequested", cancelRequested);
        m.put("createdAt", createdAt != null ? createdAt.toString() : null);
        m.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
        if (result != null) {
            Object pending = result.get("pendingCount");
            if (pending != null) m.put("pendingCount", pending);
            Object pendingIds = result.get("pendingDeviceIds");
            if (pendingIds != null) m.put("pendingDeviceIds", pendingIds);
        }
        return m;
    }
}
