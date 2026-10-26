# Backup and restore

## Make a backup

```
jtrac backup [--output DIR]
```

Creates `jtrac-backup-<UTC timestamp>.zip` in the backup directory (default
`<data>/backups`) containing:

| Part | Managed database | External database |
|---|---|---|
| Database | consistent offline copy of the PostgreSQL cluster (`database/pgdata/`) | logical dump with `pg_dump --format=custom` (`database/jtrac.pgdump`) |
| Attachments | `attachments/` | `attachments/` |
| Configuration | `config/jtrac.properties`, `secrets.properties`, `admin-secrets.properties` | same |
| Manifest | `jtrac-backup-manifest.properties`: format, creation time, JTRAC version, platform, schema version, SHA-256 of every file, directory count | same |

For a managed database the server and database are **stopped for the copy** (seconds to
minutes, depending on size) and started again; `backup` waits until JTRAC is healthy. External
databases are dumped online; `pg_dump` 18 must be on the PATH.

Every backup is **verified before success is reported**: each file is read back and its
SHA-256 compared with the manifest. The archive is written atomically and readable only by its
owner, because it contains all data **and the secrets**. Store copies off the server, encrypted.

Check a backup at any time: `jtrac verify-backup FILE`.

Backups are never part of release packages (the release scan rejects `jtrac-backup-*.zip`).

## Restore

```
jtrac restore FILE [--with-config] [--yes]
```

1. Verifies the archive (checksums, file and directory counts).
2. Stops JTRAC (and the managed database).
3. Moves the current database and attachments **aside** to `*.before-restore-<timestamp>`
   (nothing is deleted).
4. Extracts the backup; with `--with-config` also restores the settings and secrets (the
   current ones are copied aside first).
5. Starts the database. If the restored database doesn't start, the previous one is put back
   automatically and the failed copy kept as `data.failed-restore-<timestamp>`.
6. Applies this release's migrations, so a backup from an older version is brought up to date.

Then `jtrac start`. Once satisfied, delete the `*.before-restore-*` folders.

Restore needs the same database mode as the backup, and for managed backups the same
PostgreSQL major version (18). Restoring onto a new machine: install the same JTRAC version,
run setup (any settings), then `jtrac restore FILE --with-config`.

## Verified behaviour

On Windows x64 (see `testing.md`): backup with the server running (stopped, copied, restarted
healthy), `verify-backup`, an issue created after the backup, restore, start: the original
issue is present and the post-backup issue is gone; the previous data was kept aside. Tamper
detection (changed file, missing manifest) is covered by unit tests.

## Schedule backups

- Linux/macOS (cron): `0 2 * * * /opt/jtrac/bin/jtrac backup >> /var/log/jtrac-backup.log 2>&1`
- Windows: a Task Scheduler task running `"<package>\bin\jtrac.cmd" backup` daily as the account that runs JTRAC.

Prune old archives yourself; JTRAC never deletes backups.
