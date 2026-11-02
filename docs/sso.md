# Enterprise single sign-on (OpenID Connect)

JTRAC can sign users in through an enterprise identity provider (IdP) over **OpenID
Connect** (authorization code flow, with PKCE and a nonce). Microsoft Entra ID (Azure AD),
Okta, Keycloak, Ping, Auth0, Google Workspace and AD FS all work. After the IdP authenticates
the user, JTRAC opens its usual session cookie (ADR 0005). Nothing changes for the API,
CSRF or API tokens. SAML is not supported; put an OIDC bridge such as Keycloak in front of a
SAML-only IdP.

Password sign-in stays available next to SSO. The sign-in page shows a
**Sign in with <provider>** button for each configured provider.

## How accounts are matched

| Situation at sign-in | Result |
|---|---|
| This IdP identity (`sub`) has signed in before | Signs in to the linked account |
| An account has the same email and the IdP marks it `email_verified=true` | The identity is linked to that account, then signs in |
| …and that account was never confirmed | Also confirmed, and its password is replaced with a random one (whoever registered the address can't keep access) |
| No account has that email | Refused (`no_account`), unless `jtac.sso.auto-provision=true` creates one |
| Email unverified, outside `allowed-domains`, account disabled | Refused |
| The account is already linked to a *different* `sub` at the same IdP | Refused (`identity_conflict`). Usually an address that was reassigned at the IdP |
| No account exists at all (first-run setup not done) | Refused (`setup_required`). Create the administrator through setup first |

Accounts created by SSO are confirmed. They are never server administrators and belong to
no organization until an administrator adds them. They get a random password nobody knows.
A user can set a local password through **Forgot your password?** if email is configured.

Links are keyed by the registration id (e.g. `entra`). Renaming a registration means
existing users are matched by email again on their next sign-in.

## Configure

### With setup (recommended)

`setup` asks about single sign-on in its own optional stage (`setup-wizard.md`, stage 9).
On an installed server, run `jtrac configure sso`:

1. Pick the provider (Microsoft Entra ID, Okta, Google, Keycloak, or any OpenID Connect
   provider). The wizard prints the **redirect URI** to register at the IdP.
2. Enter the issuer URL, the client id and the client secret. The secret goes to
   `secrets.properties` (owner-only), and the registration refers to it as
   `${jtac.sso.client-secret:}`.
3. Choose whether to create accounts automatically, which email domains to allow, and
   whether to trust emails without `email_verified`.
4. The wizard fetches `<issuer>/.well-known/openid-configuration` and checks that the
   published issuer matches exactly. JTRAC does the same at startup and won't start if this
   fails.

For unattended setup, set the `sso.*` answers and the `JTRAC_SSO_CLIENT_SECRET` environment
variable (`config-templates/answers.example.properties`). Run `jtrac configure sso` again to
change the provider or turn single sign-on off; the old registration's keys are removed.
Providers added by hand are left alone: setup manages only the registration named in
`jtrac.sso.provider-id`.

### By hand

1. Register JTRAC at the IdP as a **web application** (confidential client).
   - Redirect URI: `<public URL>/login/oauth2/code/<id>`, e.g.
     `https://tracker.example.com/login/oauth2/code/entra`. JTRAC builds it from
     `jtac.public-url`, never from request headers, so the public URL must be correct.
   - Scopes: `openid email profile`.
2. Add the registration to `jtrac.properties`, and put the client secret in
   `secrets.properties` (see `configuration-reference.md`):

```properties
# jtrac.properties
spring.security.oauth2.client.registration.entra.client-name=Microsoft Entra ID
spring.security.oauth2.client.registration.entra.client-id=<application (client) id>
spring.security.oauth2.client.registration.entra.scope=openid,email,profile
spring.security.oauth2.client.provider.entra.issuer-uri=https://login.microsoftonline.com/<tenant id>/v2.0
jtac.sso.allowed-domains=example.com

# secrets.properties
spring.security.oauth2.client.registration.entra.client-secret=<client secret>
```

3. Restart JTRAC. With `issuer-uri` set, JTRAC reads the IdP's discovery document at
   startup, so the IdP must be reachable then. The button appears once the registration
   loads.

Without discovery, set `authorization-uri`, `token-uri`, `jwk-set-uri` and
`user-name-attribute=sub` under `provider.<id>`. Also set
`registration.<id>.redirect-uri={baseUrl}/{action}/oauth2/code/{registrationId}`. JTRAC
replaces that template with the public URL. Any other explicit value is used as is.

Environment variables work too (`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_ENTRA_CLIENT_ID`,
…). Several registrations can be configured; each gets its own button.

### Provider notes

- **Entra ID:** use a **single-tenant** issuer (`/<tenant id>/v2.0`, not `common` or
  `organizations`). Entra often omits `email_verified`, and a multi-tenant app would let
  another tenant claim any email address. If your tenant's users can't change their `mail`
  attribute and the token lacks `email_verified`, set `jtac.sso.trust-unverified-email=true`.
  JTRAC then also accepts `upn` / `preferred_username` as the email. Add the optional `email`
  claim in the app registration's token configuration.
- **Okta, Keycloak, Auth0, Google:** send `email_verified`; the defaults work.
  Google: `issuer-uri=https://accounts.google.com`, and set `allowed-domains` to your
  Workspace domains, or any Google account can sign in.
- **AD FS:** issuer `https://<adfs host>/adfs`. AD FS usually needs
  `trust-unverified-email=true` and an issuance rule for `email` or `upn`.

## Settings

| Key | Default | Meaning |
|---|---|---|
| `jtac.sso.enabled` (`JTRAC_SSO_ENABLED`) | `true` | master switch; `false` ignores registrations |
| `jtac.sso.auto-provision` (`JTRAC_SSO_AUTO_PROVISION`) | `false` | create accounts on first SSO sign-in. Off keeps the registration mode (`INVITE_ONLY`) authoritative |
| `jtac.sso.allowed-domains` (`JTRAC_SSO_ALLOWED_DOMAINS`) | empty (any) | comma-separated email domains accepted from the IdP. Matching is exact, subdomains are not included |
| `jtac.sso.trust-unverified-email` (`JTRAC_SSO_TRUST_UNVERIFIED_EMAIL`) | `false` | accept the email without `email_verified=true`. Single-tenant directories only |

## Behind a proxy and in development

Forward `/oauth2/authorization/` and `/login/oauth2/code/` to JTRAC together with `/api`.
The Vite development server already proxies both. Register
`http://localhost:5173/login/oauth2/code/<id>` at a development IdP.

## What happens on failure

JTRAC sends the browser to `/login?sso=<code>`, and the sign-in page explains the code.
Codes: `cancelled`, `failed`, `invalid_identity`, `email_unverified`, `domain_not_allowed`,
`no_account`, `account_disabled`, `identity_conflict`, `setup_required`. Text from the IdP
is never shown. The audit log records `auth.sign_in_failed` (method `sso`, reason code),
`auth.signed_in` (method `sso`, provider), `auth.sso_linked` and `auth.sso_provisioned`.

## Limitations

- Signing out ends the JTRAC session only, not the IdP session (no RP-initiated logout).
- Disabling a user at the IdP blocks new SSO sign-ins but doesn't end an open JTRAC session
  (default length 8 h) or revoke the user's API tokens. Disable the account in JTRAC as well.
- JTRAC does not keep the IdP's access or refresh tokens and doesn't sync groups or roles.
  Organization membership is managed in JTRAC.
- Users can't yet see or remove their linked identities in the web app.
- SSO is configured in the configuration files, not on the Server settings page.
