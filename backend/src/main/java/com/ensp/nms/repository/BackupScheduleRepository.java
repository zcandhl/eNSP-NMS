package com.ensp.nms.repository;

import com.ensp.nms.entity.BackupSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BackupScheduleRepository extends JpaRepository<BackupSchedule, Long> {
    List<BackupSchedule> findByIsActiveTrue();
    List<BackupSchedule> findByDeviceId(Long deviceId);
    List<BackupSchedule> findBySourceGroupId(Long sourceGroupId);

    /** bulk delete：立即执行 SQL，避免与父表 device 删除的 flush 顺序冲突 */
    @Modifying
    @Query("DELETE FROM BackupSchedule s WHERE s.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);
}
