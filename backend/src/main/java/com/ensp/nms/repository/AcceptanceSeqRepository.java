package com.ensp.nms.repository;

import com.ensp.nms.entity.AcceptanceSeq;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 验收单号序列数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Repository
public interface AcceptanceSeqRepository extends JpaRepository<AcceptanceSeq, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AcceptanceSeq s where s.scope = :scope")
    Optional<AcceptanceSeq> lockByScope(@Param("scope") String scope);
}
