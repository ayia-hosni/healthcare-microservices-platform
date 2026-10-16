package com.healthplatform.common.security.test;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/**
 * Mints RS256 bearer tokens for integration tests, standing in for identity-service. Packaged
 * in common's test-jar and reused by every service's {@code *IntegrationTest}.
 *
 * Usage:
 * <pre>
 *   {@literal @}Import(TestJwtSupport.TestJwtConfig.class)
 *   {@literal @}DynamicPropertySource static void jwt(DynamicPropertyRegistry r) { TestJwtSupport.registerProperties(r); }
 *   ...
 *   headers.setBearerAuth(TestJwtSupport.mint(userId, "ROLE_ADMIN"));
 * </pre>
 */
public final class TestJwtSupport {

    public static final String ISSUER = "https://test.identity.local";

    // Throwaway 2048-bit RSA keypair, test-only. The resource-server chain under test verifies
    // against TEST_PUBLIC_KEY; mint() signs with TEST_PRIVATE_KEY.
    private static final String TEST_PRIVATE_KEY = """
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCllLs/0EMea7PG
            DiWSNbHauiFN2YRCk+D6JNnHPPPJnRRK82aTgHpXGDPngyfPwu1x9J8pBnr0Awr6
            YdBsFz9BRsee56SikSxqjnQeMv/TMFPGcVMyDBnVn8AauaQ6AWiTiAqGiyGYNmUK
            DIhhmjjH63oRB91xtLMM/f4ky0SAOqqjLfSpsXkbHDfweRis/KAlD7UjREdrGsGb
            F9ZB9kcGg5eQ9zMMSd86wOzv2ax+ZUFg1EboZxYcJBDE3J5qnaaVsO63jKDb4quZ
            hLiTlzbBm1whPaaxJNjwZ496CxiAMGksYZu/CepU7XzeiDofloSvUWScUQdyMlBi
            2en7e/7ZAgMBAAECggEAD5//QwRzvvxGGsWVO0y8l7G6iJKDAfbbMzO0xtG8Rh1f
            77o0Bu2H7e3TYRCN+lOwTTF6rvgE9H+QgsPl83tGnXHwzrCYhzw4kyYV9E8W985+
            y1JAme+NJQzmB4eW5R/iptBewOdCcG8KnhYEbSRs4QW6Lz79ITiecFaMC9wQHuLz
            bpQ5aSQddBDdwrFJysIRD1paOyCTvvD4JBCMlFZbEzqgdSM1SABZNVa9+uMURA91
            kMOLn4FZrZWBRWH6ChvhsjQ32cZSmHmgBUg1RSelp+AyleXqkDBBGIzP5RLqAjV5
            dMK2XOD1oGTt9+WfYXepS8NTAxRJP7E4ZOnQSrTm0QKBgQDbAicwR8IInPNIZYLA
            h14sFbIHJs8dj4QsDwRqB8wBwg5uI5Jx958Ggomft+zSudZBbJwwBaUG9L/sfYNL
            ihBjqt/9Tee0k0WD9WzF/3ldQL2627b3dwk7HNmV98vwQNNF0FApdjpQqCrbwDkX
            MNaOWMUaQImQ+ck6CiVo0pQVKQKBgQDBjGUkZIV7Em7BRYKplshBA26wedGmSg/G
            IKdxrOCmDg/Z3xO86B8RoSVOwUvJwgDlq2QXZq8SeSybQGZfFxeuzGpVcsO7LA7X
            GeikRVCiFyz+1cVWPVwXqK8Tvf9YDaD8ynH2mT+Cr6kmx03eGuk4yOf1NrajRpk3
            ICLJccCiMQKBgQCd/O6iQJGzvu0tg7UQCFch/ECiMhflHjvdwLORfbBocEISp+yj
            9FquyopXQso/tbnfFmeRqPDElNjC1ERbwOxTydetln6Y4EicifZ+2u4FHIgB/S//
            UsrA7u3z5n8jANCoIz9Aub4Vo0rPndpRZXTR2S3O41GZ2OeVy+3ysT4MCQKBgHsf
            Lm/6KhdiYGY+K6JqMrV4038b30vjLSmiOCJOERJRO7AjAlFXb/wtAKiaufV8nkl7
            v85LGe+seb3BN94huIUPsEzvFqgInOAksSNenK6gA5WQs1nq6px6R/VNFhQTh2eH
            +Psf/ZDK4dWzT0uM3vOqijQpPAGdtXWl0btHy3zhAoGBAIcTEX+nMFPVTlPSSpJw
            9WBnUYO2I5OlgHbHjzbA+8tDM6l0LkjTaX9IJyREZSsEJ6mGt/+uPWftUDPhvDWp
            FxwotthDbOC0GynMdSuh0OmCWccyKvMw+1od7bQVM8uoAcFLJyGvn9E99em0m9oA
            hjfkxVdTrj8XCErVwbd64Of4
            """;

    private static final String TEST_PUBLIC_KEY = """
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApZS7P9BDHmuzxg4lkjWx
            2rohTdmEQpPg+iTZxzzzyZ0USvNmk4B6Vxgz54Mnz8LtcfSfKQZ69AMK+mHQbBc/
            QUbHnuekopEsao50HjL/0zBTxnFTMgwZ1Z/AGrmkOgFok4gKhoshmDZlCgyIYZo4
            x+t6EQfdcbSzDP3+JMtEgDqqoy30qbF5Gxw38HkYrPygJQ+1I0RHaxrBmxfWQfZH
            BoOXkPczDEnfOsDs79msfmVBYNRG6GcWHCQQxNyeap2mlbDut4yg2+KrmYS4k5c2
            wZtcIT2msSTY8GePegsYgDBpLGGbvwnqVO183og6H5aEr1FknFEHcjJQYtnp+3v+
            2QIDAQAB
            """;

    private static final RSAPublicKey PUBLIC_KEY = parsePublic(TEST_PUBLIC_KEY);
    private static final RSAPrivateKey PRIVATE_KEY = parsePrivate(TEST_PRIVATE_KEY);

    private static final JwtEncoder ENCODER = buildEncoder();

    private TestJwtSupport() {}

    /** A signed RS256 access token: {@code sub} = subject, {@code roles} = roles, 15-minute expiry. */
    public static String mint(String subject, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .claim("roles", List.of(roles))
                .build();
        return ENCODER.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** Points {@code app.jwt.*} at this fake issuer so the real decoder bean can be constructed. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.issuer-uri", () -> ISSUER);
        registry.add("app.jwt.jwks-uri", () -> "https://test.identity.local/oauth2/jwks");
    }

    /** Import into an integration test to swap the network JWKS decoder for the local test key. */
    @TestConfiguration
    public static class TestJwtConfig {
        @Bean
        @Primary
        public JwtDecoder testJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(PUBLIC_KEY).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }

    private static JwtEncoder buildEncoder() {
        RSAKey jwk = new RSAKey.Builder(PUBLIC_KEY).privateKey(PRIVATE_KEY).keyID("test-key").build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(source);
    }

    private static RSAPublicKey parsePublic(String pem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAPrivateKey parsePrivate(String pem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(pem);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
