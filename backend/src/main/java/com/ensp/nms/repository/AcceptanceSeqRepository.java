package com.ensp.nms.repository;

import com.ensp.nms.entity.AcceptanceSeq;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AcceptanceSeqRepository extends JpaRepository<AcceptanceSeq, String> {

    /** 行级锁读取：并发发起验收时序号不重复 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AcceptanceSeq s where s.scope = :scope")
    Optional<AcceptanceSeq> lockByScope(@Param("scope") String scope);
}
