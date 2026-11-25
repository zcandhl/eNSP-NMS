package com.ensp.nms.repository;

import com.ensp.nms.entity.Alarm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Repository
public interface AlarmRepository extends JpaRepository<Alarm, Long>, JpaSpecificationExecutor<Alarm> {

    List<Alarm> findByStatusOrderByOccurredAtDesc(Alarm.Status status);

    Page<Alarm> findByStatusOrderByOccurredAtDesc(Alarm.Status status, Pageable pageable);

    List<Alarm> findByStatusInOrderByOccurredAtDesc(List<Alarm.Status> statuses);

    List<Alarm> findByParentAlarmId(Long parentAlarmId);

    long countByStatusInAndOccurredAtAfter(List<Alarm.Status> statuses, LocalDateTime after);

    List<Alarm> findByDeviceIpOrderByOccurredAtDesc(String deviceIp);

    @Query("SELECT a FROM Alarm a WHERE a.device.id = :deviceId " +
            "OR (a.deviceId IS NULL AND a.deviceIp = :deviceIp) ORDER BY a.occurredAt DESC")
    List<Alarm> findByDeviceIdOrDeviceIpOrderByOccurredAtDesc(
            @Param("deviceId") Long deviceId,
            @Param("deviceIp") String deviceIp);

    List<Alarm> findBySeverityOrderByOccurredAtDesc(Alarm.Severity severity);

    Page<Alarm> findBySeverityOrderByOccurredAtDesc(Alarm.Severity severity, Pageable pageable);

    List<Alarm> findByStatusAndSeverityOrderByOccurredAtDesc(Alarm.Status status, Alarm.Severity severity);

    Page<Alarm> findByStatusAndSeverityOrderByOccurredAtDesc(Alarm.Status status, Alarm.Severity severity, Pageable pageable);

    List<Alarm> findByOccurredAtAfterOrderByOccurredAtDesc(LocalDateTime after);

    long countByStatus(Alarm.Status status);

    long countByStatusIn(List<Alarm.Status> statuses);

    long countBySeverity(Alarm.Severity severity);

    long countByStatusAndSeverity(Alarm.Status status, Alarm.Severity severity);

    long countByStatusAndOccurredAtBefore(Alarm.Status status, LocalDateTime occurredAt);

    List<Alarm> findByOccurredAtBetweenOrderByOccurredAtDesc(LocalDateTime start, LocalDateTime end);

    @Query("SELECT COUNT(a) FROM Alarm a WHERE a.occurredAt BETWEEN :start AND :end")
    long countByOccurredAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT a.trapType, COUNT(a) FROM Alarm a GROUP BY a.trapType")
    List<Object[]> countByTrapType();

    @Query("SELECT a.deviceIp, COUNT(a) FROM Alarm a WHERE a.status = :status GROUP BY a.deviceIp ORDER BY COUNT(a) DESC")
    List<Object[]> countByDeviceIpGroupByStatus(@Param("status") Alarm.Status status);

    @Query("SELECT a.device.id, COUNT(a) FROM Alarm a WHERE a.status IN :statuses AND a.device IS NOT NULL GROUP BY a.device.id")
    List<Object[]> countActiveAlarmsGroupByDeviceId(@Param("statuses") List<Alarm.Status> statuses);

    @Query("SELECT a.device.id, COUNT(a) FROM Alarm a WHERE a.status IN :statuses AND a.device IS NOT NULL " +
            "AND a.severity IN ('CRITICAL', 'MAJOR') GROUP BY a.device.id")
    List<Object[]> countSevereActiveAlarmsGroupByDeviceId(@Param("statuses") List<Alarm.Status> statuses);

    @Query("SELECT a.deviceIp, COUNT(a) FROM Alarm a WHERE a.status IN :statuses AND a.device IS NULL " +
            "AND a.deviceIp IS NOT NULL GROUP BY a.deviceIp")
    List<Object[]> countActiveAlarmsGroupByDeviceIp(@Param("statuses") List<Alarm.Status> statuses);

    @Query("SELECT a.deviceIp, COUNT(a) FROM Alarm a WHERE a.status IN :statuses AND a.device IS NULL " +
            "AND a.deviceIp IS NOT NULL AND a.severity IN ('CRITICAL', 'MAJOR') GROUP BY a.deviceIp")
    List<Object[]> countSevereActiveAlarmsGroupByDeviceIp(@Param("statuses") List<Alarm.Status> statuses);

    @Query("SELECT a.status, COUNT(a) FROM Alarm a GROUP BY a.status")
    List<Object[]> countGroupByStatus();

    @Query("SELECT a.severity, COUNT(a) FROM Alarm a GROUP BY a.severity")
    List<Object[]> countGroupBySeverity();

    @Query("SELECT a.severity, COUNT(a) FROM Alarm a WHERE a.status = :status GROUP BY a.severity")
    List<Object[]> countByStatusGroupBySeverity(@Param("status") Alarm.Status status);

    @Query("SELECT a.severity, COUNT(a) FROM Alarm a WHERE a.status IN :statuses GROUP BY a.severity")
    List<Object[]> countGroupBySeverityInStatus(@Param("statuses") List<Alarm.Status> statuses);

    @Query("SELECT a.trapType, COUNT(a) FROM Alarm a WHERE a.occurredAt >= :since AND a.trapType IS NOT NULL " +
            "GROUP BY a.trapType ORDER BY COUNT(a) DESC")
    List<Object[]> countTrapTypeSince(@Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT YEAR(a.occurredAt), MONTH(a.occurredAt), DAY(a.occurredAt), HOUR(a.occurredAt), COUNT(a) " +
            "FROM Alarm a WHERE a.occurredAt >= :start " +
            "GROUP BY YEAR(a.occurredAt), MONTH(a.occurredAt), DAY(a.occurredAt), HOUR(a.occurredAt) " +
            "ORDER BY YEAR(a.occurredAt), MONTH(a.occurredAt), DAY(a.occurredAt), HOUR(a.occurredAt)")
    List<Object[]> countGroupByHourSince(@Param("start") LocalDateTime start);

    Page<Alarm> findAllByOrderByOccurredAtDesc(Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Alarm a SET a.device = null WHERE a.device.id = :deviceId")
    int clearDeviceReference(@Param("deviceId") Long deviceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Alarm a WHERE a.device.id = :deviceId")
    int deleteByDeviceIdHard(@Param("deviceId") Long deviceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Alarm a WHERE a.device IS NULL AND a.deviceIp = :deviceIp")
    int deleteOrphanByDeviceIp(@Param("deviceIp") String deviceIp);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM alarm WHERE device_id IS NULL AND device_ip IS NOT NULL " +
            "AND device_ip NOT IN (SELECT ip_address FROM device)", nativeQuery = true)
    int deleteOrphanAlarms();

    @Query("SELECT a FROM Alarm a WHERE a.device.id = :deviceId AND a.trapType = 'PERFORMANCE' " +
           "AND a.rawData = :metric AND a.status IN :statuses ORDER BY a.occurredAt DESC")
    List<Alarm> findPerformanceAlarms(
            @Param("deviceId") Long deviceId,
            @Param("metric") String metric,
            @Param("statuses") List<Alarm.Status> statuses);

    @Query("SELECT a FROM Alarm a WHERE a.status IN :statuses " +
           "AND COALESCE(a.lastOccurredAt, a.occurredAt) >= :since " +
           "AND ((:deviceIp IS NULL AND a.deviceIp IS NULL) OR a.deviceIp = :deviceIp) " +
           "AND ((:trapType IS NULL AND a.trapType IS NULL) OR a.trapType = :trapType) " +
           "AND a.title = :title " +
           "ORDER BY COALESCE(a.lastOccurredAt, a.occurredAt) DESC")
    List<Alarm> findSimilarRecent(
            @Param("deviceIp") String deviceIp,
            @Param("trapType") String trapType,
            @Param("title") String title,
            @Param("statuses") List<Alarm.Status> statuses,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Alarm a WHERE a.status = :status AND a.clearedAt IS NOT NULL AND a.clearedAt < :before")
    int deleteClearedBefore(@Param("status") Alarm.Status status, @Param("before") LocalDateTime before);

    @Query("SELECT a FROM Alarm a WHERE a.status = :status "
            + "AND a.parentAlarmId IS NULL AND a.occurredAt >= :since "
            + "ORDER BY a.occurredAt DESC")
    List<Alarm> findRecentClearedRoots(
            @Param("status") Alarm.Status status,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Alarm a WHERE a.occurredAt < :before")
    int deleteOccurredBefore(@Param("before") LocalDateTime before);
}
