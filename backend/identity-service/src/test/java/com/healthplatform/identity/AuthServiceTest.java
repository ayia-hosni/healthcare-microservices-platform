package com.healthplatform.identity;

import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.identity.config.JwtProperties;
import com.healthplatform.identity.domain.Role;
import com.healthplatform.identity.domain.User;
import com.healthplatform.identity.repository.RefreshTokenRepository;
import com.healthplatform.identity.repository.UserRepository;
import com.healthplatform.identity.service.AuthService;
import com.healthplatform.identity.service.JwtService;
import com.healthplatform.identity.web.dto.LoginRequest;
import com.healthplatform.identity.web.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        jwtService = mock(JwtService.class);

        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-value-at-least-32-bytes-long!!");
        props.setAccessTokenTtlMinutes(15);
        props.setRefreshTokenTtlDays(30);

        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("fake.jwt.token");
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        authService = new AuthService(userRepository, refreshTokenRepository, jwtService, props);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("aya@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("aya@example.com", "supersecurepassword")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void registerDefaultsToPatientRole() {
        when(userRepository.existsByEmail(any())).thenReturn(false);

        authService.register(new RegisterRequest("new@example.com", "supersecurepassword"));

        ArgumentCaptorLike captured = captureSavedUser();
        assertThat(captured.roles()).containsExactly(Role.ROLE_PATIENT);
    }

    @Test
    void loginRejectsWrongPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User existing = new User("aya@example.com", encoder.encode("correct-password-123"), EnumSet.of(Role.ROLE_PATIENT));
        when(userRepository.findByEmail("aya@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.login(new LoginRequest("aya@example.com", "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void loginRejectsLockedAccount() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User existing = new User("aya@example.com", encoder.encode("correct-password-123"), EnumSet.of(Role.ROLE_PATIENT));
        existing.lock();
        when(userRepository.findByEmail("aya@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.login(new LoginRequest("aya@example.com", "correct-password-123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("locked");
    }

    // Minimal helper to avoid pulling in full Mockito ArgumentCaptor boilerplate for a simple record-like read
    private record ArgumentCaptorLike(EnumSet<Role> roles) {}

    private ArgumentCaptorLike captureSavedUser() {
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return new ArgumentCaptorLike(EnumSet.copyOf(captor.getValue().getRoles()));
    }
}
