# Build and release

## Build a package for this machine

Prerequisites on the **build** machine only: JDK 25 (`JAVA_HOME`), Node.js 24 with pnpm 12
(corepack), and network access to Maven Central (dependencies and PostgreSQL binaries).

```powershell
cd frontend; pnpm install --frozen-lockfile; pnpm build; cd ..
./gradlew -PwithFrontend :distribution:release
```

Result in `distribution/releases/<version>/`:

- `jtrac-<version>-<target>.zip` (Windows) or `.tar.gz` (Linux, macOS)
- `jtrac-<version>-<target>.<ext>.sha256`

The target defaults to the build machine; pass `-Ptarget=linux-x64|linux-arm64|macos-x64|macos-arm64`
to **cross-build** from any OS. Cross-builds link the runtime with the build JDK's `jlink`
against the target's Temurin `jmods` (same release as the build JDK, 25.0.4.1+1; URLs and
SHA-256 pinned in `distribution/runtime-jmods.properties`). Unix archives are written by the
build itself (`UnixTarGz`, Apache Commons Compress) with explicit modes and PostgreSQL's
symlinks taken straight from its `.txz`, so a package built on Windows is identical in layout
to one built on Linux. Windows ARM64 has no pinned jmods yet and still needs a native build.
An existing archive is never overwritten unless `-PoverwriteRelease` is passed.

## What the pipeline does (`distribution/build.gradle.kts`)

| Task | |
|---|---|
| `downloadPostgres` | downloads the zonky PostgreSQL 18.6 binaries for the target; SHA-1 (and, for Windows x64, SHA-256) pinned in `postgres-binaries.properties`; a mismatch fails |
| `extractPostgres` | unpacks them for staging (pure Java, any build OS) |
| `downloadJmods`, `extractJmods` | cross-builds only: the target's Temurin jmods, SHA-256 pinned |
| `runtimeImage` | `jlink --add-modules <list> --strip-debug --no-header-files --no-man-pages --compress=zip-6`; modules derived with `jdeps` over the server and launcher |
| `verifyServerJar` | fails unless the server jar contains the web UI |
| `licenseNotices` | CycloneDX SBOMs (runtime classpath only) for server and launcher, `THIRD-PARTY-JAVA.md`, `THIRD-PARTY-WEB.md` (from `pnpm licenses list --prod`, without local paths) |
| `stageRelease` | assembles the package **only** from: server jar, launcher jars, runtime, PostgreSQL `bin/lib/share`, platform scripts, config templates, `docs/*.md`, migrations, licenses, `VERSION`, `README.txt`, `RELEASE-NOTES.md` |
| `verifyStage` / `verifyArchive` | release gate on the stage and on the **unpacked final archive** (below) |
| `checkUnixArchive` | Unix targets: one top folder, no unsafe paths, executables 0755, symlinks resolve, scripts with shebang and LF, CPU architecture of Java and PostgreSQL binaries |
| `releaseArchive`, `releaseChecksums` | stable file order, no file timestamps; SHA-256 file. Archives are **not** bit-for-bit reproducible yet: the SBOMs embed their generation time. |

### Release gate

Fails the build when any file:

- is not on the allowlist (top-level scripts, `bin/`, `lib/`, `runtime/`, `pgsql/bin|lib|share`,
  `docs/*.md`, `licenses/`, `config-templates/*.example.properties`, `migrations/V*.sql`,
  `README.txt`, `VERSION`, `RELEASE-NOTES.md`);
- matches the denylist: `.env*`, `.git`, `node_modules`, `secrets.properties`,
  `admin-secrets.properties`, a real `jtrac.properties`, `setup-code.txt`, pgpass, keys and
  certificates (`.pem .key .p12 .pfx .crt .csr .jks`, except the runtime's public CA bundle),
  `.pgdump .eml .log .bak .tmp`, PostgreSQL data files (`PG_VERSION`, `postmaster.pid`,
  `base/`, `global/`, `pg_wal/` ...), `jtrac-backup-*.zip`, `data/`, `attachments/`,
  anything named after `jtac_dev`/`jtac_test`;
- contains (text files, and text entries inside jars) a private key, AWS/GitHub/Slack token
  patterns, a non-placeholder password in a properties file, a JDBC URL with a password, or the
  build machine's home folder, workspace path or `user@`.

Negative test (done for 3.0.0): planting a `secrets.properties`, a private key, `.env`, a
backup zip and a workspace path in the stage made `verifyStage` fail with all five reported.

## Checks before publishing

```
python -I tools/inspect-unix-package.py <linux or macOS archive> --modules-from <windows zip>   # independent static check
```

python -I tools/osv-scan.py          # OSV vulnerabilities of all shipped Maven and npm components
python -I tools/installer-smoke.py --archive <archive> --smtp-fixtures serverconfig/build/libs/serverconfig-<v>-test-fixtures.jar
```

(`./gradlew :serverconfig:testFixturesJar` builds the controlled SMTP server used by the smoke test.)

## CI

- `.github/workflows/ci.yml`: backend build and tests against an ephemeral PostgreSQL 18
  service container, `serverconfig` and `launcher` tests, frontend typecheck/lint/test/build.
- `.github/workflows/release.yml`: native matrix (Windows x64/ARM64, Linux x64/ARM64,
  macOS x64/ARM64), each building, verifying and smoke-testing its own package (Windows ARM64:
  build only, no bundled PostgreSQL), OSV scan on Linux x64, artifacts uploaded. Nothing is
  published automatically. These workflows have not run yet (no CI remote); see
  `platform-support.md` for the actual status of each target.

## Versioning

`version` in `gradle.properties` sets the version of every project and package. Tag releases
`v<version>`.
