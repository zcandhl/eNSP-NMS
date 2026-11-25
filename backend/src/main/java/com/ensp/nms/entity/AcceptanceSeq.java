package com.ensp.nms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 验收单号序列实体，用于生成批次编号。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Data
@Entity
@Table(name = "acceptance_seq")
public class AcceptanceSeq {

    @Id
    @Column(name = "scope", length = 60)
    private String scope;

    @Column(name = "seq_value", nullable = false)
    private long value;
}
