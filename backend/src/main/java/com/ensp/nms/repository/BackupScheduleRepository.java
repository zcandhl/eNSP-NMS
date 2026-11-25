package com.ensp.nms.repository;

import com.ensp.nms.entity.BackupSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 备份计划数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Repository
public interface BackupScheduleRepository extends JpaRepository<BackupSchedule, Long> {
    List<BackupSchedule> findByIsActiveTrue();
    List<BackupSchedule> findByDeviceId(Long deviceId);
    List<BackupSchedule> findBySourceGroupId(Long sourceGroupId);

    @Modifying
    @Query("DELETE FROM BackupSchedule s WHERE s.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);
}
