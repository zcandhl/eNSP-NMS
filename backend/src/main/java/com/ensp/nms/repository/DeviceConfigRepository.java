package com.ensp.nms.repository;

import com.ensp.nms.dto.DeviceBackupLatestView;
import com.ensp.nms.dto.DeviceBackupStatsView;
import com.ensp.nms.entity.DeviceConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceConfigRepository extends JpaRepository<DeviceConfig, Long> {

    List<DeviceConfig> findByDeviceId(Long deviceId);

    List<DeviceConfig> findByDeviceIdOrderByCreatedAtDesc(Long deviceId);

    Page<DeviceConfig> findByDeviceIdOrderByCreatedAtDesc(Long deviceId, Pageable pageable);

    @Query("select distinct c.deviceId from DeviceConfig c")
    List<Long> findDistinctDeviceIds();

    /** 每设备备份份数与最近时间（不加载 content） */
    @Query("""
            select c.deviceId as deviceId, count(c) as backupCount, max(c.createdAt) as lastBackupAt
            from DeviceConfig c
            group by c.deviceId
            """)
    List<DeviceBackupStatsView> aggregateBackupStats();

    /** 每设备最新备份元数据（不加载 content） */
    @Query("""
            select c.deviceId as deviceId, c.configType as configType,
                   c.configVersion as configVersion, c.createdAt as createdAt
            from DeviceConfig c
            where c.createdAt = (
                select max(c2.createdAt) from DeviceConfig c2 where c2.deviceId = c.deviceId
            )
            """)
    List<DeviceBackupLatestView> findLatestMetaPerDevice();

    void deleteByDeviceId(Long deviceId);
}
