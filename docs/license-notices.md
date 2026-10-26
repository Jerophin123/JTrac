# License notices

JTRAC packages redistribute third-party software. The package's `licenses/` folder contains:

| File | Contents | Produced by |
|---|---|---|
| `README.md` | overview of bundled components and their licenses | `licenses/README.md` in the repository |
| `PostgreSQL-LICENSE.txt` | PostgreSQL License (managed-database targets) | repository |
| `THIRD-PARTY-JAVA.md` | every Java library in the server and launcher, with versions and licenses | release build, from the CycloneDX SBOMs |
| `THIRD-PARTY-WEB.md` | every production npm package bundled into the web UI | release build, from `pnpm licenses list --prod` |
| `sbom-server.json`, `sbom-launcher.json` | CycloneDX 1.6 SBOMs of the runtime dependencies | CycloneDX Gradle plugin 3.4.1 |

Also included in every package:

- `runtime/legal/` and `runtime/release`: the OpenJDK (Eclipse Temurin 25) license (GPL v2 with
  Classpath Exception) and per-module third-party notices, added by `jlink`.
- `pgsql/share/`: PostgreSQL's own files. The binaries come from
  `io.zonky.test.postgres:embedded-postgres-binaries` (Apache 2.0 packaging) and link
  libraries such as OpenSSL, ICU, zlib and libxml2 under their respective licenses.

Licenses observed in the 3.0.0 dependency tree include Apache-2.0, MIT, BSD-2/3-Clause,
EPL-2.0 (some dual-licensed with LGPL-2.1 or GPL-2.0 with Classpath Exception), ISC, 0BSD,
Unlicense and OFL-1.1 (fonts). No GPL-only library is linked into the server.

The license of JTRAC's own source code is set by its maintainers; it is not asserted here.
