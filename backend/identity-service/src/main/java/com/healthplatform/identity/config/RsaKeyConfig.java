package com.healthplatform.identity.config;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Turns the PEM keys in {@code app.jwt.rsa.*} into the beans the token pipeline needs:
 * the {@link JwtEncoder} that signs access tokens, the {@link JWKSource} that
 * {@code JwksController} publishes, and a local {@link JwtDecoder} for verifying this
 * service's own tokens.
 *
 * The {@code kid} is derived from the key's RFC 7638 thumbprint so it stays stable as long as
 * the key does and changes the moment the key is rotated.
 */
@Configuration
public class RsaKeyConfig {

    private final RSAKey rsaKey;

    public RsaKeyConfig(JwtProperties properties) {
        JwtProperties.Rsa rsa = properties.getRsa();
        if (!StringUtils.hasText(rsa.getPrivateKey()) || !StringUtils.hasText(rsa.getPublicKey())) {
            throw new IllegalStateException(
                    "app.jwt.rsa.private-key and app.jwt.rsa.public-key must both be set");
        }
        RSAPublicKey publicKey = parsePublic(rsa.getPublicKey());
        RSAPrivateKey privateKey = parsePrivate(rsa.getPrivateKey());
        try {
            this.rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyIDFromThumbprint()
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build RSA JWK from the configured keys", e);
        }
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** The single public JWK served at {@code /oauth2/jwks} (private material stripped). */
    @Bean
    public RSAKey signingJwkPublic() {
        try {
            return rsaKey.toPublicJWK();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuerUri()));
        return decoder;
    }

    private static RSAPublicKey parsePublic(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid app.jwt.rsa.public-key", e);
        }
    }

    private static RSAPrivateKey parsePrivate(String pem) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid app.jwt.rsa.private-key", e);
        }
    }

    private static byte[] der(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
