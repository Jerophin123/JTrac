# Setup wizard

`setup.bat` / `./setup.sh` (or `jtrac setup`) turns an unpacked package into a running JTRAC
server. It is implemented in `launcher/src/main/java/com/jtrac/launcher/SetupWizard.java`; the
questions for each settings group live in `Sections.java` and are shared with
`jtrac configure`. All answers are checked with the shared rules in
`serverconfig/.../ConfigValidator.java`, the same rules the Server settings page uses.

## Full-screen setup (keyboard and mouse)

In an interactive terminal, `setup` and `jtrac configure` open a full-screen interface:

- a header with progress ("Step 4 of 14" and a bar), and a sidebar listing every step
  (✓ done, ● current, ○ upcoming);
- the current step's activity (checks, warnings, failures) as it happens, scrollable with the
  mouse wheel or PgUp/PgDn;
- a card with the question: text fields, masked password fields (with a confirm field and a
  **Show** toggle), option lists and Yes/No buttons, each with the same checks as the plain
  prompts and the reason shown in place when an answer is refused;
- a spinner while setup works (provisioning, migrations, starting JTRAC), and a closing
  dialog with the result.

| Action | Keyboard | Mouse |
|---|---|---|
| Answer / continue | Enter | click **Continue** (or click a chosen option again) |
| Move between fields and buttons | Tab, Shift+Tab | click the field or button |
| Choose an option | ↑/↓, 1–9 | click the option |
| Yes / No | Y / N, or ←/→ and Enter | click **Yes** or **No** |
| Edit text | ←/→, Home/End, Backspace/Delete, Ctrl+U clears; paste works | click to place the cursor |
| Leave setup | Esc (asks first; Enter keeps going) | click **Cancel** |

When the interface closes, the terminal is restored and the whole transcript is printed, so
the record stays in the scrollback. Ctrl+C also restores the terminal.

Setup uses the plain line-by-line prompts instead when: output is redirected or there is no
terminal, the terminal is "dumb" or smaller than 72×22 columns, an answers file is used,
`--plain` is passed, or `JTRAC_PLAIN=1` is set. The plain prompts are the better choice with
a screen reader. Without mouse support in the terminal everything still works from the
keyboard. The launcher reads the terminal through JLine 4 with Java's FFM API; the `jtrac`
scripts pass `--enable-native-access=ALL-UNNAMED` for it.

## Stages

| # | Stage | What happens |
|---|---|---|
| 1 | Welcome and mode | Checks OS/architecture; managed (bundled PostgreSQL) or external database. If JTRAC is already set up, it stops here and changes nothing. |
| 2 | Directories and runtime | Data directory (must be absolute and writable); shows the bundled Java runtime; checks the server jar is present. |
| 3 | Database | Managed: picks a free loopback port (default 54329) and generates random passwords for `jtrac_owner` (migrations) and `jtrac_app` (application) and the cluster superuser. External: host, port, database, roles, `sslmode`, passwords; optionally provisions with an administrator account used once and never stored, or prints the SQL for a DBA. |
| 4 | First administrator | Name, email, password (12-128 characters, entered twice). This account is the server administrator. |
| 5 | Address and port | Bind address (default `127.0.0.1`) and HTTP port; the port is checked by binding it, and a free one is suggested if taken. |
| 6 | Public address and proxy | The URL people open (used in email links); whether a reverse proxy sets `X-Forwarded-*`. `https://` turns on Secure cookies. |
| 7 | Outgoing email | SMTP host, TLS mode, port, sign-in, user, password, sender, reply-to, timeout, optional private CA file; sends a **real test email** and shows every step. On failure: change settings, save anyway, or continue in email-pending mode. See `smtp-configuration.md`. |
| 8 | Accounts and sessions | Invite-only (default) or open self-registration; session length (default 8 h). |
| 9 | Single sign-on (optional) | OpenID Connect provider (Microsoft Entra ID, Okta, Google, Keycloak or any other): shows the redirect URI to register, then asks for the issuer URL, client id, client secret, automatic account creation, allowed email domains and whether to trust unverified emails. It then **checks the provider's discovery document** the way the server reads it at startup. On failure: change settings, save anyway, or skip. See `sso.md`. |
| 10 | Storage | Attachments, backups and logs directories (default under the data directory); optional legal operator details. |
| 11 | Review | Shows the settings (secrets never shown) and validates everything; errors stop here with nothing applied. |
| 12 | Bootstrap | Saves settings and secrets (owner-only files), initializes and starts the managed cluster, provisions roles/database/schema, verifies that the application role can't change the schema, applies migrations as the owner in a separate process, refuses to continue if the database already has accounts, creates the administrator. |
| 13 | Start | Starts JTRAC and waits for `/actuator/health/readiness` to report UP. |
| 14 | Done | Prints the address and the next commands (and the SSO redirect URI, if configured). |

## Running it again

Setup is idempotent:

- An installation that finished setup is never changed by `setup`: use `jtrac configure`.
- If setup stopped half-way, running it again resumes with the saved settings. Completed steps
  (`database`, `migrations`, `administrator`) are recorded in `config/setup-state.properties`
  and skipped; generated secrets are never rotated.
- Setup never imports or overwrites data: it stops if the target database already contains
  JTRAC accounts.

## Unattended setup

```
jtrac setup --answers my-answers.properties [--no-start]
```

Start from `config-templates/answers.example.properties`. Values are read literally, so
Windows paths like `C:\JTRAC\data` work as written. Secrets come from environment variables:

| Variable | Needed for |
|---|---|
| `JTRAC_ADMIN_PASSWORD` | first administrator (always) |
| `JTRAC_SMTP_PASSWORD` | SMTP with sign-in |
| `JTRAC_DB_OWNER_PASSWORD`, `JTRAC_DB_APP_PASSWORD` | external database |
| `JTRAC_DB_ADMIN_PASSWORD` | external database with `database.provision=y` |
| `JTRAC_SSO_CLIENT_SECRET` | single sign-on with `sso.enabled=y` |

An invalid answer stops setup with the reason; unknown keys are listed at the end. Answer keys:
`database.mode`, `database.port`, `database.host`, `database.name`, `database.owner-user`,
`database.app-user`, `database.ssl-mode`, `database.provision`, `database.admin-user`,
`data.dir`, `admin.name`, `admin.email`, `http.bind`, `http.port`, `http.public-url`,
`http.behind-proxy`, `smtp.enabled`, `smtp.host`, `smtp.tls`, `smtp.port`, `smtp.auth`,
`smtp.username`, `smtp.from`, `smtp.from-name`, `smtp.reply-to`, `smtp.timeout`,
`smtp.private-ca`, `smtp.trusted-certificates`, `smtp.test`, `smtp.test.recipient`,
`security.registration`, `security.session-hours`, `sso.enabled`, `sso.provider`
(`entra`, `okta`, `google`, `keycloak`, `oidc`), `sso.provider-id`, `sso.name`, `sso.issuer-uri`,
`sso.client-id`, `sso.auto-provision`, `sso.allowed-domains`, `sso.trust-unverified-email`,
`sso.check`, `storage.attachments`,
`storage.backups`, `storage.logs`, `legal.configure`, `legal.*`, `confirm`, `start`.

## Changing settings later

```
jtrac configure            # choose a group (full screen; add --plain for line prompts)
jtrac configure smtp       # or name it: http, smtp, security, storage, legal, sso
```

The same questions and checks as setup; values are saved and, if JTRAC is running, you're
offered a restart. Email can also be changed without a restart in **Server settings** (user
menu, server administrators only).
