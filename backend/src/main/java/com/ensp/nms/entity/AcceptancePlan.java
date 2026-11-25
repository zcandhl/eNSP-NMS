package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 验收计划实体，关联项目与检查项配置。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

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

    @Column(name = "report_no_rule", length = 60)
    private String reportNoRule = "YS-{date}-{batch}";

    @Column(length = 20)
    private String status = "draft";

    @Column(name = "checks_json", columnDefinition = "TEXT")
    private String checksJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
