package com.healthplatform.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The one JWT-resource-server security chain shared by every business service (patient, doctor,
 * appointment, emr, billing, audit, analytics, notification). This used to be a byte-identical
 * SecurityConfig class copy-pasted into each service's config package; it's here now so a change
 * to the auth model (e.g. moving off the shared HMAC secret per ADR-0004) is one edit, not eight.
 *
 * identity-service does NOT use this — it issues tokens rather than only verifying them, needs
 * its own permitAll("/api/v1/auth/**"), and keeps its own SecurityConfig for that reason.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtSecurityProperties.class, CorsProperties.class})
public class PlatformSecurityConfig {

    @Bean
    public JwtVerifier jwtVerifier(JwtSecurityProperties props) {
        return new JwtVerifier(props.getSecret());
    }

    // @Primary because org.springframework.web.servlet.handler.HandlerMappingIntrospector
    // (the auto-configured "mvcHandlerMappingIntrospector" bean, present whenever Spring MVC
    // is on the classpath) ALSO implements CorsConfigurationSource. Without this, autowiring
    // CorsConfigurationSource below by type is ambiguous and the whole context fails to start
    // with a NoUniqueBeanDefinitionException — this went unnoticed until a service actually
    // exercised full Spring context startup end-to-end.
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier jwtVerifier,
                                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new ResourceServerJwtFilter(jwtVerifier), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
