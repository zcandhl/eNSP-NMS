package com.ensp.nms.repository;

import com.ensp.nms.entity.ConfigChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long>,
        JpaSpecificationExecutor<ConfigChangeLog> {

    List<ConfigChangeLog> findByDeviceIdOrderByCreatedAtDesc(Long deviceId);

    List<ConfigChangeLog> findAllByOrderByCreatedAtDesc();

    Page<ConfigChangeLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    void deleteByDeviceId(Long deviceId);
}
