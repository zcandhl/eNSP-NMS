package com.ensp.nms.repository;

import com.ensp.nms.entity.PerformanceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PerformanceAlertRepository extends JpaRepository<PerformanceAlert, Long> {

    List<PerformanceAlert> findByDeviceIdAndStatusOrderByCreatedAtDesc(Long deviceId, String status);
    
    List<PerformanceAlert> findByDeviceIdAndStatusInOrderByCreatedAtDesc(Long deviceId, List<String> statuses);

    List<PerformanceAlert> findByStatusOrderByCreatedAtDesc(String status);

    @Query("SELECT a FROM PerformanceAlert a WHERE a.deviceId = :deviceId AND a.metric = :metric AND a.status IN ('active', 'acknowledged') ORDER BY a.createdAt DESC")
    List<PerformanceAlert> findLatestActiveAlerts(Long deviceId, String metric);

    List<PerformanceAlert> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    void deleteByDeviceId(Long deviceId);

    List<PerformanceAlert> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
}
