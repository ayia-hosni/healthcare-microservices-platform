import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // ensureValidAccessToken() silently runs the refresh_token grant when the access token has
  // expired but the refresh token is still good, so a returning user isn't bounced to /login
  // just because their short-lived access token lapsed.
  return auth.ensureValidAccessToken().pipe(
    map((ok) => (ok ? true : router.createUrlTree(['/login'])))
  );
};
