package com.healthplatform.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from `app.jwt.*` in application.yml (see ADR on secret management for how this is sourced in prod). */
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** Base64-encoded HMAC-SHA256 secret. In production this comes from a secrets manager, not a config file. */
    private String secret;

    /** Access token lifetime, in minutes. Kept short (default 15) since it can't be revoked once issued. */
    private long accessTokenTtlMinutes = 15;

    /** Refresh token lifetime, in days. Refresh tokens ARE revocable (stored server-side). */
    private long refreshTokenTtlDays = 30;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public long getAccessTokenTtlMinutes() { return accessTokenTtlMinutes; }
    public void setAccessTokenTtlMinutes(long v) { this.accessTokenTtlMinutes = v; }
    public long getRefreshTokenTtlDays() { return refreshTokenTtlDays; }
    public void setRefreshTokenTtlDays(long v) { this.refreshTokenTtlDays = v; }
}
