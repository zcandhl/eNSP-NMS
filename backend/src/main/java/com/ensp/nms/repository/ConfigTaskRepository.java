package com.ensp.nms.repository;

import com.ensp.nms.entity.ConfigTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ConfigTaskRepository extends JpaRepository<ConfigTask, String> {

    List<ConfigTask> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime after);

    List<ConfigTask> findTop100ByOrderByCreatedAtDesc();

    @Modifying
    @Transactional
    void deleteByFinishedAtBefore(LocalDateTime cutoff);
}
