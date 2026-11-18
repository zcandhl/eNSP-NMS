package com.ensp.nms.repository;

import com.ensp.nms.entity.AcceptanceBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AcceptanceBatchRepository extends JpaRepository<AcceptanceBatch, String> {

    List<AcceptanceBatch> findByPlanIdOrderByStartedAtDesc(Long planId);

    /** 启动回收用：找出所有仍标记为执行中的批次（重启后必然是僵尸） */
    List<AcceptanceBatch> findByStatus(String status);

    /** 首次启用序号表时打底：历史批次里的最大批次号 */
    @Query("select max(b.batchId) from AcceptanceBatch b")
    String findMaxBatchId();

    /** 按方案清空历史批次（删除方案/工程时级联调用，需在外层事务中执行） */
    void deleteByPlanId(Long planId);
}
