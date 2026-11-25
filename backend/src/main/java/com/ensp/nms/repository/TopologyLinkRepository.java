package com.ensp.nms.repository;

import com.ensp.nms.entity.TopologyLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 拓扑链路数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Repository
public interface TopologyLinkRepository extends JpaRepository<TopologyLink, Long> {
    List<TopologyLink> findBySourceNodeIdOrTargetNodeId(Long sourceNodeId, Long targetNodeId);
    List<TopologyLink> findBySourceNodeId(Long sourceNodeId);
    List<TopologyLink> findByTargetNodeId(Long targetNodeId);
    Optional<TopologyLink> findBySourceNodeIdAndTargetNodeId(Long sourceNodeId, Long targetNodeId);

    @Modifying
    @Query("DELETE FROM TopologyLink l WHERE l.sourceNodeId = :deviceId OR l.targetNodeId = :deviceId")
    int deleteAllByEndpoint(@Param("deviceId") Long deviceId);

    @Query("SELECT l FROM TopologyLink l WHERE (l.sourceNodeId = :a AND l.targetNodeId = :b) " +
            "OR (l.sourceNodeId = :b AND l.targetNodeId = :a)")
    Optional<TopologyLink> findBetweenDevices(@Param("a") Long deviceA, @Param("b") Long deviceB);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TopologyLink l WHERE l.bandwidth = '1Gbps' AND (" +
            "l.sourcePort IS NULL OR l.sourcePort = '' OR l.sourcePort = 'unknown' " +
            "OR l.sourcePort LIKE 'Port-%')")
    int deleteSyntheticLinks();
}

