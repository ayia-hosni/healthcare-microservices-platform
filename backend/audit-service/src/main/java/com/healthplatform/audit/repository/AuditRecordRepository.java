package com.healthplatform.audit.repository;

import com.healthplatform.audit.domain.AuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {
    List<AuditRecord> findAllByAggregateId(String aggregateId);
    List<AuditRecord> findAllByRecordedAtBefore(Instant cutoff);
}
