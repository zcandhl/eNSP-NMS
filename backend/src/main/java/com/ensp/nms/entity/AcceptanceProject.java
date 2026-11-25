package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 验收项目实体，描述待验收的工程对象。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Data
@Entity
@Table(name = "acceptance_project")
public class AcceptanceProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String building;

    @Column(length = 100)
    private String owner;

    @Column(length = 100)
    private String contractor;

    @Column(name = "acceptance_org", length = 100)
    private String acceptanceOrg;

    @Column(name = "device_count")
    private Integer deviceCount = 0;

    @Column(columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
