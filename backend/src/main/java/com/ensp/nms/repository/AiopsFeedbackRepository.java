package com.ensp.nms.repository;

import com.ensp.nms.entity.AiopsFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiopsFeedbackRepository extends JpaRepository<AiopsFeedback, Long> {
    List<AiopsFeedback> findTop50ByOrderByCreatedAtDesc();

    List<AiopsFeedback> findByTargetTypeOrderByCreatedAtDesc(String targetType);

    @Query("select f.targetId, sum(case when f.useful = true then 1 else 0 end), "
            + "sum(case when f.useful = false then 1 else 0 end) "
            + "from AiopsFeedback f where f.targetType = :targetType group by f.targetId")
    List<Object[]> aggregateByTargetType(@Param("targetType") String targetType);
}
