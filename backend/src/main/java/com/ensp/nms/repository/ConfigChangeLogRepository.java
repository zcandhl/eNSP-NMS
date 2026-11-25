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

/**
 * 配置变更日志数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Repository
public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long>,
        JpaSpecificationExecutor<ConfigChangeLog> {

    List<ConfigChangeLog> findByDeviceIdOrderByCreatedAtDesc(Long deviceId);

    List<ConfigChangeLog> findAllByOrderByCreatedAtDesc();

    Page<ConfigChangeLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Modifying
    @Query("DELETE FROM ConfigChangeLog l WHERE l.deviceId = :deviceId")
    int deleteAllByDeviceId(@Param("deviceId") Long deviceId);
}
