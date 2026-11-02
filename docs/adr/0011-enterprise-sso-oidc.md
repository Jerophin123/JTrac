# ADR 0011: Enterprise single sign-on over OpenID Connect

- Status: Accepted
- Date: 2026-11-02

## Context

Organizations want to sign in to JTRAC with their enterprise directory (Entra ID, Okta,
Keycloak, Google Workspace, AD FS) instead of a separate password. ADR 0005 established
session-cookie authentication with CSRF and left SSO out of scope. Self-hosted JTRAC must
work with any standards-compliant IdP and must not weaken its invite-only default.

## Decision

1. **OpenID Connect only**, via Spring Security's OAuth 2.0 client
   (`spring-boot-starter-oauth2-client`, managed by Boot 4.1.2). Providers are standard
   `spring.security.oauth2.client.*` registrations; `oauth2Login` is wired only while at
   least one exists and `jtac.sso.enabled` is true. SAML is excluded (an OIDC bridge covers it).
2. **Same session as password sign-in.** After the IdP callback, the success handler swaps
   the OAuth2 authentication for the usual `JtacUserDetails` principal, rotates the session
   id and CSRF token, and saves the context. The rest of the app is unchanged.
3. **Account mapping lives in `EnterpriseSsoService`** (identity module, internal) and
   persists links in `user_external_identity` (V11), keyed by (registration id, `sub`).
   An existing account is linked only by an email the IdP marks verified, unless the
   operator explicitly trusts the IdP. Linking an unconfirmed account confirms it and
   replaces its password, which defeats pre-registration takeover. A second `sub` for an
   already-linked account is refused.
4. **Provisioning is opt-in** (`jtac.sso.auto-provision=false` by default), and the email
   domain can be restricted. Provisioned accounts are never server administrators. SSO is
   refused before first-run setup, so the setup code stays the only way to create the first
   administrator.
5. **URLs come from `jtac.public-url`**: the redirect URI sent to the IdP (when the
   registration uses the default template) and the post-sign-in redirects. A validated
   same-origin return path is kept in the session.
6. **Failures reach the browser only as fixed codes** (`/login?sso=<code>`). Any
   authentication the login filter stored is discarded first. IdP access and refresh tokens
   are not retained.

## Consequences

- Operators configure SSO in `jtrac.properties` / `secrets.properties` (docs/sso.md); with
  `issuer-uri` the IdP must be reachable at startup.
- No RP-initiated logout, group/role sync, or self-service unlinking yet. Disabling a user
  at the IdP does not end an open JTRAC session.
- The public config API lists providers (`ssoProviders`) so the SPA can render one button
  per provider.

## Alternatives considered

- Spring Security's generated login page and default handlers: rejected, it shadows the SPA
  `/login`, echoes exception text and keeps the OAuth2 principal in the session.
- Always auto-provision (common in SaaS): rejected as the default, it bypasses
  `INVITE_ONLY` for anyone the IdP accepts.
- Linking by email without `email_verified`: rejected, it allows account takeover through
  an IdP where users choose their email (the "nOAuth" class of issue).
