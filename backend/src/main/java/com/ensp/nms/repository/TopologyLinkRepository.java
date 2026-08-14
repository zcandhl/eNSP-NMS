package com.ensp.nms.repository;

import com.ensp.nms.entity.TopologyLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TopologyLinkRepository extends JpaRepository<TopologyLink, Long> {
    List<TopologyLink> findBySourceNodeIdOrTargetNodeId(Long sourceNodeId, Long targetNodeId);
    List<TopologyLink> findBySourceNodeId(Long sourceNodeId);
    List<TopologyLink> findByTargetNodeId(Long targetNodeId);
    Optional<TopologyLink> findBySourceNodeIdAndTargetNodeId(Long sourceNodeId, Long targetNodeId);

    @Query("SELECT l FROM TopologyLink l WHERE (l.sourceNodeId = :a AND l.targetNodeId = :b) " +
            "OR (l.sourceNodeId = :b AND l.targetNodeId = :a)")
    Optional<TopologyLink> findBetweenDevices(@Param("a") Long deviceA, @Param("b") Long deviceB);
}

