# JTRAC 3.0.4

Security hardening release. No database migrations: settings, secrets and data carry over
unchanged.

## Fixed

- **The web UI no longer ships source maps.** Up to 3.0.2 the server served `*.js.map`
  files, so a browser's developer tools showed the original frontend source tree and its
  dependencies. They contained no credentials or local paths, but there was no reason to
  publish them. Production builds now have none, and the release gate rejects any package
  whose web UI contains a source map or a `sourceMappingURL` reference.

## Upgrade notes

From 3.0.0 or 3.0.2: unpack 3.0.4 next to the old folder, then run
`jtrac update --from <old folder>` from the new one (see `docs/upgrades.md`). The update
makes a verified backup first; no migrations are pending.

---

# JTRAC 3.0.2

Maintenance release. No database migrations: settings, secrets and data carry over unchanged.

## Fixed

- **Organization branding now applies everywhere in the production package.** The brand
  color was applied through an injected `<style>` element, which the server's
  Content-Security-Policy (`style-src 'self'`) blocks, so only part of the interface changed
  color while navigation highlights, badges, surfaces and borders kept the default orange.
  The theme is now derived in the stylesheet from a single color set through the DOM, which
  the policy allows. The policy itself is unchanged.
- A color taken from an organization's uploaded logo now themes the whole interface too.
- The JTRAC logo and favicon stay readable when the browser uses a dark color scheme.

## Upgrade notes

From 3.0.0: unpack 3.0.2 next to the old folder, then run `jtrac update --from <old folder>`
from the new one (see `docs/upgrades.md`). The update makes a verified backup first; no
migrations are pending.

---

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
