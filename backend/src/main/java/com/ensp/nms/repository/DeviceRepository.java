package com.ensp.nms.repository;

import com.ensp.nms.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 设备台账数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long>, JpaSpecificationExecutor<Device> {

    Optional<Device> findByIpAddress(String ipAddress);

    Optional<Device> findFirstByNameIgnoreCase(String name);

    List<Device> findByNameContainingIgnoreCase(String name);

    List<Device> findByStatus(String status);

    List<Device> findByDeviceType(String deviceType);

    List<Device> findByGroupId(Long groupId);

    List<Device> findByProjectId(Long projectId);

    long countByProjectId(Long projectId);

    boolean existsByIpAddress(String ipAddress);
}
