package com.ensp.nms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验收批次：一次运行 = 一个批次号 + 一份冻结快照。
 *
 * 快照冻结纪律：itemsJson 在每项跑完后立即写入，报告只读快照、绝不重跑——
 * 同一批次号必须永远出同一份报告。
 */
@Data
@Entity
@Table(name = "acceptance_batch")
public class AcceptanceBatch {

    /** 批次号，如 B-001（主键用业务号，便于追溯） */
    @Id
    @Column(name = "batch_id", length = 30)
    private String batchId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 报告编号，如 YS-20261109-001 */
    @Column(nullable = false, length = 40)
    private String no;

    /**
     * RUNNING 执行中 | DONE 已完成 | FAILED 执行失败 | INTERRUPTED 中断（重启/人工终止）| VOIDED 已作废
     *
     * 终态语义：DONE 批次不可删只可作废（保留验收追溯）；INTERRUPTED/FAILED 可删。
     */
    @Column(nullable = false, length = 20)
    private String status = "RUNNING";

    /** 状态说明：失败原因、中断原因、等待人工确认的项 */
    @Column(name = "status_message", columnDefinition = "TEXT")
    private String statusMessage;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 批次快照：逐项状态/指标/原始输出/问题清单（JSON） */
    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;
}
