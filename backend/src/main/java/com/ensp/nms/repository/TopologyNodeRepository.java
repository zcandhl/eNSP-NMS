package com.ensp.nms.repository;

import com.ensp.nms.entity.TopologyNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TopologyNodeRepository extends JpaRepository<TopologyNode, Long> {
    Optional<TopologyNode> findByDeviceId(Long deviceId);

    java.util.List<TopologyNode> findAllByDeviceId(Long deviceId);
}

