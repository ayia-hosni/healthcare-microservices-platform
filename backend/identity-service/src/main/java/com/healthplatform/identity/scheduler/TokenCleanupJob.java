package com.healthplatform.identity.scheduler;

import com.healthplatform.identity.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes expired/revoked refresh tokens so the table doesn't grow unbounded.
 *
 * Plain @Scheduled (not Quartz) is sufficient here: this is a single stateless cleanup
 * task with no need for persistent job state, misfire handling, or clustering-aware
 * locking. Quartz earns its complexity for jobs that must run exactly-once across
 * multiple instances with dynamic scheduling (see the reporting service, which uses it).
 */
@Component
public class TokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenCleanupJob(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(cron = "0 0 3 * * *") // daily at 03:00
    @Transactional
    public void purgeExpiredTokens() {
        long before = refreshTokenRepository.count();
        refreshTokenRepository.findAll().stream()
                .filter(t -> !t.isValid())
                .forEach(refreshTokenRepository::delete);
        long after = refreshTokenRepository.count();
        log.info("Refresh token cleanup: removed {} expired/revoked tokens", before - after);
    }
}
