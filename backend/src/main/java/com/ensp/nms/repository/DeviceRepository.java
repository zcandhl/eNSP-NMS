package com.ensp.nms.repository;

import com.ensp.nms.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long>, JpaSpecificationExecutor<Device> {

    Optional<Device> findByIpAddress(String ipAddress);

    Optional<Device> findFirstByNameIgnoreCase(String name);

    List<Device> findByNameContainingIgnoreCase(String name);

    List<Device> findByStatus(String status);

    List<Device> findByGroupId(Long groupId);

    boolean existsByIpAddress(String ipAddress);
}
