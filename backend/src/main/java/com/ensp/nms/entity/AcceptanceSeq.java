package com.ensp.nms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 验收序号计数器：批次号 / 报告编号的持久化自增源。
 *
 * 为什么不用 count()+1：批次会随方案、工程级联删除，count 会回退，
 * 导致批次号主键冲突、报告编号重号。计数器只增不减，删除历史批次也不会回收号段。
 */
@Data
@Entity
@Table(name = "acceptance_seq")
public class AcceptanceSeq {

    /** 号段作用域，如 acceptance.batch */
    @Id
    @Column(name = "scope", length = 60)
    private String scope;

    @Column(name = "seq_value", nullable = false)
    private long value;
}
