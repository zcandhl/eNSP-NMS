package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 配置异步任务持久化：重启后可查历史，内存态仍用于实时进度。
 */
@Data
@Entity
@Table(name = "config_task", indexes = {
        @Index(name = "idx_config_task_created", columnList = "created_at"),
        @Index(name = "idx_config_task_status", columnList = "status")
})
public class ConfigTask {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 255)
    private String label;

    @Column(length = 100)
    private String operator;

    @Column(name = "target_count")
    private Integer targetCount = 0;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    private Integer progress = 0;

    @Column(length = 500)
    private String message;

    @Column(columnDefinition = "TEXT")
    private String error;

    /** 结果 JSON */
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    /** 请求快照，供分波继续 / 失败重试 */
    @Column(name = "request_json", columnDefinition = "LONGTEXT")
    private String requestJson;

    @Column(name = "cancel_requested", nullable = false)
    private Boolean cancelRequested = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
