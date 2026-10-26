# ADR 0005: Same-origin session authentication and CSRF for the SPA

- Status: Accepted
- Date: 2026-10-24

## Context

JTAC's web client is a single-page application served from the same origin as the API.
Credentials or tokens in `localStorage`/`sessionStorage` are exposed to any XSS.

## Decision

- **Server-side HTTP session** (Spring Security 7.1). Cookie `JTAC_SESSION`:
  `HttpOnly`, `SameSite=Lax`, `Secure` when `JTAC_SECURE_COOKIES=true` (required for
  every HTTPS deployment), session timeout 8 h.
- **No tokens in web storage.** The only `localStorage` key is the theme preference.
  The e2e suite asserts this.
- **CSRF**: `csrf.spa()` (Spring Security's SPA configuration) with a readable
  `XSRF-TOKEN` cookie echoed in `X-XSRF-TOKEN`. `GET /api/v1/auth/csrf` issues/refreshes
  the cookie; the client fetches it on demand and retries a login once after a
  `csrf-rejected` problem. Login and logout rotate the token (`CsrfAuthenticationStrategy`,
  `CsrfLogoutHandler`).
- **Login** `POST /api/v1/auth/login` (JSON) authenticates through the
  `AuthenticationManager`, then applies session-fixation protection
  (`ChangeSessionIdAuthenticationStrategy`) and saves the security context to the session.
  Unknown, disabled and wrong-password attempts return one generic
  `invalid-credentials` problem; `DaoAuthenticationProvider` performs dummy password
  checks for unknown users.
- **Logout** `POST /api/v1/auth/logout` (Spring Security filter, CSRF-protected) → 204,
  invalidates the session and deletes the cookie. Replaying an old session cookie
  after logout returns 401 (verified against the real server).
- **Sign-in policy lives in `SignInService`** (throttling, password-length rule,
  credential check, sign-in bookkeeping); the controller only establishes the session.
- **Brute-force protection** (`LoginThrottle`): each attempt is *reserved* atomically
  before credentials are checked and released on success, so concurrent requests cannot
  bypass the limits. Limits per 15-minute sliding window: 5 per (account, client
  address), 30 per client address, 50 per account across all addresses; excess attempts
  get `429` with `Retry-After`. Keying the tight limit on (account, client) means one
  client cannot lock another user out with a handful of wrong passwords; at most
  100 000 keys are tracked (least recently used evicted first).
- **Account re-validation**: `ActiveAccountFilter` checks on every authenticated request
  that the session's account still exists and is `ACTIVE`; otherwise the session is
  invalidated and the request continues unauthenticated (401 for the API).
- **Email identity**: account creation validates addresses with the same Bean
  Validation `@Email` rules as the sign-in request, and lookups are normalized by
  PostgreSQL with the same expression as the generated `email_normalized` column, so
  Java and SQL case mapping cannot disagree.
- **Passwords**: `DelegatingPasswordEncoder` (bcrypt). BCrypt reads only 72 bytes, so
  passwords longer than 72 UTF-8 bytes are rejected at creation, and treated as invalid
  at login instead of being silently truncated.
- **Headers**: CSP `default-src 'self'` (no inline scripts or styles), `frame-ancestors
  'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy:
  strict-origin-when-cross-origin`.
- **Accounts in M1** are created by an administrator (`./gradlew :backend:createUser`,
  inputs from environment variables). There is no self-registration.

## Consequences / known limitations (to address in M2)

- Sessions are in-memory: a restart signs everyone out, and multiple instances need a
  shared session store (Spring Session JDBC is the planned option).
- The login throttle is per instance and in memory; the client key is
  `request.getRemoteAddr()` (no proxy header trust configured yet). A distributed
  attacker using many addresses can still trigger the 50-per-account ceiling and block
  sign-in for that account for up to 15 minutes, and could flush tracked keys by
  generating more than 100 000 of them within the window. Risk-based challenges, MFA and
  a shared store are planned for M2.
- The account re-validation adds one indexed primary-key query per authenticated
  request; caching may be introduced if measurements require it.
- **Reverse proxies / TLS termination are not configured yet**
  (`server.forward-headers-strategy` is unset). Behind a proxy every client would share
  the proxy's address, one noisy client could then throttle all sign-ins, and
  `request.isSecure()` would be false, so the `XSRF-TOKEN` cookie would lack `Secure`.
  Configure trusted forwarded headers before any non-local deployment.
- `JTAC_SECURE_COOKIES` defaults to `false` so that plain-HTTP local development works;
  it is **not secure by default** and must be set to `true` for every HTTPS deployment.
- Argon2id (with rehash-on-login via `DelegatingPasswordEncoder.upgradeEncoding`) is
  the intended upgrade from bcrypt.
- No MFA, SSO (OIDC) or account recovery yet.

## Alternatives considered

- JWT access tokens in browser storage: rejected (XSS exposure, revocation).
- Form-login redirect flow: rejected; the SPA needs JSON problems, not redirects.
