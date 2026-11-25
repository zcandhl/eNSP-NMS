package com.ensp.nms.repository;

import com.ensp.nms.entity.AcceptancePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 验收计划数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-11-22 */

@Repository
public interface AcceptancePlanRepository extends JpaRepository<AcceptancePlan, Long> {

    List<AcceptancePlan> findByProjectIdOrderByUpdatedAtDesc(Long projectId);
}
