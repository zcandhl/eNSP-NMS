package com.ensp.nms.repository;

import com.ensp.nms.entity.TopologyNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TopologyNodeRepository extends JpaRepository<TopologyNode, Long> {
    Optional<TopologyNode> findByDeviceId(Long deviceId);

    java.util.List<TopologyNode> findAllByDeviceId(Long deviceId);

    /** bulk delete：立即执行 SQL，避免与父表 device 删除的 flush 顺序冲突 */
    @Modifying
    @Query("DELETE FROM TopologyNode n WHERE n.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);
}

