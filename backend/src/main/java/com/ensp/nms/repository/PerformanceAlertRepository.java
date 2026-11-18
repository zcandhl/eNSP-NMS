package com.ensp.nms.repository;

import com.ensp.nms.entity.PerformanceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 删除某设备下的性能告警。
     * 用 bulk delete（@Modifying + 关联路径 a.device.id）而不是派生 deleteByDeviceId：
     * - 派生 delete 会先 select 再逐条 remove，删除动作滞留到事务提交前才 flush，
     *   与父表 device 的删除存在执行顺序/并发插入竞争（实测报
     *   FK ... `performance_alerts` FOREIGN KEY (`device_id`)）；
     * - 本实体虽有 deviceId 字段，但它与 @ManyToOne device 映射同一列且 insertable=false，
     *   走 a.device.id 才稳定命中。
     */
    @Modifying
    @Query("DELETE FROM PerformanceAlert a WHERE a.device.id = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);

    List<PerformanceAlert> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
}
