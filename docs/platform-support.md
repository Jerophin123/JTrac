# Platform support

JTRAC is released as one self-contained package per operating system and CPU architecture.
Every package contains the JTRAC server, the `jtrac` command, a Java 25 runtime linked with
`jlink` (no JDK, `JAVA_HOME`, Gradle or Node.js needed on the server) and, where available,
PostgreSQL 18.6 binaries for the **managed database** mode.

## Status of each target

Status words mean exactly this:

- **Smoke-tested**: the final archive was unpacked and `tools/installer-smoke.py` passed on
  that platform (setup, SMTP test, API, lifecycle, backup/restore, update, uninstall).
- **Cross-built, statically verified**: the package was built by the release pipeline on
  another OS and inspected without running it (`checkUnixArchive` in the build and the
  independent `tools/inspect-unix-package.py`): CPU architecture of every Java and PostgreSQL
  binary from its ELF/Mach-O header; Java native binaries byte-identical to Temurin's;
  macOS code signatures present; executable bits; symlinks resolving inside the archive;
  shell scripts with shebang, LF endings and a passing `sh -n`; runtime modules identical to
  the smoke-tested Windows package; release scans. **Nothing in it has been executed on the
  target OS.**
- **CI configured**: a native job exists in `.github/workflows/release.yml`, but it has not
  run yet (the repository has no CI remote at the time of this release).

| Target | Package | Database strategy | Status (JTRAC 3.0.0) |
|---|---|---|---|
| Windows x64 | `jtrac-<v>-windows-x64.zip` | Managed (bundled PostgreSQL 18.6) or external | **Built and smoke-tested** (36/36 checks) on Windows 11 build 26300, x64 |
| Windows ARM64 | `jtrac-<v>-windows-arm64.zip` | **External PostgreSQL 18 only** (no redistributable Windows ARM64 binaries were found) | CI configured; not built or tested |
| Linux x64 | `jtrac-<v>-linux-x64.tar.gz` | Managed or external | **Smoke-tested** (36/36 checks) on Arch Linux, kernel 7.2, glibc 2.44, x86_64 (cross-built on Windows); systemd user service verified |
| Linux ARM64 (AArch64) | `jtrac-<v>-linux-arm64.tar.gz` | Managed or external | **Cross-built on Windows, statically verified**; not run on Linux |
| macOS x64 (Intel) | `jtrac-<v>-macos-x64.tar.gz` | Managed or external | **Cross-built on Windows, statically verified**; not run on macOS |
| macOS ARM64 (Apple Silicon) | `jtrac-<v>-macos-arm64.tar.gz` | Managed or external | **Cross-built on Windows, statically verified**; not run on macOS |

Do not treat a target as supported for production until its status says smoke-tested.

## Requirements and limitations per platform

| | Windows | Linux | macOS |
|---|---|---|---|
| Minimum OS | Tested: Windows 11 (x64). Windows 10 and Windows Server are expected to work but are untested. | **glibc 2.27 or later** (e.g. Ubuntu 18.04+, Debian 10+, RHEL/Rocky/Alma 8+, SLES 15+), read from the highest GLIBC symbol version the bundled PostgreSQL needs; musl (Alpine) is not supported. | **macOS 13 Ventura or later**, read from the Mach-O minimum-version commands (PostgreSQL needs 13.0; the Java runtime 11.0-12.0). |
| Packaging | ZIP; run `setup.bat` | tar.gz; run `./setup.sh` | tar.gz; run `./setup.sh` |
| Automatic start | `jtrac service install` adds a script to your Startup folder (starts at sign-in, no admin rights). Running as a Windows service before sign-in needs an administrator: see *Windows service* in `installation.md`. | systemd **user** unit (`systemctl --user`); start at boot needs `loginctl enable-linger` | launchd agent in `~/Library/LaunchAgents` (starts at login) |
| Code signing | Not signed: SmartScreen may warn when the ZIP was downloaded from the internet. | n/a | The Java and PostgreSQL binaries carry their publishers' code signatures (verified present; Java binaries byte-identical to Temurin's), but the **package is not notarized**: Gatekeeper blocks files from a browser download. Remove the quarantine flag after unpacking: `xattr -dr com.apple.quarantine jtrac-<v>-macos-*` |
| Offline install | Yes (everything is in the package) | Yes | Yes |

## Database strategies

1. **Managed (default where bundled)**: setup creates a new PostgreSQL 18.6 cluster in the
   data directory, listening on `127.0.0.1` only, with password (SCRAM-SHA-256) authentication
   for every role, and starts/stops it with JTRAC. Binaries: `io.zonky.test.postgres:
   embedded-postgres-binaries` 18.6.0 from Maven Central, checksums pinned in
   `distribution/postgres-binaries.properties`.
   The macOS binaries are universal (x86_64 + arm64), which is why macOS packages are larger
   (about 224 MiB versus 131-133 MiB for Linux).
2. **External**: any PostgreSQL 18 server. Setup can create the roles, database and schema
   with an administrator account you enter once (not stored), or print the SQL for your DBA.
   Requires network access to that server, and `pg_dump`/`pg_restore` 18 on the PATH for
   `jtrac backup`/`restore`.

JTRAC never substitutes another database.

## What was verified for Linux and macOS (without running)

| Package | Size | Binary architecture (Java, JVM, PostgreSQL) | Other checks |
|---|---|---|---|
| linux-x64 | ~133 MiB | ELF 64-bit x86-64 | 14 symlinks resolve; executables 0755; `sh -n` passes for 13 scripts |
| linux-arm64 | ~131 MiB | ELF 64-bit AArch64 | same |
| macos-x64 | ~225 MiB | Mach-O x86_64 (PostgreSQL universal x86_64+arm64), all slices signed | same (no symlinks) |
| macos-arm64 | ~224 MiB | Mach-O arm64 (PostgreSQL universal), all slices signed | same |

Linux x64 has since been run for real (next section). Still unverified on the real OS for
linux-arm64 and both macOS packages: setup, the managed PostgreSQL, background start, graceful
stop, backups, and the launchd agent. Run `tools/installer-smoke.py` there (see `testing.md`).

## What was verified on Linux x64 (Arch Linux VM)

`tools/installer-smoke.py` on the final archive: all 36 checks, the same as on Windows, with
the managed PostgreSQL 18.6, `nohup` background start, owner-only secrets (mode 600), the
STARTTLS test email, graceful stop, backup/restore, update and uninstall. Separately, the
systemd user service: `jtrac service install`, `systemctl --user start jtrac` (healthy,
database started), `systemctl --user stop jtrac` (graceful shutdown logged, database stopped,
unit `inactive`), `jtrac service remove`.

Running on Linux found two bugs that no Windows test could, both fixed before the final
build: the port check refused a port whose old connections were still in `TIME_WAIT` after a
stop (so `start` right after `stop` failed), and a `systemctl stop` marked the unit `failed`
because the JVM exits with 143 after a SIGTERM shutdown (`SuccessExitStatus=143`).

## What was verified on Windows x64

`tools/installer-smoke.py` on the final archive (see `testing.md`): unpack; setup with `PATH`
stripped and no `JAVA_HOME`; new empty managed database; first administrator; configured HTTP
port; owner-only secret files; SMTP STARTTLS + authentication + test email against a
controlled server; web UI served; sign-in, terms acceptance, organization, project, issue,
comment; test email from Server settings; password never returned or logged; `status`,
`stop`, `start`, `restart`, `logs`, `test-email`; graceful stop; verified backup, restore
(data written after the backup disappears); update from a second copy keeping settings,
secrets and data; uninstall keeping data; `--delete-data` only with typed confirmation.
The automatic start was verified separately (`service install`, running the Startup script,
`service remove`).
