package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_operator", columnList = "operator"),
        @Index(name = "idx_audit_module", columnList = "module"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_target_id", columnList = "target_id")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String operator;

    @Column(nullable = false, length = 30)
    private String module;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id", length = 100)
    private String targetId;

    @Column(name = "target_name", length = 200)
    private String targetName;

    @Column(nullable = false, length = 20)
    private String status = "success";

    @Column(length = 500)
    private String summary;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "ref_type", length = 50)
    private String refType;

    @Column(name = "ref_id", length = 100)
    private String refId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
