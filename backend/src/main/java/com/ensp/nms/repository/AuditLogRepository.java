package com.ensp.nms.repository;

import com.ensp.nms.entity.AuditLog;
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

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {

    @Modifying
    int deleteByCreatedAtBefore(LocalDateTime cutoff);

    @Query("SELECT a.id FROM AuditLog a ORDER BY a.createdAt ASC")
    List<Long> findOldestIds(Pageable pageable);
}
