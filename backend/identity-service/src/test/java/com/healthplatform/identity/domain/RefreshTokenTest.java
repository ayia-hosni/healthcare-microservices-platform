package com.healthplatform.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure domain unit tests for the revocation/expiry rules RefreshToken enforces. */
class RefreshTokenTest {

    @Test
    void isValidForUnrevokedTokenWithFutureExpiry() {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().plusSeconds(3600));

        assertThat(token.isValid()).isTrue();
    }

    @Test
    void isNotValidWhenExpired() {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().minusSeconds(1));

        assertThat(token.isValid()).isFalse();
    }

    @Test
    void isNotValidWhenRevokedEvenIfNotExpired() {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().plusSeconds(3600));

        token.revoke();

        assertThat(token.isValid()).isFalse();
        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    void isNotValidWhenBothExpiredAndRevoked() {
        RefreshToken token = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().minusSeconds(3600));

        token.revoke();

        assertThat(token.isValid()).isFalse();
    }
}
