package com.ensp.nms.repository;

import com.ensp.nms.entity.PerformanceData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PerformanceDataRepository extends JpaRepository<PerformanceData, Long> {

    @Query("SELECT p FROM PerformanceData p WHERE p.device.id = :deviceId ORDER BY p.timestamp DESC")
    List<PerformanceData> findByDeviceIdOrderByTimestampDesc(@Param("deviceId") Long deviceId);

    @Modifying
    @Query("DELETE FROM PerformanceData p WHERE p.device.id = :deviceId")
    void deleteByDeviceId(@Param("deviceId") Long deviceId);

    /** 删除早于指定时间的性能数据（保留策略） */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PerformanceData p WHERE p.timestamp < :before")
    int deleteByTimestampBefore(@Param("before") LocalDateTime before);

    @Query("SELECT p FROM PerformanceData p WHERE p.device.id = :deviceId AND p.timestamp > :after ORDER BY p.timestamp ASC")
    List<PerformanceData> findByDeviceIdAndTimestampAfterOrderByTimestampAsc(
            @Param("deviceId") Long deviceId,
            @Param("after") LocalDateTime after
    );

    @Query(value = "SELECT * FROM performance_data WHERE device_id = :deviceId ORDER BY timestamp DESC LIMIT 1", nativeQuery = true)
    Optional<PerformanceData> findTopByDeviceIdOrderByTimestampDesc(@Param("deviceId") Long deviceId);

    @Query("SELECT p FROM PerformanceData p WHERE p.device.id = :deviceId " +
           "AND p.timestamp BETWEEN :start AND :end " +
           "AND p.portIndex IS NULL " +
           "ORDER BY p.timestamp ASC")
    List<PerformanceData> findByDeviceIdAndTimestampBetween(
            @Param("deviceId") Long deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable
    );

    @Query("SELECT p FROM PerformanceData p WHERE p.device.id = :deviceId " +
           "AND p.timestamp BETWEEN :start AND :end " +
           "AND p.portIndex IS NULL " +
           "ORDER BY p.timestamp ASC")
    List<PerformanceData> findByDeviceIdAndTimestampBetween(
            @Param("deviceId") Long deviceId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Query(value = "SELECT * FROM performance_data " +
           "WHERE device_id = :deviceId AND port_index IS NULL " +
           "ORDER BY timestamp DESC LIMIT 1", nativeQuery = true)
    List<PerformanceData> findDeviceMetricsByDeviceId(@Param("deviceId") Long deviceId);

    @Query(value = "SELECT p.* FROM performance_data p " +
           "INNER JOIN (" +
           "  SELECT port_index, MAX(timestamp) as max_ts " +
           "  FROM performance_data " +
           "  WHERE device_id = :deviceId AND port_index IS NOT NULL " +
           "  GROUP BY port_index" +
           ") t ON p.port_index = t.port_index AND p.timestamp = t.max_ts " +
           "WHERE p.device_id = :deviceId", nativeQuery = true)
    List<PerformanceData> findLatestPortMetricsByDeviceId(@Param("deviceId") Long deviceId);
}
