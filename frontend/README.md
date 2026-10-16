# Healthcare Frontend

A simple Angular app for the `healthcare-platform` backend: register/login, search doctors,
manage your patient profile, and book/cancel appointments.

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 19.2.27.

## Backend services this talks to

| Path prefix              | Backend service     | Port |
|---------------------------|----------------------|------|
| `/api/v1/auth/**`         | identity-service     | 8081 |
| `/api/v1/patients/**`     | patient-service      | 8082 |
| `/api/v1/doctors/**`      | doctor-service       | 8083 |
| `/api/v1/appointments/**` | appointment-service  | 8084 |

Start the backend first (`docker compose up --build` from the repo root), then run this app.

## Authentication & authorization

The app authenticates against `identity-service`, which acts as a minimal OpenID Connect
provider (it publishes `/.well-known/openid-configuration` and `/oauth2/jwks`, and issues
RS256-signed JWT access tokens).

- **Login / register** call `POST /api/v1/auth/{login,register}` and receive an
  `accessToken` (15 min) + `refreshToken` (30 days). Both are kept in `localStorage`
  (`hp_access_token` / `hp_refresh_token`) by `AuthService`.
- **JWT** — `AuthService.decode()` reads the token payload (`sub`, `email`, `roles`, `exp`,
  `iss`) without verifying the signature (that's each resource server's job via the issuer's
  JWKS). Tokens whose `iss` claim doesn't match `environment.authIssuerUri` are rejected.
- **Bearer attach** — `authInterceptor` adds `Authorization: Bearer <token>` to every
  non-public request.
- **Silent refresh** — `tokenRefreshInterceptor` catches a `401`, runs the OAuth2
  `refresh_token` grant once (concurrent 401s share the single refresh), and replays the
  request. `AuthService` also refreshes proactively ~60s before expiry. A failed refresh
  logs the user out.
- **Authorization** — route guards (`authGuard`, `adminGuard`, `staffGuard`) gate routes by
  login state and role; they trigger a silent refresh before redirecting to `/login`.
  `AuthService.hasAnyRole(...)` is the single client-side role check, also used to hide nav
  the current user can't use. All of this is advisory — every endpoint is enforced
  server-side with `@PreAuthorize`.

The issuer URL and refresh skew live in `src/environments/environment*.ts`.

## Development server

To start a local development server, run:

```bash
npm install   # first time only
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

Requests to `/api/v1/**` are forwarded to the right backend port by `proxy.conf.json`
(wired up in `angular.json` under `serve.options.proxyConfig`). This is what lets the
dev server talk to four different backend ports without hitting CORS — the browser only
ever talks to `localhost:4200`, and the Angular CLI dev server proxies server-side.

**Note:** this proxy only applies to `ng serve`. If you build this app for production and
serve it standalone (e.g. via Nginx) against the raw backend ports, you'll need either an
API gateway in front of the services, or to update each service's CORS config
(`identity-service`'s `SecurityConfig` currently only allows `https://app.example-health.com`;
the other services don't enable CORS at all) to allow your frontend's origin.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Karma](https://karma-runner.github.io) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
