import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';

// The only truly public endpoints under /api/v1/auth — everything else there (e.g.
// staff-accounts, admin-only) needs the bearer token like any other request.
const PUBLIC_AUTH_PATHS = ['/api/v1/auth/register', '/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout'];

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.accessToken;

  if (!token || PUBLIC_AUTH_PATHS.some((path) => req.url.includes(path))) {
    return next(req);
  }

  return next(
    req.clone({
      setHeaders: { Authorization: `Bearer ${token}` }
    })
  );
};
