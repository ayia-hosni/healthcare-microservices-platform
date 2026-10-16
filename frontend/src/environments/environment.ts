/**
 * Production defaults. `ng build` (production configuration) swaps in this file via the
 * fileReplacements entry in angular.json; `environment.development.ts` is used otherwise.
 */
export const environment = {
  production: true,

  /**
   * OpenID Connect issuer of the identity-service. Access tokens are RS256-signed JWTs whose
   * `iss` claim must equal this value; the frontend rejects any token that doesn't match
   * (defence against a token minted by a different/rogue issuer being replayed here).
   * Signature verification itself stays server-side — every resource service fetches the
   * issuer's JWKS from `${authIssuerUri}/oauth2/jwks` and verifies independently.
   */
  authIssuerUri: 'https://identity.example-health.com',

  /**
   * Refresh the access token this many seconds before it actually expires, so an in-flight
   * request never races the clock. Access tokens live 15 min server-side.
   */
  tokenRefreshSkewSeconds: 60,
};
