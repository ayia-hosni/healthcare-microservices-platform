package com.healthplatform.identity.config;

import com.healthplatform.common.security.CorsProperties;
import com.healthplatform.identity.security.JwtAuthenticationFilter;
import com.healthplatform.identity.service.JwtService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService,
                                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless JWT API, no cookies -> CSRF not applicable
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Without an explicit entry point, Spring Security falls back to
            // Http403ForbiddenEntryPoint for unauthenticated requests (no httpBasic()/formLogin()
            // is configured here to seed a default), which returns 403 instead of the standard
            // 401 for "no/invalid credentials presented." That's wrong REST/HTTP semantics for a
            // token-issuing auth API and breaks clients that branch on 401 vs 403. Authenticated
            // requests that merely lack the required role still fall through to the default
            // AccessDeniedHandler (403), which is correct.
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Locked down explicitly rather than using "*" — HIPAA-adjacent platforms should never
     * rely on permissive CORS defaults. Origins come from CorsProperties (app.cors.allowed-origins,
     * env override CORS_ALLOWED_ORIGINS) — the same property every other service now reads, so a
     * new frontend origin is one env var change, not a hunt through N SecurityConfig classes.
     */
    // @Primary because org.springframework.web.servlet.handler.HandlerMappingIntrospector
    // (the auto-configured "mvcHandlerMappingIntrospector" bean, present whenever Spring MVC
    // is on the classpath) ALSO implements CorsConfigurationSource. Without this, autowiring
    // CorsConfigurationSource above by type is ambiguous and the whole context fails to start
    // with a NoUniqueBeanDefinitionException (mirrors the same fix in the shared
    // common.security.PlatformSecurityConfig used by every other service).
    @Bean
    @Primary
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
