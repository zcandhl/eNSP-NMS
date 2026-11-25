package com.ensp.nms.repository;

import com.ensp.nms.entity.ConfigTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 配置异步任务数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

public interface ConfigTaskRepository extends JpaRepository<ConfigTask, String> {

    List<ConfigTask> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime after);

    List<ConfigTask> findTop100ByOrderByCreatedAtDesc();

    @Modifying
    @Transactional
    void deleteByFinishedAtBefore(LocalDateTime cutoff);
}
