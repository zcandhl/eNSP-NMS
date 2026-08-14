package com.ensp.nms.repository;

import com.ensp.nms.entity.DeviceIpAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceIpAliasRepository extends JpaRepository<DeviceIpAlias, Long> {

    Optional<DeviceIpAlias> findByIpAddress(String ipAddress);

    List<DeviceIpAlias> findByDeviceId(Long deviceId);

    void deleteByDeviceId(Long deviceId);
}
