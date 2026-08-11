package com.healthplatform.identity.security;

import com.healthplatform.identity.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * This filter is where JWT role claims become Spring Security GrantedAuthority objects --
 * the actual RBAC wiring point. Covers the happy path (roles mapped to authorities), the
 * "no token present" and "malformed header" pass-through cases, and invalid-token handling.
 */
class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        filter = new JwtAuthenticationFilter(jwtService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsRoleClaimsToGrantedAuthoritiesForValidToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid.jwt.token");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-123");
        when(claims.get("roles", List.class)).thenReturn(List.of("ROLE_PATIENT", "ROLE_DOCTOR"));
        when(jwtService.parseAndValidate("valid.jwt.token")).thenReturn(claims);

        filter.doFilterInternal(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("user-123");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PATIENT", "ROLE_DOCTOR");
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesContextEmptyWhenNoAuthorizationHeaderPresent() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService);
    }

    @Test
    void ignoresNonBearerAuthorizationHeader() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService);
    }

    @Test
    void clearsContextAndContinuesChainWhenTokenIsInvalid() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer garbage.token.value");
        when(jwtService.parseAndValidate("garbage.token.value")).thenThrow(new JwtException("bad signature"));

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void clearsContextWhenRolesClaimIsMalformed() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer weird.token.value");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("user-123");
        when(claims.get("roles", List.class)).thenThrow(new IllegalArgumentException("cannot cast claim"));
        when(jwtService.parseAndValidate("weird.token.value")).thenReturn(claims);

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
