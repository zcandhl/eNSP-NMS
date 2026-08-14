package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "device_port")
public class DevicePort {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "port_name", nullable = false, length = 50)
    private String portName;

    @Column(name = "port_type", length = 50)
    private String portType;

    @Column(name = "if_index")
    private Integer ifIndex;

    @Column(name = "admin_status", length = 20)
    private String adminStatus;

    @Column(name = "oper_status", length = 20)
    private String operStatus;

    private Long speed;

    private Integer mtu;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

