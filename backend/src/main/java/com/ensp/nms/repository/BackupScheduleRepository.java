package com.ensp.nms.repository;

import com.ensp.nms.entity.BackupSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BackupScheduleRepository extends JpaRepository<BackupSchedule, Long> {
    List<BackupSchedule> findByIsActiveTrue();
    List<BackupSchedule> findByDeviceId(Long deviceId);
    List<BackupSchedule> findBySourceGroupId(Long sourceGroupId);

    void deleteByDeviceId(Long deviceId);
}
