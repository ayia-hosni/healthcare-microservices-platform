package com.healthplatform.common.security;

import com.healthplatform.common.crypto.FieldEncryptionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The one OAuth2 resource-server security chain shared by every business service (patient,
 * doctor, appointment, emr, billing, audit, analytics, notification). It validates the RS256
 * access tokens issued by identity-service against that service's published JWKS — no signing
 * secret lives here.
 *
 * The JWK set is fetched lazily on the first token (NimbusJwtDecoder.withJwkSetUri), so a
 * service still starts when identity-service is down; keys are cached and refreshed on an
 * unknown {@code kid}.
 *
 * identity-service does NOT use this — it issues tokens as well as verifying its own, needs
 * permitAll on /api/v1/auth/**, /oauth2/jwks and the discovery document, and keeps its own
 * SecurityConfig for that reason.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtSecurityProperties.class, CorsProperties.class, FieldEncryptionProperties.class})
public class PlatformSecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwtSecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwksUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.getIssuerUri()));
        return decoder;
    }

    /** Maps the token's {@code roles} claim (already {@code ROLE_}-prefixed) straight to authorities. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    // @Primary because org.springframework.web.servlet.handler.HandlerMappingIntrospector
    // (the auto-configured "mvcHandlerMappingIntrospector" bean, present whenever Spring MVC
    // is on the classpath) ALSO implements CorsConfigurationSource. Without this, autowiring
    // CorsConfigurationSource below by type is ambiguous and the whole context fails to start
    // with a NoUniqueBeanDefinitionException.
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
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                            JwtAuthenticationConverter jwtAuthenticationConverter,
                                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/docs.html", "/webjars/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
