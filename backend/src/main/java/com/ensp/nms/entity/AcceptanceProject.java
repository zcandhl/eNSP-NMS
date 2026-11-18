package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 验收工程/项目维度：一个工程一份网络，设备和验收方案都挂在它下面。
 * 有了它，"换一栋楼"才是新建一个工程，而不是清空数据库。
 */
@Data
@Entity
@Table(name = "acceptance_project")
public class AcceptanceProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 建筑与规模描述（如：综合实训大楼（6 层 / 约 3.5 万㎡）） */
    @Column(length = 200)
    private String building;

    /** 建设单位（业主/验收发起方） */
    @Column(length = 100)
    private String owner;

    /** 承建单位 */
    @Column(length = 100)
    private String contractor;

    /** 验收单位 */
    @Column(name = "acceptance_org", length = 100)
    private String acceptanceOrg;

    /** 纳管设备数（用于报告"工程概况"） */
    @Column(name = "device_count")
    private Integer deviceCount = 0;

    @Column(columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
