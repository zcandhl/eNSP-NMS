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
}
