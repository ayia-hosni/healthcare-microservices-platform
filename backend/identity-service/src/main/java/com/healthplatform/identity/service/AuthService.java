package com.healthplatform.identity.service;

import com.healthplatform.common.exception.BusinessException;
import com.healthplatform.identity.config.JwtProperties;
import com.healthplatform.identity.domain.RefreshToken;
import com.healthplatform.identity.domain.Role;
import com.healthplatform.identity.domain.User;
import com.healthplatform.identity.repository.RefreshTokenRepository;
import com.healthplatform.identity.repository.UserRepository;
import com.healthplatform.identity.web.dto.AuthResponse;
import com.healthplatform.identity.web.dto.LoginRequest;
import com.healthplatform.identity.web.dto.RegisterRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Core authentication flows: register, login, refresh, logout.
 *
 * Business rule this enforces: on registration every account starts as ROLE_PATIENT.
 * Elevated roles (doctor, nurse, billing clerk, admin) are granted out-of-band by an
 * admin action, never self-service at signup — that's a deliberate authorization
 * boundary, not an oversight.
 */
@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        JwtService jwtService,
                        JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException("EMAIL_TAKEN", "An account with this email already exists");
        }

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                EnumSet.of(Role.ROLE_PATIENT)
        );
        userRepository.save(user);

        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException("INVALID_CREDENTIALS", "Invalid email or password"));

        if (user.isAccountLocked()) {
            throw new BusinessException("ACCOUNT_LOCKED", "This account is locked. Contact support.");
        }
        if (!user.isEnabled()) {
            throw new BusinessException("ACCOUNT_DISABLED", "This account is disabled.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid email or password");
        }

        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .filter(RefreshToken::isValid)
                .orElseThrow(() -> new BusinessException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired"));

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BusinessException("INVALID_REFRESH_TOKEN", "User no longer exists"));

        // Rotate: revoke the presented token and issue a brand new pair. This limits the
        // damage window if a refresh token is ever exfiltrated (replay is detectable/blocked).
        stored.revoke();
        refreshTokenRepository.save(stored);

        return issueTokenPair(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        Optional<RefreshToken> stored = refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken));
        stored.ifPresent(t -> {
            t.revoke();
            refreshTokenRepository.save(t);
        });
    }

    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRoles());

        String rawRefreshToken = generateOpaqueToken();
        Instant expiresAt = Instant.now().plusSeconds(jwtProperties.getRefreshTokenTtlDays() * 24 * 3600);
        refreshTokenRepository.save(new RefreshToken(user.getId(), sha256(rawRefreshToken), expiresAt));

        return new AuthResponse(accessToken, rawRefreshToken, jwtProperties.getAccessTokenTtlMinutes() * 60);
    }

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
