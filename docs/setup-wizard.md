# Setup wizard

`setup.bat` / `./setup.sh` (or `jtrac setup`) turns an unpacked package into a running JTRAC
server. It is implemented in `launcher/src/main/java/com/jtrac/launcher/SetupWizard.java`; the
questions for each settings group live in `Sections.java` and are shared with
`jtrac configure`. All answers are checked with the shared rules in
`serverconfig/.../ConfigValidator.java`, the same rules the Server settings page uses.

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
| 9 | Storage | Attachments, backups and logs directories (default under the data directory); optional legal operator details. |
| 10 | Review | Shows the settings (secrets never shown) and validates everything; errors stop here with nothing applied. |
| 11 | Bootstrap | Saves settings and secrets (owner-only files), initializes and starts the managed cluster, provisions roles/database/schema, verifies that the application role can't change the schema, applies migrations as the owner in a separate process, refuses to continue if the database already has accounts, creates the administrator. |
| 12 | Start | Starts JTRAC and waits for `/actuator/health/readiness` to report UP. |
| 13 | Done | Prints the address and the next commands. |

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

An invalid answer stops setup with the reason; unknown keys are listed at the end. Answer keys:
`database.mode`, `database.port`, `database.host`, `database.name`, `database.owner-user`,
`database.app-user`, `database.ssl-mode`, `database.provision`, `database.admin-user`,
`data.dir`, `admin.name`, `admin.email`, `http.bind`, `http.port`, `http.public-url`,
`http.behind-proxy`, `smtp.enabled`, `smtp.host`, `smtp.tls`, `smtp.port`, `smtp.auth`,
`smtp.username`, `smtp.from`, `smtp.from-name`, `smtp.reply-to`, `smtp.timeout`,
`smtp.private-ca`, `smtp.trusted-certificates`, `smtp.test`, `smtp.test.recipient`,
`security.registration`, `security.session-hours`, `storage.attachments`,
`storage.backups`, `storage.logs`, `legal.configure`, `legal.*`, `confirm`, `start`.

## Changing settings later

```
jtrac configure            # choose a group
jtrac configure smtp       # or name it: http, smtp, security, storage, legal
```

The same questions and checks as setup; values are saved and, if JTRAC is running, you're
offered a restart. Email can also be changed without a restart in **Server settings** (user
menu, server administrators only).
