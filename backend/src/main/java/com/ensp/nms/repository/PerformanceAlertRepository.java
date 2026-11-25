package com.ensp.nms.repository;

import com.ensp.nms.entity.PerformanceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 性能告警数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Repository
public interface PerformanceAlertRepository extends JpaRepository<PerformanceAlert, Long> {

    List<PerformanceAlert> findByDeviceIdAndStatusOrderByCreatedAtDesc(Long deviceId, String status);
    
    List<PerformanceAlert> findByDeviceIdAndStatusInOrderByCreatedAtDesc(Long deviceId, List<String> statuses);

    List<PerformanceAlert> findByStatusOrderByCreatedAtDesc(String status);

    @Query("SELECT a FROM PerformanceAlert a WHERE a.deviceId = :deviceId AND a.metric = :metric AND a.status IN ('active', 'acknowledged') ORDER BY a.createdAt DESC")
    List<PerformanceAlert> findLatestActiveAlerts(Long deviceId, String metric);

    List<PerformanceAlert> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    @Modifying
    @Query("DELETE FROM PerformanceAlert a WHERE a.device.id = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);

    List<PerformanceAlert> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
}
