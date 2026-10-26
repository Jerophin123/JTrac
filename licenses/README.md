# Third-party software in the JTRAC package

| Component | Where in the package | License | Notices |
|---|---|---|---|
| Java runtime (Eclipse Temurin / OpenJDK 25, linked with `jlink`) | `runtime/` | GPL v2 with the Classpath Exception | `runtime/legal/` (one folder per module) and `runtime/release` |
| PostgreSQL 18 server binaries (managed-database targets only) | `pgsql/` | PostgreSQL License | `PostgreSQL-LICENSE.txt`; libraries it links (OpenSSL, ICU, zlib, libxml2, ...) keep their own licenses |
| PostgreSQL binary packaging (io.zonky.test.postgres:embedded-postgres-binaries) | `pgsql/` | Apache License 2.0 | |
| Server and launcher libraries (Spring, Hibernate, PostgreSQL JDBC, Jakarta Mail, ...) | `lib/` | various, mostly Apache 2.0 | `THIRD-PARTY-JAVA.md`, `sbom-server.json`, `sbom-launcher.json` |
| Web UI libraries (React, TanStack, lucide, ...) | inside `lib/jtrac-server.jar` | various, mostly MIT | `THIRD-PARTY-WEB.md` |

The generated files (`THIRD-PARTY-*.md`, `sbom-*.json`) are produced by the release build from
the resolved dependencies; see `docs/license-notices.md` in the source repository.
