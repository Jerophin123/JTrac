# Installing JTRAC

This covers installing a JTRAC server from a release package. To build packages yourself,
see `build-and-release.md`.

## 1. Choose the package

Download the archive for your platform and its `.sha256` file (see `platform-support.md`),
then check the archive:

```powershell
# Windows (PowerShell)
(Get-FileHash .\jtrac-3.0.2-windows-x64.zip -Algorithm SHA256).Hash   # compare with the .sha256 file
```
```sh
# Linux / macOS
sha256sum -c jtrac-3.0.2-linux-x64.tar.gz.sha256        # macOS: shasum -a 256 -c ...
```

## 2. Unpack

Unpack into a folder you keep, owned by the account that will run JTRAC (not root /
Administrator):

- Windows: e.g. `C:\JTRAC\jtrac-3.0.2-windows-x64` (right-click > Extract All, or
  `Expand-Archive`). Avoid `C:\Program Files` (needs admin rights to write logs there).
- Linux: e.g. `~/jtrac` or `/opt/jtrac` (owned by a dedicated `jtrac` user):
  `tar -xzf jtrac-3.0.2-linux-x64.tar.gz`
- macOS: `tar -xzf jtrac-3.0.2-macos-arm64.tar.gz`, then
  `xattr -dr com.apple.quarantine jtrac-3.0.2-macos-arm64` (the package is not notarized).

Nothing else needs to be installed: the package contains its Java runtime and, on most
targets, PostgreSQL.

## 3. Run setup

| Windows | Linux / macOS |
|---|---|
| double-click `setup.bat`, or `setup.bat` in a terminal | `./setup.sh` |

The wizard (see `setup-wizard.md`) asks for:

1. the database mode (managed is recommended),
2. the data directory (configuration, database, attachments, backups, logs),
3. the first administrator (name, email, password),
4. the bind address, HTTP port and public address,
5. outgoing email (SMTP) and sends a test email,
6. who can create accounts and how long sessions last,
7. storage directories and, optionally, operator details for the legal pages.

It then provisions a **new, empty** database, applies migrations, creates the administrator,
starts JTRAC, checks its health and prints the address to open.

Unattended installs: `setup --answers answers.properties` with secrets in environment
variables; template in `config-templates/answers.example.properties`.

## 4. Daily operation

`bin/jtrac` (Linux/macOS) or `bin\jtrac.cmd` (Windows), or the matching `*.sh` / `*.bat`
shortcut in the package folder:

| Command | What it does |
|---|---|
| `status` | version, address, database and server state, health (exit 0 healthy, 3 otherwise) |
| `start` / `stop` / `restart` | starts the managed database and JTRAC; stops gracefully |
| `logs [--lines N] [--follow]` | shows `logs/jtrac.log` |
| `configure [http\|smtp\|security\|storage\|legal]` | changes settings with the wizard's questions and checks |
| `test-email [--to ADDRESS]` | sends a test email with the saved settings |
| `backup`, `verify-backup FILE`, `restore FILE` | see `backup-restore.md` |
| `update --from OLD_FOLDER` | see `upgrades.md` |
| `service install\|remove\|status` | start automatically (below) |
| `uninstall [--delete-data]` | stops and unregisters; keeps data unless deletion is confirmed |

`jtrac` never stops other programs: if the configured port is taken, `start` explains and
exits.

## 5. Start automatically

```
jtrac service install
```

- **Linux**: writes a systemd user unit (`~/.config/systemd/user/jtrac.service`) running
  `jtrac run`. Enable with `systemctl --user enable --now jtrac` (stop a `jtrac start`ed
  instance first). Start at boot without logging in: `sudo loginctl enable-linger <user>`.
  For a system-wide service, copy the unit to `/etc/systemd/system/`, add `User=jtrac`, and
  use `systemctl enable --now jtrac`.
- **macOS**: writes `~/Library/LaunchAgents/com.jtrac.server.plist` (starts at login).
- **Windows**: adds `JTRAC Server.cmd` to your Startup folder (starts JTRAC minimized when
  you sign in). No administrator rights are needed.

### Windows service (start before anyone signs in)

This needs an administrator. Create a dedicated local account (e.g. `jtrac`), give it the
package and data folders, and in Task Scheduler create a task that runs
`"<package>\bin\jtrac.cmd" run --data-dir "<data folder>"` **at system startup**, as that
account, "whether user is logged on or not". Run setup as that account first (secret files
are readable only by their owner).

## 6. Expose it to users

Keep JTRAC bound to `127.0.0.1` and put a reverse proxy with HTTPS in front of it (IIS, nginx,
Caddy, Apache). Then run `jtrac configure http`: set the public address to the `https://`
URL and answer *yes* to "behind a reverse proxy". With an `https://` address, session cookies
are marked Secure. Example nginx location:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    client_max_body_size 30m;
}
```

## Where things are

| | Default |
|---|---|
| Package (replaced on update) | where you unpacked it |
| Data directory | Windows `%LOCALAPPDATA%\JTRAC`, Linux `~/.local/share/jtrac`, macOS `~/Library/Application Support/JTRAC` (chosen during setup) |
| Settings | `<data>/config/jtrac.properties` |
| Secrets (owner-only) | `<data>/config/secrets.properties`, `<data>/config/admin-secrets.properties` |
| Managed database | `<data>/pgsql/data` (127.0.0.1, port chosen in setup, default 54329) |
| Logs | `<data>/logs/jtrac.log`, `jtrac-console.log`, `postgresql.log` |
| Backups | `<data>/backups` |
