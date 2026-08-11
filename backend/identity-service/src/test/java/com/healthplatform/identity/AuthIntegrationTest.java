package com.healthplatform.identity;

import com.healthplatform.identity.web.dto.AuthResponse;
import com.healthplatform.identity.web.dto.LoginRequest;
import com.healthplatform.identity.web.dto.RefreshRequest;
import com.healthplatform.identity.web.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof, against a real Postgres via Testcontainers, that Flyway migrations, the
 * JPA mappings, and the real SecurityConfig / JwtAuthenticationFilter chain all wire up
 * correctly together. None of the mocked unit tests elsewhere in this module touch any of
 * that -- this is the one that actually boots the Spring context and hits it over HTTP.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void disableOutputStreaming() {
        // The JDK's default HttpURLConnection-backed request factory buffers POST bodies in
        // "streaming mode," which cannot replay the request if the server responds 401 --
        // java.net throws HttpRetryException("cannot retry due to server authentication, in
        // streaming mode") instead of just handing back the response. Disabling streaming makes
        // the client buffer the body up front so 401 responses (e.g. wrong-password login) can
        // be read normally. Test-client-only workaround; unrelated to production behavior.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        restTemplate.getRestTemplate().setRequestFactory(factory);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void registerLoginAndAccessProtectedEndpointEndToEnd() {
        String email = "integration-" + System.nanoTime() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest(email, "supersecurepassword");

        ResponseEntity<AuthResponse> registerResponse =
                restTemplate.postForEntity(baseUrl() + "/api/v1/auth/register", registerRequest, AuthResponse.class);

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AuthResponse registerBody = registerResponse.getBody();
        assertThat(registerBody).isNotNull();
        assertThat(registerBody.accessToken()).isNotBlank();
        assertThat(registerBody.refreshToken()).isNotBlank();

        // /actuator/metrics is not in SecurityConfig's permitAll list (only
        // /actuator/health/** is), so it's a real "anyRequest().authenticated()" endpoint --
        // exactly the kind of protected resource the JWT filter guards in every other service.
        ResponseEntity<String> withoutToken =
                restTemplate.getForEntity(baseUrl() + "/actuator/metrics", String.class);
        assertThat(withoutToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(registerBody.accessToken());
        ResponseEntity<String> withToken = restTemplate.exchange(
                baseUrl() + "/actuator/metrics", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(withToken.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Login with the same credentials issues an independent, valid token pair.
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/login", new LoginRequest(email, "supersecurepassword"), AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        assertThat(loginResponse.getBody().accessToken()).isNotBlank();

        // Refresh rotates the refresh token and yields a brand new pair.
        ResponseEntity<AuthResponse> refreshResponse = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/refresh", new RefreshRequest(registerBody.refreshToken()), AuthResponse.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        AuthResponse refreshBody = refreshResponse.getBody();
        assertThat(refreshBody).isNotNull();
        assertThat(refreshBody.refreshToken()).isNotEqualTo(registerBody.refreshToken());

        // The rotated-out refresh token is now revoked and can't be reused (replay defense).
        ResponseEntity<String> reuseOldRefreshToken = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/refresh", new RefreshRequest(registerBody.refreshToken()), String.class);
        assertThat(reuseOldRefreshToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Logout revokes the current refresh token too.
        ResponseEntity<Void> logoutResponse = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/logout", new RefreshRequest(refreshBody.refreshToken()), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> refreshAfterLogout = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/refresh", new RefreshRequest(refreshBody.refreshToken()), String.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void registerRejectsDuplicateEmailWithConflict() {
        String email = "dup-" + System.nanoTime() + "@example.com";
        RegisterRequest request = new RegisterRequest(email, "supersecurepassword");
        restTemplate.postForEntity(baseUrl() + "/api/v1/auth/register", request, AuthResponse.class);

        ResponseEntity<String> secondAttempt =
                restTemplate.postForEntity(baseUrl() + "/api/v1/auth/register", request, String.class);

        assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginRejectsWrongPasswordWithUnauthorized() {
        String email = "wrongpw-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity(baseUrl() + "/api/v1/auth/register",
                new RegisterRequest(email, "supersecurepassword"), AuthResponse.class);

        ResponseEntity<String> loginAttempt = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/login", new LoginRequest(email, "totally-wrong-password"), String.class);

        assertThat(loginAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
