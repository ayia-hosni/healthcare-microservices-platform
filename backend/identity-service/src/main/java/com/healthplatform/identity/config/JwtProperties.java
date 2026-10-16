package com.healthplatform.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code app.jwt.*} in application.yml. */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /**
     * Issuer identifier stamped into every token's {@code iss} claim and published in the
     * OpenID discovery document. Must be the URL other services reach this service at
     * (e.g. {@code http://identity-service:8081} inside Docker).
     */
    private String issuerUri;

    /** RS256 signing keypair. In production both come from a secrets manager, not a config file. */
    private final Rsa rsa = new Rsa();

    /** Access token lifetime, in minutes. Kept short (default 15) since it can't be revoked once issued. */
    private long accessTokenTtlMinutes = 15;

    /** Refresh token lifetime, in days. Refresh tokens ARE revocable (stored server-side). */
    private long refreshTokenTtlDays = 30;

    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public Rsa getRsa() { return rsa; }
    public long getAccessTokenTtlMinutes() { return accessTokenTtlMinutes; }
    public void setAccessTokenTtlMinutes(long v) { this.accessTokenTtlMinutes = v; }
    public long getRefreshTokenTtlDays() { return refreshTokenTtlDays; }
    public void setRefreshTokenTtlDays(long v) { this.refreshTokenTtlDays = v; }

    /** PEM-encoded keys (PKCS#8 private, X.509/SPKI public), with or without the BEGIN/END armor. */
    public static class Rsa {
        private String privateKey;
        private String publicKey;

        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }
}
