package com.healthplatform.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure domain unit tests: no mocks, no Spring context needed. */
class UserTest {

    @Test
    void newUserIsEnabledAndUnlockedByDefault() {
        User user = new User("aya@example.com", "hashed", EnumSet.of(Role.ROLE_PATIENT));

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountLocked()).isFalse();
    }

    @Test
    void lockSetsAccountLockedTrue() {
        User user = new User("aya@example.com", "hashed", EnumSet.of(Role.ROLE_PATIENT));

        user.lock();

        assertThat(user.isAccountLocked()).isTrue();
    }

    @Test
    void unlockClearsAccountLockedFlag() {
        User user = new User("aya@example.com", "hashed", EnumSet.of(Role.ROLE_PATIENT));
        user.lock();

        user.unlock();

        assertThat(user.isAccountLocked()).isFalse();
    }

    @Test
    void disableSetsEnabledFalse() {
        User user = new User("aya@example.com", "hashed", EnumSet.of(Role.ROLE_PATIENT));

        user.disable();

        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    void roleConstructorSupportsMultipleElevatedRoles() {
        User user = new User("doc@example.com", "hashed", EnumSet.of(Role.ROLE_DOCTOR, Role.ROLE_ADMIN));

        assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.ROLE_DOCTOR, Role.ROLE_ADMIN);
    }
}
