package com.ensp.nms.repository;

import com.ensp.nms.entity.AiopsPolicySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiopsPolicySettingsRepository extends JpaRepository<AiopsPolicySettings, Long> {
}
