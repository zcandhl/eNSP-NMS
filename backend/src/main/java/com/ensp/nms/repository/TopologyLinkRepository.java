package com.ensp.nms.repository;

import com.ensp.nms.entity.TopologyLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** 删除与该设备相关的所有链路（两端任一命中）：bulk delete，立即执行 SQL */
    @Modifying
    @Query("DELETE FROM TopologyLink l WHERE l.sourceNodeId = :deviceId OR l.targetNodeId = :deviceId")
    int deleteAllByEndpoint(@Param("deviceId") Long deviceId);

    @Query("SELECT l FROM TopologyLink l WHERE (l.sourceNodeId = :a AND l.targetNodeId = :b) " +
            "OR (l.sourceNodeId = :b AND l.targetNodeId = :a)")
    Optional<TopologyLink> findBetweenDevices(@Param("a") Long deviceA, @Param("b") Long deviceB);

    /**
     * 删除 ARP/MAC 兜底生成的「合成链路」：
     * 特征是端口为空 / unknown / Port-<ifIndex>，且带宽被硬编码成 1Gbps。
     * LLDP 建链不会写带宽，手工建链的端口是真实接口名，因此不会误删。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM TopologyLink l WHERE l.bandwidth = '1Gbps' AND (" +
            "l.sourcePort IS NULL OR l.sourcePort = '' OR l.sourcePort = 'unknown' " +
            "OR l.sourcePort LIKE 'Port-%')")
    int deleteSyntheticLinks();
}

