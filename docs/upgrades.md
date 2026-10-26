# Upgrades

Each release is a new, self-contained package folder. Your data directory stays where it
is, so upgrading means pointing the new folder at it.

## Steps

1. Read the release notes (`RELEASE-NOTES.md`) of every version you skip.
2. Unpack the new package **next to** the old one (don't unpack over it).
3. From the **new** folder:

   ```
   jtrac update --from <old package folder>
   ```

   This:
   1. stops the running JTRAC (old version),
   2. makes a **verified backup** (`backup-restore.md`),
   3. applies the new version's database migrations as the schema owner,
   4. records the data directory in the new folder and removes it from the old one,
   5. starts the new version and checks its health,
   6. prints how to roll back.

   Settings and secrets are left exactly as they are (verified by the installer smoke test,
   which compares the files byte for byte).
4. If you use automatic start, run `jtrac service install` from the new folder (the old
   registration points at the old folder; `update` warns about it).
5. Keep the old folder until you're satisfied, then delete it.

## Rollback

Migrations only move forward. To go back:

1. `jtrac stop` (new folder).
2. From the **old** folder: `jtrac restore <pre-update backup> --yes`, then `jtrac start`.

Data written after the upgrade is lost by a rollback.

## Rules JTRAC follows

- An update never drops the database, never removes data, and never runs without a verified
  backup first.
- The application itself never migrates the schema: it only validates it at startup and
  refuses to start on a mismatch (ADR 0004).
- Managed PostgreSQL stays on major version 18 within JTRAC 3.x. A future change of major
  version will be documented in the release notes with its own procedure.

## What was tested

`update --from` between two copies of the same 3.0.0 package (Windows x64): stop, verified
backup, migrations (none pending), pointer move, healthy start, data and configuration
unchanged. An update across versions with pending migrations has not been exercised yet,
because 3.0.0 is the first release.
