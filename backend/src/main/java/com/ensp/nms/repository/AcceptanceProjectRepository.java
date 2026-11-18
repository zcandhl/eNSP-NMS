package com.ensp.nms.repository;

import com.ensp.nms.entity.AcceptanceProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AcceptanceProjectRepository extends JpaRepository<AcceptanceProject, Long> {
}
