import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { tokenRefreshInterceptor } from './token-refresh.interceptor';
import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

function b64url(value: unknown): string {
  return btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function makeJwt(exp: number): string {
  const claims = {
    iss: environment.authIssuerUri,
    sub: 'user-1',
    email: 'user@example.com',
    roles: ['ROLE_ADMIN'],
    iat: Math.floor(Date.now() / 1000),
    exp,
  };
  return `${b64url({ alg: 'RS256' })}.${b64url(claims)}.sig`;
}

describe('tokenRefreshInterceptor', () => {
  let httpClient: HttpClient;
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('hp_access_token', makeJwt(Math.floor(Date.now() / 1000) + 3600));
    localStorage.setItem('hp_refresh_token', 'refresh-token');

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([tokenRefreshInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    httpClient = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  it('refreshes and replays a request that comes back 401', () => {
    let result: unknown;
    httpClient.get('/api/v1/patients/1').subscribe((r) => (result = r));

    http.expectOne('/api/v1/patients/1').flush({ message: 'expired' }, { status: 401, statusText: 'Unauthorized' });

    const refreshedJwt = makeJwt(Math.floor(Date.now() / 1000) + 3600);
    http.expectOne('/api/v1/auth/refresh').flush({
      accessToken: refreshedJwt,
      refreshToken: 'refresh-token-2',
      accessTokenExpiresInSeconds: 900,
    });

    const replay = http.expectOne('/api/v1/patients/1');
    expect(replay.request.headers.get('Authorization')).toBe(`Bearer ${refreshedJwt}`);
    replay.flush({ id: '1' });

    expect(result).toEqual({ id: '1' });
  });

  it('logs out when the refresh grant also fails', () => {
    const navigate = spyOn(router, 'navigateByUrl');
    httpClient.get('/api/v1/patients/1').subscribe({ next: () => {}, error: () => {} });

    http.expectOne('/api/v1/patients/1').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });

    // The failed refresh already cleared the (now-useless) refresh token, so logout() has
    // nothing to revoke server-side — it just wipes local state and redirects.
    expect(navigate).toHaveBeenCalledWith('/login');
    expect(localStorage.getItem('hp_access_token')).toBeNull();
    expect(localStorage.getItem('hp_refresh_token')).toBeNull();
  });

  it('does not try to refresh a failed /login', () => {
    httpClient.post('/api/v1/auth/login', {}).subscribe({ next: () => {}, error: () => {} });
    http.expectOne('/api/v1/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectNone('/api/v1/auth/refresh');
  });
});
