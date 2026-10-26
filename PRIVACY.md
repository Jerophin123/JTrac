# Privacy

## The project collects nothing

JTrac has **no telemetry, analytics, crash reporting, update checks or "phone home"** of any
kind. The web UI loads no third-party scripts, fonts or trackers. Nothing about your instance,
your users or your data is ever sent to the Maintainer or to anyone else.

The only outbound connections a JTrac server makes are the ones its operator configures:

| Connection | When | Configured by |
|---|---|---|
| PostgreSQL | always | the operator (managed: loopback only) |
| SMTP server | sending email | the operator (or email stays queued) |
| Webhook endpoints | events, if webhooks are set up | organization administrators |

Release builds additionally contact Maven Central, Adoptium (GitHub releases) and the npm
registry **on the build machine** to download dependencies, and api.osv.dev if you run the
vulnerability scan; release packages themselves don't.

## Personal data inside an instance

An instance stores what its users put into it (accounts, issues, comments, attachments,
activity history). The operator of that instance is responsible for it. JTrac helps the
operator meet data-protection duties:

- Terms of Service, Privacy Notice and Grievance Redressal pages (templates aligned with
  India's DPDP Act, 2023), configurable operator and grievance officer details;
- recorded, versioned consent with re-consent on change, withdrawal and erasure requests;
- a per-user data download;
- owner-only storage of secrets, Argon2id password hashing, no secrets in logs.

See [docs/security.md](docs/security.md) and the legal pages in a running instance
(`/legal/privacy`).

## This repository

GitHub's own privacy statement applies to your activity on GitHub (issues, stars, forks).
Please don't post personal data or production logs in issues.
