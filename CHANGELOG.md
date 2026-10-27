# Changelog

All notable changes to JTrac are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [3.0.2] - 2026-10-27

### Fixed

- Organization branding is applied to the whole interface under the production
  Content-Security-Policy (previously only part of it changed color).
- A color extracted from an organization's logo now themes the whole interface.
- The logo and favicon stay readable in a dark browser color scheme.

### Changed

- NOTICE and README describe the project as an unofficial reimagining of JTrac.

## [3.0.0] - 2026-10-26

First release of this unofficial, modern-era reimagining of JTrac, as a self-contained
server package. Not affiliated with or endorsed by the original JTrac project.

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

### Fixed during release verification

- Linux: the port check refused a port whose old connections were in `TIME_WAIT`, so `start`
  right after `stop` failed (now matches the server's own socket behaviour).
- Linux: `systemctl --user stop` marked the service failed (exit 143 is now success).
- Backups now include PostgreSQL's empty directories; a restore that can't start puts the
  previous database back automatically.
- Answers files keep Windows paths and ignore a UTF-8 byte-order mark.

[3.0.2]: https://github.com/Jerophin123/JTrac/releases/tag/v3.0.2
[3.0.0]: https://github.com/Jerophin123/JTrac/releases/tag/v3.0.0
