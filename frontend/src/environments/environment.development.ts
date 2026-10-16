/**
 * Local development defaults (the fallback when no production fileReplacement applies).
 * `ng serve` proxies /api/v1/auth to http://localhost:8081 (see proxy.conf.json), which is
 * also the identity-service's default `app.jwt.issuer-uri`.
 */
export const environment = {
  production: false,
  authIssuerUri: 'http://localhost:8081',
  tokenRefreshSkewSeconds: 60,
};
