import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { AuthResponse } from './models';
import { environment } from '../../environments/environment';

function b64url(value: unknown): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt(overrides: Record<string, unknown> = {}): string {
  const claims = {
    iss: environment.authIssuerUri,
    sub: 'user-1',
    email: 'user@example.com',
    roles: ['ROLE_ADMIN'],
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 3600,
    ...overrides,
  };
  return `${b64url({ alg: 'RS256', typ: 'JWT' })}.${b64url(claims)}.signature`;
}

function authResponse(access = makeJwt()): AuthResponse {
  return { accessToken: access, refreshToken: 'refresh-token', accessTokenExpiresInSeconds: 900 };
}

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('stores the session and decodes roles on login', async () => {
    const promise = firstValueFrom(service.login({ email: 'a@b.c', password: 'x' }));
    http.expectOne('/api/v1/auth/login').flush(authResponse());
    await promise;

    expect(service.isLoggedIn).toBeTrue();
    expect(service.isAdmin).toBeTrue();
    expect(service.hasAnyRole('ROLE_NURSE')).toBeFalse();
  });

  it('rejects a token minted by an unexpected issuer', async () => {
    const promise = firstValueFrom(
      service.login({ email: 'a@b.c', password: 'x' })
    );
    http.expectOne('/api/v1/auth/login').flush(authResponse(makeJwt({ iss: 'https://evil.example' })));
    await promise;

    expect(service.currentUser()).toBeNull();
    expect(service.isLoggedIn).toBeFalse();
  });

  it('de-dupes concurrent refresh calls into a single request', async () => {
    localStorage.setItem('hp_access_token', makeJwt({ exp: Math.floor(Date.now() / 1000) - 10 }));
    localStorage.setItem('hp_refresh_token', 'refresh-token');

    const a = firstValueFrom(service.refresh());
    const b = firstValueFrom(service.refresh());

    const req = http.expectOne('/api/v1/auth/refresh');
    expect(req.request.body).toEqual({ refreshToken: 'refresh-token' });
    req.flush(authResponse());

    await Promise.all([a, b]);
    expect(service.isLoggedIn).toBeTrue();
  });

  it('ensureValidAccessToken() short-circuits when the access token is still fresh', async () => {
    const login = firstValueFrom(service.login({ email: 'a@b.c', password: 'x' }));
    http.expectOne('/api/v1/auth/login').flush(authResponse());
    await login;

    const ok = await firstValueFrom(service.ensureValidAccessToken());
    expect(ok).toBeTrue();
    http.expectNone('/api/v1/auth/refresh');
  });

  it('ensureValidAccessToken() refreshes an expired access token', async () => {
    localStorage.setItem('hp_access_token', makeJwt({ exp: Math.floor(Date.now() / 1000) - 10 }));
    localStorage.setItem('hp_refresh_token', 'refresh-token');

    const result = firstValueFrom(service.ensureValidAccessToken());
    http.expectOne('/api/v1/auth/refresh').flush(authResponse());

    expect(await result).toBeTrue();
  });

  it('ensureValidAccessToken() returns false when there is no refresh token', async () => {
    const ok = await firstValueFrom(service.ensureValidAccessToken());
    expect(ok).toBeFalse();
    http.expectNone('/api/v1/auth/refresh');
  });

  it('clears the session when the refresh grant is rejected', async () => {
    localStorage.setItem('hp_access_token', makeJwt({ exp: Math.floor(Date.now() / 1000) - 10 }));
    localStorage.setItem('hp_refresh_token', 'stale-refresh-token');

    const result = firstValueFrom(service.ensureValidAccessToken());
    http.expectOne('/api/v1/auth/refresh').flush({ message: 'expired' }, { status: 401, statusText: 'Unauthorized' });

    expect(await result).toBeFalse();
    expect(localStorage.getItem('hp_refresh_token')).toBeNull();
  });
});
