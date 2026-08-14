package com.ensp.nms.repository;

import com.ensp.nms.entity.ConfigTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConfigTemplateRepository extends JpaRepository<ConfigTemplate, Long> {
    
    List<ConfigTemplate> findByDeviceType(String deviceType);
    
    List<ConfigTemplate> findByCategory(String category);
}
