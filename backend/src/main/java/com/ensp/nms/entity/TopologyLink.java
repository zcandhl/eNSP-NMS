package com.ensp.nms.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 拓扑链路实体，记录两端节点与连接类型。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@Data
@Entity
@Table(name = "topology_link")
public class TopologyLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_node_id", nullable = false)
    private Long sourceNodeId;

    @Column(name = "target_node_id", nullable = false)
    private Long targetNodeId;

    @Column(name = "source_port", length = 50)
    private String sourcePort;

    @Column(name = "target_port", length = 50)
    private String targetPort;

    @Column(length = 20)
    private String status = "up";

    @Column(length = 20)
    private String bandwidth;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

