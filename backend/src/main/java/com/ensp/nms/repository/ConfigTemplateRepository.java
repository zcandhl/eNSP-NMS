package com.ensp.nms.repository;

import com.ensp.nms.entity.ConfigTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 配置模板数据访问仓库：封装对应实体的持久化查询与写入。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@Repository
public interface ConfigTemplateRepository extends JpaRepository<ConfigTemplate, Long> {
    
    List<ConfigTemplate> findByDeviceType(String deviceType);
    
    List<ConfigTemplate> findByCategory(String category);
}
