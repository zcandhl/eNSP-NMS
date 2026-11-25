package com.ensp.nms.repository;

import com.ensp.nms.entity.AiopsUnattendedStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 无人值守步骤数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

public interface AiopsUnattendedStepRepository extends JpaRepository<AiopsUnattendedStep, Long> {

    List<AiopsUnattendedStep> findByRunIdOrderBySeqAsc(Long runId);

    @Modifying
    @Query("DELETE FROM AiopsUnattendedStep s WHERE s.runId IN :runIds")
    int deleteByRunIdIn(@Param("runIds") List<Long> runIds);
}
