import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Gates pages meant for staff (ADMIN/DOCTOR/NURSE), not patients — broader than adminGuard.
 * Same "redirect, don't explain" pattern as authGuard/adminGuard.
 */
export const staffGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.ensureValidAccessToken().pipe(
    map((ok) => {
      if (!ok) return router.createUrlTree(['/login']);
      if (!auth.canManageSchedule) return router.createUrlTree(['/']);
      return true;
    })
  );
};
