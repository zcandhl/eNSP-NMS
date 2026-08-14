package com.ensp.nms.repository;

import com.ensp.nms.entity.DevicePort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DevicePortRepository extends JpaRepository<DevicePort, Long> {
    List<DevicePort> findByDeviceId(Long deviceId);

    void deleteByDeviceId(Long deviceId);
}

