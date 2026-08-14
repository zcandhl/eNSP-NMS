package com.ensp.nms.repository;

import com.ensp.nms.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {
    
    List<Permission> findByResource(String resource);
}
