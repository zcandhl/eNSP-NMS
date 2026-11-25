package com.ensp.nms.repository;

import com.ensp.nms.dto.DeviceBackupLatestView;
import com.ensp.nms.dto.DeviceBackupStatsView;
import com.ensp.nms.entity.DeviceConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备配置备份数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Repository
public interface DeviceConfigRepository extends JpaRepository<DeviceConfig, Long> {

    List<DeviceConfig> findByDeviceId(Long deviceId);

    List<DeviceConfig> findByDeviceIdOrderByCreatedAtDesc(Long deviceId);

    Page<DeviceConfig> findByDeviceIdOrderByCreatedAtDesc(Long deviceId, Pageable pageable);

    @Query("select distinct c.deviceId from DeviceConfig c")
    List<Long> findDistinctDeviceIds();

    @Query("""
            select c.deviceId as deviceId, count(c) as backupCount, max(c.createdAt) as lastBackupAt
            from DeviceConfig c
            group by c.deviceId
            """)
    List<DeviceBackupStatsView> aggregateBackupStats();

    @Query("""
            select c.deviceId as deviceId, c.configType as configType,
                   c.configVersion as configVersion, c.createdAt as createdAt
            from DeviceConfig c
            where c.createdAt = (
                select max(c2.createdAt) from DeviceConfig c2 where c2.deviceId = c.deviceId
            )
            """)
    List<DeviceBackupLatestView> findLatestMetaPerDevice();

    @Modifying
    @Query("DELETE FROM DeviceConfig c WHERE c.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);
}
