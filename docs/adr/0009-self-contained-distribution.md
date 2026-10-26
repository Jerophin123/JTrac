# ADR 0009: Self-contained server packages, launcher and shared configuration

- Status: Accepted
- Date: 2026-10-26

## Context

JTRAC must be installable by operators who have neither a JDK nor Node.js nor a database
administrator, on Windows, Linux and macOS (x64 and ARM64), with a guided setup, SMTP
configuration with a real test, and safe lifecycle operations. Development keeps using
Gradle, pnpm and the local PostgreSQL service (ADR 0004).

## Decision

1. **One package per OS/architecture**, built natively: the server jar (with the web UI), the
   `jtrac` launcher, a Java 25 runtime linked with `jlink` (modules from `jdeps`), and, where
   redistributable binaries exist, PostgreSQL 18.6 (`io.zonky.test.postgres:
   embedded-postgres-binaries`, checksums pinned). Windows ARM64 has none: external database
   only.
2. **Managed database mode**: a dedicated cluster in the data directory, loopback only, SCRAM
   passwords, `builtin` C.UTF-8 locale, started/stopped by the launcher. **External mode**
   for operators who provide PostgreSQL 18. No other database is ever substituted.
3. **The launcher is plain Java** (no Spring) in its own Gradle project, so lifecycle logic is
   written once for every OS; `.bat`/`.cmd` and POSIX `sh` wrappers only locate the runtime.
4. **Shared configuration library** (`serverconfig`, package `com.jtrac.serverconfig`,
   outside the `com.jtac` module tree): one model, one validator, one SMTP session factory and
   tester, used by the setup wizard, `jtrac configure` and the server's administration API.
5. **Configuration files** in the data directory using the server's own property names:
   `jtrac.properties` (settings) and `secrets.properties` (owner-only) are imported by the
   server; `admin-secrets.properties` (owner and superuser passwords) is read only by the
   launcher, so the running server still never holds DDL credentials (ADR 0004).
6. **Migrations stay out of the server**: the launcher runs `DatabaseMigrations` as the owner
   in a separate JVM (password in the environment) during setup, restore and update.
7. **Graceful stop via a stop-request file** watched by the server, because process signals
   can't stop a JVM gracefully on Windows.
8. **Release gate**: packages are assembled from an allowlist and scanned (denylist, secrets,
   build-machine paths) before and after archiving; SBOMs and license notices are generated.

## Consequences

- Packages are large (about 145 MiB for Windows x64) but need nothing else on the server.
- Linux and macOS packages can be cross-built on any OS (target jmods + a build-time tar
  writer that preserves modes and symlinks), but each target must still be smoke-tested on its
  own platform; status is tracked in `docs/platform-support.md`.
- OS secret stores are not used; secret files rely on file-system permissions.
- Managed backups are offline cluster copies (brief downtime) because the zonky binaries
  don't include `pg_dump`; external databases use `pg_dump`/`pg_restore`.

## Alternatives considered

- **jpackage installers (MSI/DEB/PKG)**: need platform tooling (WiX, signing) and still no
  database; kept as a possible later addition on top of the same staged layout.
- **Containers**: rejected as the primary format (no Docker on target machines; ADR 0004).
- **SQLite/H2 for "simple" installs**: rejected; PostgreSQL-specific features and one tested
  database.
- **Running migrations inside the server at startup**: rejected (ADR 0004).
