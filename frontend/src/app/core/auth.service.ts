import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, map, of, shareReplay, tap, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { AuthResponse, CreateStaffAccountRequest, DecodedToken, LoginRequest, RegisterRequest, StaffAccountResponse } from './models';

const ACCESS_TOKEN_KEY = 'hp_access_token';
const REFRESH_TOKEN_KEY = 'hp_refresh_token';

/** Clock-skew allowance (seconds) when deciding whether the access token is still usable. */
const EXPIRY_SKEW_SECONDS = 30;

@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly currentUser = signal<DecodedToken | null>(this.decode(this.accessToken));

  /**
   * In-flight refresh, shared so that N requests that 401 at the same moment trigger exactly
   * one POST /api/v1/auth/refresh and all wait on it. Cleared when it settles.
   */
  private refreshInFlight: Observable<AuthResponse> | null = null;

  /** Handle for the proactive "refresh shortly before expiry" timer, so we can cancel it. */
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private http: HttpClient, private router: Router) {
    this.scheduleProactiveRefresh();
  }

  get accessToken(): string | null {
    return localStorage.getItem(ACCESS_TOKEN_KEY);
  }

  get refreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  }

  /** True while a non-expired access token is held. Does not consider the refresh token. */
  get isLoggedIn(): boolean {
    const user = this.currentUser();
    return !!user && user.exp * 1000 > Date.now();
  }

  get isAdmin(): boolean {
    return this.hasAnyRole('ROLE_ADMIN');
  }

  /** ADMIN and DOCTOR are the only roles the backend lets add/remove AvailabilitySlots
   *  (@PreAuthorize("hasAnyRole('ADMIN','DOCTOR')") on DoctorController); NURSE can view the
   *  schedule page but any edit attempt would 403 server-side. */
  get canManageSchedule(): boolean {
    return this.hasAnyRole('ROLE_ADMIN', 'ROLE_DOCTOR', 'ROLE_NURSE');
  }

  /** Authorization primitive — every role check on the client funnels through here. */
  hasAnyRole(...roles: string[]): boolean {
    const granted = this.currentUser()?.roles ?? [];
    return roles.some((r) => granted.includes(r));
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/v1/auth/register', request)
      .pipe(tap((res) => this.storeSession(res)));
  }

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/v1/auth/login', request)
      .pipe(tap((res) => this.storeSession(res)));
  }

  createStaffAccount(request: CreateStaffAccountRequest): Observable<StaffAccountResponse> {
    return this.http.post<StaffAccountResponse>('/api/v1/auth/staff-accounts', request);
  }

  /**
   * OAuth2 `refresh_token` grant: swap the (rotating) refresh token for a fresh access/refresh
   * pair. Concurrent callers share one request. On failure the session is cleared — the caller
   * (guard / interceptor) decides whether to also redirect to /login.
   */
  refresh(): Observable<AuthResponse> {
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }
    const refreshToken = this.refreshToken;
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token available'));
    }

    this.refreshInFlight = this.http
      .post<AuthResponse>('/api/v1/auth/refresh', { refreshToken })
      .pipe(
        tap((res) => this.storeSession(res)),
        catchError((err) => {
          this.clearSession();
          return throwError(() => err);
        }),
        finalize(() => {
          this.refreshInFlight = null;
        }),
        shareReplay(1)
      );
    return this.refreshInFlight;
  }

  /**
   * Used by route guards. Emits true if the caller may proceed: either a valid access token is
   * held, or an expired one was silently refreshed via the refresh token. Emits false when the
   * user must log in again.
   */
  ensureValidAccessToken(): Observable<boolean> {
    if (this.hasFreshAccessToken()) {
      return of(true);
    }
    if (!this.refreshToken) {
      return of(false);
    }
    return this.refresh().pipe(
      map(() => true),
      catchError(() => of(false))
    );
  }

  logout(): void {
    const refreshToken = this.refreshToken;
    this.clearSession();
    if (refreshToken) {
      this.http.post('/api/v1/auth/logout', { refreshToken }).subscribe({ error: () => {} });
    }
    // Force a real navigation rather than relying on the *ngIf shell swap alone: a route's
    // component stays attached to the router state (and re-attaches into whichever shell's
    // <router-outlet> mounts next) until an actual navigation runs the guards again — without
    // this, logging out from e.g. /admin/staff leaves that page's content rendering, chrome-less,
    // under the logged-out shell.
    this.router.navigateByUrl('/login');
  }

  private hasFreshAccessToken(): boolean {
    const user = this.currentUser();
    return !!user && user.exp * 1000 > Date.now() + EXPIRY_SKEW_SECONDS * 1000;
  }

  private storeSession(res: AuthResponse): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, res.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, res.refreshToken);
    this.currentUser.set(this.decode(res.accessToken));
    this.scheduleProactiveRefresh();
  }

  private clearSession(): void {
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    this.currentUser.set(null);
  }

  /** Renew the access token shortly before it expires so a live session never hits a 401. */
  private scheduleProactiveRefresh(): void {
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
    const user = this.currentUser();
    if (!user || !this.refreshToken) {
      return;
    }
    const fireInMs = user.exp * 1000 - Date.now() - environment.tokenRefreshSkewSeconds * 1000;
    if (fireInMs <= 0) {
      // Already inside the skew window — refresh now.
      this.refresh().subscribe({ error: () => {} });
      return;
    }
    this.refreshTimer = setTimeout(() => {
      this.refresh().subscribe({ error: () => {} });
    }, fireInMs);
  }

  /**
   * Decode (not verify — signature verification is the resource servers' job) the JWT payload
   * and reject anything not issued by our configured OpenID Connect issuer.
   */
  private decode(token: string | null): DecodedToken | null {
    if (!token) return null;
    try {
      const payload = token.split('.')[1];
      const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      const claims = JSON.parse(json) as DecodedToken;
      if (claims.iss !== environment.authIssuerUri) {
        console.warn(`Rejecting token from unexpected issuer: ${claims.iss}`);
        return null;
      }
      return claims;
    } catch {
      return null;
    }
  }
}
