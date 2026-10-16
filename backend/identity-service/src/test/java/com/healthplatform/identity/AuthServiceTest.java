package com.healthplatform.identity;

import com.healthplatform.common.crypto.BlindIndexHasher;
import com.healthplatform.common.crypto.FieldEncryptionProperties;
import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.identity.config.JwtProperties;
import com.healthplatform.identity.domain.RefreshToken;
import com.healthplatform.identity.domain.Role;
import com.healthplatform.identity.domain.User;
import com.healthplatform.identity.repository.RefreshTokenRepository;
import com.healthplatform.identity.repository.UserRepository;
import com.healthplatform.identity.service.AuthService;
import com.healthplatform.identity.service.JwtService;
import com.healthplatform.identity.web.dto.AuthResponse;
import com.healthplatform.identity.web.dto.CreateStaffAccountRequest;
import com.healthplatform.identity.web.dto.LoginRequest;
import com.healthplatform.identity.web.dto.RegisterRequest;
import com.healthplatform.identity.web.dto.StaffAccountResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private JwtService jwtService;
    private BlindIndexHasher blindIndexHasher;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        jwtService = mock(JwtService.class);

        JwtProperties props = new JwtProperties();
        props.setAccessTokenTtlMinutes(15);
        props.setRefreshTokenTtlDays(30);

        FieldEncryptionProperties encryptionProps = new FieldEncryptionProperties();
        // Fixed 32-byte test key. Real deployments set FIELD_ENCRYPTION_HMAC_KEY
        // (see docker-compose.yml / infra/k8s/base/secrets.yaml).
        encryptionProps.setHmacKey("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=");
        blindIndexHasher = new BlindIndexHasher(encryptionProps);

        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("fake.jwt.token");
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        authService = new AuthService(userRepository, refreshTokenRepository, jwtService, props, blindIndexHasher);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmailHash(blindIndexHasher.hash("aya@example.com"))).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("aya@example.com", "supersecurepassword")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void registerDefaultsToPatientRole() {
        when(userRepository.existsByEmailHash(any())).thenReturn(false);

        authService.register(new RegisterRequest("new@example.com", "supersecurepassword"));

        ArgumentCaptorLike captured = captureSavedUser();
        assertThat(captured.roles()).containsExactly(Role.ROLE_PATIENT);
    }

    @Test
    void createStaffAccountAssignsTheRequestedRole() {
        when(userRepository.existsByEmailHash(any())).thenReturn(false);

        StaffAccountResponse response = authService.createStaffAccount(
                new CreateStaffAccountRequest("doctor@example.com", "supersecurepassword", Role.ROLE_DOCTOR));

        assertThat(response.email()).isEqualTo("doctor@example.com");
        assertThat(response.role()).isEqualTo(Role.ROLE_DOCTOR);
        ArgumentCaptorLike captured = captureSavedUser();
        assertThat(captured.roles()).containsExactly(Role.ROLE_DOCTOR);
    }

    @Test
    void createStaffAccountRejectsPatientRole() {
        assertThatThrownBy(() -> authService.createStaffAccount(
                new CreateStaffAccountRequest("nope@example.com", "supersecurepassword", Role.ROLE_PATIENT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("self-service registration");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createStaffAccountRejectsDuplicateEmail() {
        when(userRepository.existsByEmailHash(blindIndexHasher.hash("admin@example.com"))).thenReturn(true);

        assertThatThrownBy(() -> authService.createStaffAccount(
                new CreateStaffAccountRequest("admin@example.com", "supersecurepassword", Role.ROLE_ADMIN)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void loginRejectsWrongPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User existing = new User("aya@example.com", blindIndexHasher.hash("aya@example.com"),
                encoder.encode("correct-password-123"), EnumSet.of(Role.ROLE_PATIENT));
        when(userRepository.findByEmailHash(blindIndexHasher.hash("aya@example.com"))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.login(new LoginRequest("aya@example.com", "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void loginRejectsLockedAccount() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User existing = new User("aya@example.com", blindIndexHasher.hash("aya@example.com"),
                encoder.encode("correct-password-123"), EnumSet.of(Role.ROLE_PATIENT));
        existing.lock();
        when(userRepository.findByEmailHash(blindIndexHasher.hash("aya@example.com"))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.login(new LoginRequest("aya@example.com", "correct-password-123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("locked");
    }

    @Test
    void loginRejectsDisabledAccount() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User existing = new User("aya@example.com", blindIndexHasher.hash("aya@example.com"),
                encoder.encode("correct-password-123"), EnumSet.of(Role.ROLE_PATIENT));
        existing.disable();
        when(userRepository.findByEmailHash(blindIndexHasher.hash("aya@example.com"))).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.login(new LoginRequest("aya@example.com", "correct-password-123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void refreshRotatesStoredTokenAndIssuesNewPairForValidToken() {
        UUID userId = UUID.randomUUID();
        User user = new User("aya@example.com", "email-hash", "hash", EnumSet.of(Role.ROLE_PATIENT));
        RefreshToken stored = new RefreshToken(userId, "irrelevant-hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AuthResponse response = authService.refresh("raw-refresh-token-value");

        assertThat(response.accessToken()).isEqualTo("fake.jwt.token");
        assertThat(response.refreshToken()).isNotBlank();
        // The presented token is revoked (rotation)...
        assertThat(stored.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(stored);
        // ...and a brand new refresh token is persisted for the replacement pair.
        verify(refreshTokenRepository, times(2)).save(any());
    }

    @Test
    void refreshRejectsTokenThatIsNotFound() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("nonexistent-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid or expired");
    }

    @Test
    void refreshRejectsRevokedStoredToken() {
        RefreshToken revoked = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().plusSeconds(3600));
        revoked.revoke();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh("revoked-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid or expired");
    }

    @Test
    void refreshRejectsExpiredStoredToken() {
        RefreshToken expired = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("expired-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid or expired");
    }

    @Test
    void refreshRejectsWhenUserNoLongerExists() {
        UUID userId = UUID.randomUUID();
        RefreshToken stored = new RefreshToken(userId, "hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("orphaned-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer exists");
    }

    @Test
    void logoutRevokesMatchingStoredToken() {
        RefreshToken stored = new RefreshToken(UUID.randomUUID(), "hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        authService.logout("some-refresh-token");

        assertThat(stored.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(stored);
    }

    @Test
    void logoutIsNoOpWhenTokenNotFound() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        authService.logout("unknown-token");

        verify(refreshTokenRepository, never()).save(any());
    }

    // Minimal helper to avoid pulling in full Mockito ArgumentCaptor boilerplate for a simple record-like read
    private record ArgumentCaptorLike(EnumSet<Role> roles) {}

    private ArgumentCaptorLike captureSavedUser() {
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return new ArgumentCaptorLike(EnumSet.copyOf(captor.getValue().getRoles()));
    }
}
