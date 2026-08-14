package com.ensp.nms.repository;

import com.ensp.nms.entity.LlmSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmSettingsRepository extends JpaRepository<LlmSettings, Long> {
}
