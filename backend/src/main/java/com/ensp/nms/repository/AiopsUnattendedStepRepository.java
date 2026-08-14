package com.ensp.nms.repository;

import com.ensp.nms.entity.AiopsUnattendedStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiopsUnattendedStepRepository extends JpaRepository<AiopsUnattendedStep, Long> {

    List<AiopsUnattendedStep> findByRunIdOrderBySeqAsc(Long runId);

    @Modifying
    @Query("DELETE FROM AiopsUnattendedStep s WHERE s.runId IN :runIds")
    int deleteByRunIdIn(@Param("runIds") List<Long> runIds);
}
