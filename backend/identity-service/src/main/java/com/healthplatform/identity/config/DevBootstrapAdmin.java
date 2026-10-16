package com.healthplatform.identity.config;

import com.healthplatform.common.crypto.BlindIndexHasher;
import com.healthplatform.identity.domain.Role;
import com.healthplatform.identity.domain.User;
import com.healthplatform.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;

/**
 * Seeds a single ADMIN account on startup so there is a way in before any other admin exists
 * (creating staff accounts is itself an admin-only action). Local/dev convenience only —
 * {@code app.bootstrap.admin.enabled} is unset in real deployments, where the first admin is
 * provisioned out of band.
 *
 * This replaces {@code infra/seed/seed.sh}'s old trick of locally minting an admin JWT with the
 * shared HMAC secret, which RS256 no longer allows.
 */
@Component
@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true")
public class DevBootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevBootstrapAdmin.class);

    private static final String ADMIN_EMAIL = "admin@healthplatform.local";
    private static final String ADMIN_PASSWORD = "SeedStaff-2026!";

    private final UserRepository userRepository;
    private final BlindIndexHasher blindIndexHasher;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public DevBootstrapAdmin(UserRepository userRepository, BlindIndexHasher blindIndexHasher) {
        this.userRepository = userRepository;
        this.blindIndexHasher = blindIndexHasher;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String emailHash = blindIndexHasher.hash(ADMIN_EMAIL);
        if (userRepository.existsByEmailHash(emailHash)) {
            return;
        }
        userRepository.save(new User(
                ADMIN_EMAIL,
                emailHash,
                passwordEncoder.encode(ADMIN_PASSWORD),
                EnumSet.of(Role.ROLE_ADMIN)
        ));
        log.warn("Bootstrapped dev admin account {} — disable app.bootstrap.admin.enabled outside local dev", ADMIN_EMAIL);
    }
}
