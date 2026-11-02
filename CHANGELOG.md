# Changelog

All notable changes to JTrac are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [3.2.0] - 2026-11-02

### Added

- **AI agent connections** over the Model Context Protocol (MCP): coding agents (Claude Code,
  Codex, Gemini CLI, Cursor and other MCP clients) read issues and, only with permission,
  comment, change fields and move issues. Browser sign-in with OAuth 2.1 and PKCE; read-only
  by default. Account › AI agents. (ADR 0010, `docs/ai-agents.md`)
- **UTCP** (Universal Tool Calling Protocol, experimental): a read-only tool manual and tool
  execution endpoint for agent runtimes. (`docs/utcp.md`)
- **Enterprise single sign-on** over OpenID Connect (Microsoft Entra ID, Okta, Keycloak, Google
  Workspace, AD FS and others), configurable in `setup` and `jtrac configure sso`, with a
  provider check before saving. Accounts are linked only by verified email; automatic account
  creation and allowed email domains are opt-in. (ADR 0011, `docs/sso.md`)
- **Full-screen setup** in the terminal for `setup` and `jtrac configure`, with keyboard and
  mouse: steps sidebar, live activity, clickable fields, options and buttons. `--plain` or
  `JTRAC_PLAIN=1` keeps the line-by-line prompts.
- **Keyboard shortcuts** (`?` lists them), with customization under Account › Keyboard
  shortcuts: change, remove or reset any shortcut, or turn single-key shortcuts off.
- **Collapsible sidebar** (icon rail, `[`), and **search right in the sidebar** with results
  as you type (`/`).
- **My work** shows what needs you: overdue, due this week, in progress and done counters,
  an "Up next" list grouped by due date, an inbox preview, open work by priority and project,
  recently completed issues and your dashboards.
- **About JTRAC** (Account › About JTRAC): version, the installation's operator, the legal
  documents and open-source licenses.
- **Cookie Policy** describing the two strictly necessary cookies and the preferences kept in
  the browser (no tracking or analytics).

### Changed

- Server settings moved into Your account (Server overview, Outgoing email); the old address
  redirects. The profile menu now holds Profile & settings and Sign out.
- Every page uses the same content width.
- The interface follows the logo's shape (rounded and pointed corners) and has subtle motion,
  which respects the "reduce motion" setting.
- The Privacy Notice lists every preference stored in the browser.

### Fixed

- Screen readers read the inbox count and search results with proper spacing.
- The JTRAC mark is no longer clipped on status screens and in the collapsed sidebar.
- A faint streak could appear next to the strokes above page titles.

### Database

- Migrations V9 (AI agent connections), V10 (UTCP) and V11 (single sign-on identities);
  `jtrac update` applies them after a verified backup.

## [3.0.4] - 2026-10-27

### Security

- The web UI no longer ships source maps, so browser developer tools no longer show the
  original frontend source. The release gate rejects packages that contain them.

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

[3.0.4]: https://github.com/Jerophin123/JTrac/releases/tag/v3.0.4
[3.0.2]: https://github.com/Jerophin123/JTrac/releases/tag/v3.0.2
[3.0.0]: https://github.com/Jerophin123/JTrac/releases/tag/v3.0.0
