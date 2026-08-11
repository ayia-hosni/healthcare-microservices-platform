package com.healthplatform.audit.scheduler;

import com.healthplatform.audit.domain.AuditRecord;
import com.healthplatform.audit.repository.AuditRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Archives (in this learning build: deletes after logging a summary) audit records older
 * than the retention window. In production this would export to cold storage (S3/MinIO
 * Glacier-tier) before deleting, to satisfy HIPAA's audit-log retention requirements while
 * keeping the hot Postgres table small.
 */
@Component
public class AuditArchivalJob {

    private static final Logger log = LoggerFactory.getLogger(AuditArchivalJob.class);
    private static final int RETENTION_DAYS = 365;

    private final AuditRecordRepository auditRecordRepository;

    public AuditArchivalJob(AuditRecordRepository auditRecordRepository) {
        this.auditRecordRepository = auditRecordRepository;
    }

    @Scheduled(cron = "0 30 3 * * *") // daily at 03:30
    @Transactional
    public void archiveOldRecords() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        List<AuditRecord> old = auditRecordRepository.findAllByRecordedAtBefore(cutoff);
        // TODO (production): write `old` to MinIO/S3 cold storage before deleting.
        auditRecordRepository.deleteAll(old);
        log.info("Archived {} audit records older than {} days", old.size(), RETENTION_DAYS);
    }
}
