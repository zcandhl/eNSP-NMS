package com.ensp.nms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验收批次实体，记录一次验收执行的状态与结果。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Data
@Entity
@Table(name = "acceptance_batch")
public class AcceptanceBatch {

    @Id
    @Column(name = "batch_id", length = 30)
    private String batchId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(nullable = false, length = 40)
    private String no;

    @Column(nullable = false, length = 20)
    private String status = "RUNNING";

    @Column(name = "status_message", columnDefinition = "TEXT")
    private String statusMessage;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;
}
