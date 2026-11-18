package com.ensp.nms.repository;

import com.ensp.nms.entity.ConfigChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long>,
        JpaSpecificationExecutor<ConfigChangeLog> {

    List<ConfigChangeLog> findByDeviceIdOrderByCreatedAtDesc(Long deviceId);

    List<ConfigChangeLog> findAllByOrderByCreatedAtDesc();

    Page<ConfigChangeLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** bulk delete：立即执行 SQL，避免与父表 device 删除的 flush 顺序冲突 */
    @Modifying
    @Query("DELETE FROM ConfigChangeLog l WHERE l.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);
}
