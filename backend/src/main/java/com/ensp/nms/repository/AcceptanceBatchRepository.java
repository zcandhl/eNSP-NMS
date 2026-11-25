package com.ensp.nms.repository;

import com.ensp.nms.entity.AcceptanceBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 验收批次数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Repository
public interface AcceptanceBatchRepository extends JpaRepository<AcceptanceBatch, String> {

    List<AcceptanceBatch> findByPlanIdOrderByStartedAtDesc(Long planId);

    List<AcceptanceBatch> findByStatus(String status);

    @Query("select max(b.batchId) from AcceptanceBatch b")
    String findMaxBatchId();

    void deleteByPlanId(Long planId);
}
