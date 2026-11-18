package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 验收方案：验收什么、判定准则是什么，全部以数据形式存这里。
 * 换一个项目改一份方案即可，验收引擎（代码）不变。
 * checks 结构：[{id, seq, code, title, type, owner, severity, enabled,
 *               allowFinding, needConfirm, params:{...}, assertExpr}]
 */
@Data
@Entity
@Table(name = "acceptance_plan")
public class AcceptancePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String version = "1.0";

    /** 报告编号规则，如 YS-{date}-{batch} */
    @Column(name = "report_no_rule", length = 60)
    private String reportNoRule = "YS-{date}-{batch}";

    /** draft | active | archived */
    @Column(length = 20)
    private String status = "draft";

    /** 检查项列表（JSON 数组文本） */
    @Column(name = "checks_json", columnDefinition = "TEXT")
    private String checksJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
