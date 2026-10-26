# JTRAC 3.0.0

First release of JTRAC as a self-contained server package.

## Highlights

- Project and issue tracking with configurable workflows, field rules, boards, lists,
  timelines, dashboards, saved views, search, mentions, notifications, attachments,
  webhooks, API tokens and CSV import/export.
- Organizations with roles; only server administrators create organizations.
- Terms of Service, Privacy Notice (India DPDP Act) and grievance pages, with recorded consent.
- **Self-contained packages** with a bundled Java runtime and, on most targets, a bundled
  PostgreSQL 18 that JTRAC manages for you (loopback only).
- **Setup wizard** (`setup`): database, first administrator, port and address, outgoing email
  with a real test email, account policy, storage, then start and health check. Safe to re-run.
- **Lifecycle commands**: start, stop, restart, status, logs, configure, test-email, backup,
  restore, update, service install, uninstall.
- **Outgoing email administration** in Server settings, with step-by-step SMTP diagnostics.

## Upgrade notes

This is the first packaged release. See `docs/upgrades.md` for how later releases are applied.

## Known limitations

See `docs/platform-support.md` for the test status of each operating system and architecture.
