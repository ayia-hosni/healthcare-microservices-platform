import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Gates the admin console. Not logged in -> /login (same as authGuard, and it'll try a silent
 * token refresh first); logged in but not ROLE_ADMIN -> the regular dashboard, silently — same
 * "redirect, don't explain" pattern authGuard already uses, no separate "not authorized" page.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.ensureValidAccessToken().pipe(
    map((ok) => {
      if (!ok) return router.createUrlTree(['/login']);
      if (!auth.isAdmin) return router.createUrlTree(['/']);
      return true;
    })
  );
};
