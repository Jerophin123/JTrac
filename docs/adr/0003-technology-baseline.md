# ADR 0003: Technology baseline and verified versions

- Status: Accepted
- Date: 2026-10-24 (versions verified on this date)

## Context

The baseline is JDK 25. Every framework, build tool and library must be a supported
release whose compatibility with JDK 25 and with each other is verified from official
sources: not chosen merely because it is the newest.

## Decision

### Backend

| Component | Version | Verification |
|---|---|---|
| JDK | Temurin **25.0.4.1 LTS** (local install) | `java -version` on the dev machine |
| Spring Boot | **4.1.2** | Official system requirements: "requires at least Java 17 and is compatible with versions up to and including Java 27"; Gradle "8.x (8.14 or later) and 9.x": https://docs.spring.io/spring-boot/system-requirements.html |
| Spring Framework / Security | 7.0.10 / 7.1.2 (managed by Boot) | Boot 4.1.2 BOM `spring-boot-dependencies-4.1.2.pom` |
| Spring Modulith | **2.1.2** | Boot 4.1 release highlights list Modulith 2.1 (https://spring.io/projects/release-highlights/); `spring-modulith-starter-test-2.1.2.pom` depends on `spring-boot-starter-test` **4.1.2** (Maven Central) |
| Gradle (wrapper) | **9.8.2**, `distributionSha256Sum` pinned | Gradle compatibility matrix: Java 25 supported for running Gradle and toolchains since 9.1.0; JVM 17–27 can run Gradle: https://docs.gradle.org/current/userguide/compatibility.html; checksum from https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256 |
| Hibernate ORM | 7.4.13.Final (managed) | Boot 4.1.2 BOM |
| Flyway | 12.4.0 + `flyway-database-postgresql` (managed) | Boot 4.1.2 BOM; Redgate PostgreSQL page lists PostgreSQL 18 as a verified version: https://documentation.red-gate.com/fd/postgresql-database-277579325.html |
| PostgreSQL JDBC | 42.7.14 (managed) | Boot 4.1.2 BOM; pgpass support verified empirically (class `org.postgresql.jdbcurlresolver.PgPassParser`, migration authenticated with no configured password) |
| PostgreSQL server | **18.6** (local service `postgresql-x64-18`) | https://www.postgresql.org/support/versioning/ (supported until 2030-11-14) |
| JUnit / AssertJ / Testcontainers | 6.0.3 / 3.27.7 / 2.0.5 (managed; Testcontainers unused: no Docker, see ADR 0004) | Boot 4.1.2 BOM |

Spring Boot is applied as a Gradle **platform** (`platform(libs.spring.boot.bom)`), not
via the dependency-management plugin. Versions live in `gradle/libs.versions.toml`.

### Frontend

Chosen as the **newest versions whose peer ranges are mutually compatible** (checked
with `npm view <pkg> peerDependencies`, then `pnpm peers check` → no issues):

| Package | Version | Note |
|---|---|---|
| Node.js / pnpm | 24.21.0 / 12.10.1 | dev machine |
| React / React DOM | 19.3.1 | |
| TypeScript | **5.9.3** | *Not* 7.0.2: `typescript-eslint@8.72.0` requires `typescript <6.1.0` and `openapi-typescript@7.13.0` requires `^5.x` |
| ESLint / @eslint/js | **9.39.5** | *Not* 10.x: `eslint-plugin-jsx-a11y@6.10.2` supports ESLint ≤ 9 |
| Vite / @vitejs/plugin-react | 8.3.5 / 6.1.2 | plugin peer `vite ^8` |
| Vitest / jsdom | 5.0.5 / 30.1.3 | Node ≥ 24.15 required by jsdom 30 |
| TanStack Router / Query | 1.170.46 / 5.104.5 | |
| openapi-fetch / openapi-typescript | 0.17.0 / 7.13.0 | typed client from `api/openapi.yaml` |
| react-hook-form / @hookform/resolvers / zod | 7.91.0 / 6.0.0 / 4.6.7 | resolvers peer `zod ^4` |
| Playwright / @axe-core/playwright | 1.64.1 / 4.14.0 | uses installed Microsoft Edge (`channel: msedge`) |

Exact versions are pinned in `frontend/package.json`; `pnpm-lock.yaml` is committed.

### Landing page (`website/`, 2026-10-27)

A separate static site, not part of the server bundle: Next.js `output: 'export'`, no
server code, no API calls. Pinned in `website/package.json`; `pnpm-lock.yaml` committed.

| Package | Version | Note |
|---|---|---|
| Next.js / eslint-config-next | 16.4.3 | latest; peers `react ^19`, Node ≥ 20.9 |
| React / React DOM | 19.3.1 | same as the SPA |
| Tailwind CSS / @tailwindcss/postcss | 4.3.3 | utilities over the product tokens (`app/product-tokens.css`) |
| motion | 14.6.0 | peers `react ^18 \|\| ^19`; honours reduced motion via `MotionConfig` |
| lucide-react | 1.61.0 | same as the SPA |
| @fontsource/ibm-plex-sans (dev) | 5.3.0 | static WOFF for the build-time share image |
| TypeScript / ESLint | 5.9.3 / 9.39.5 | kept equal to the SPA (see above) |

The site reuses the SPA's design tokens and CSS Modules (copied with source notes into
`website/components/product/styles/`); change them in `frontend/` first, then re-copy.

Release packaging (ADR 0009) adds the CycloneDX Gradle plugin 3.4.1 and the zonky
PostgreSQL 18.6.0 binaries; versions and checksums are recorded in `docs/dependencies.md`.

## Consequences

- Upgrading any listed component requires re-verifying compatibility and updating this
  ADR and `docs/dependencies.md`.
- Revisit TypeScript 7 / ESLint 10 when typescript-eslint, openapi-typescript and
  jsx-a11y declare support.
- Spring Boot 4.1.x OSS support ends before 4.2.x; plan the upgrade with the next minor.

## Alternatives considered

- Spring Boot 4.0.x: supported, but 4.1 is the current line with a longer support window.
- Maven: viable; Gradle Kotlin DSL chosen for the version catalog, configuration cache
  and first-class task composition (migration and admin tasks).
