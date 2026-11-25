package com.ensp.nms.repository;

import com.ensp.nms.entity.AiopsUnattendedRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 无人值守执行记录数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

public interface AiopsUnattendedRunRepository extends JpaRepository<AiopsUnattendedRun, Long> {

    Page<AiopsUnattendedRun> findByOrderByStartedAtDesc(Pageable pageable);

    Page<AiopsUnattendedRun> findByPlanSourceOrderByStartedAtDesc(String planSource, Pageable pageable);

    Page<AiopsUnattendedRun> findByAlarmIdOrderByStartedAtDesc(Long alarmId, Pageable pageable);

    List<AiopsUnattendedRun> findTop20ByOrderByStartedAtDesc();

    long countByStartedAtAfter(LocalDateTime after);

    long countByStartedAtAfterAndPlanSource(LocalDateTime after, String planSource);

    long countByStartedAtAfterAndStatus(LocalDateTime after, String status);

    @Modifying
    @Query("DELETE FROM AiopsUnattendedRun r WHERE r.startedAt < :cutoff")
    int deleteByStartedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("UPDATE AiopsUnattendedRun r SET r.deviceId = NULL WHERE r.deviceId = :deviceId")
    int detachDevice(@Param("deviceId") Long deviceId);
}
