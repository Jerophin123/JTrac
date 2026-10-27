# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 3.0.x | yes |
| < 3.0 | no (unpackaged development builds) |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting: open https://github.com/Jerophin123/JTrac/security/advisories/new
("Report a vulnerability"). Include:

- the affected version and platform package (e.g. `jtrac-3.0.4-linux-x64`);
- a description and impact, and steps or a proof of concept to reproduce;
- whether the issue is already public.

You can expect an acknowledgement within 7 days and an assessment within 30 days. Fixes are
released as patch versions with an advisory crediting the reporter (unless you prefer not to
be named).

## Scope

In scope: the JTrac server, web UI, `jtrac` launcher, setup wizard, release packaging and the
documentation's security guidance. Out of scope: vulnerabilities in third-party components
that are already public (please report those upstream; we update promptly), denial of service
by resource exhaustion on unprotected deployments, and findings that need a compromised
operating-system account.

## How JTrac is hardened

Read [docs/security.md](docs/security.md) for the threat model, secure defaults (loopback
binding, invite-only registration, CSRF protection, Argon2id, strict CSP, SSRF-safe webhooks,
owner-only secret files) and deployment recommendations. Every release is scanned against the
OSV vulnerability database and for secrets before it is published.
