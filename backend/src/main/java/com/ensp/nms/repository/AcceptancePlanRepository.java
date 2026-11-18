package com.ensp.nms.repository;

import com.ensp.nms.entity.AcceptancePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AcceptancePlanRepository extends JpaRepository<AcceptancePlan, Long> {

    List<AcceptancePlan> findByProjectIdOrderByUpdatedAtDesc(Long projectId);
}
