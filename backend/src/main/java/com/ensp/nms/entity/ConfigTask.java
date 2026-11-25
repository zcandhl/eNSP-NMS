package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "config_task", indexes = {
        @Index(name = "idx_config_task_created", columnList = "created_at"),
        @Index(name = "idx_config_task_status", columnList = "status")
})

/**
 * 配置异步任务实体，记录恢复、下发与批量任务进度。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

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

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

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
