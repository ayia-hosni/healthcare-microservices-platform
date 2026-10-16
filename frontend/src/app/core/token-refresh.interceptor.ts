import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

// Auth endpoints handle their own errors — a 401 from /login means "bad credentials", not
// "expired access token", so never try to refresh in response to one.
const AUTH_PATHS = ['/api/v1/auth/register', '/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout'];

/**
 * Transparent OAuth2 token renewal. When a protected request comes back 401, run the
 * `refresh_token` grant once (AuthService.refresh() de-dupes concurrent callers) and replay the
 * request with the new access token. If the refresh itself fails, the session is over — log out.
 *
 * Must be registered AFTER authInterceptor so the original request already carries a bearer
 * token; the replay sets the refreshed token explicitly.
 */
export const tokenRefreshInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  if (AUTH_PATHS.some((path) => req.url.includes(path))) {
    return next(req);
  }

  return next(req).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 401 || !auth.refreshToken) {
        return throwError(() => err);
      }
      return auth.refresh().pipe(
        switchMap((res) =>
          next(req.clone({ setHeaders: { Authorization: `Bearer ${res.accessToken}` } }))
        ),
        catchError((refreshErr: unknown) => {
          auth.logout();
          return throwError(() => refreshErr);
        })
      );
    })
  );
};
