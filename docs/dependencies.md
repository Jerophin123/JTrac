# Dependency catalog

The machine-readable catalogs are `gradle/libs.versions.toml` (backend) and
`frontend/package.json` + `frontend/pnpm-lock.yaml` (frontend). This page records **why**
each direct dependency exists and **how its version was verified** (2026-10-24).
Compatibility rationale: ADR 0003.

## Build and runtime

| Item | Version | Source of truth |
|---|---|---|
| JDK | Temurin 25.0.4.1 LTS | local install `D:\Java\jdk-25` |
| Gradle wrapper | 9.8.2 (SHA-256 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`) | https://docs.gradle.org/current/userguide/compatibility.html, https://services.gradle.org/versions/current |
| Node.js / pnpm | 24.21.0 / 12.10.1 | local install |
| PostgreSQL | 18.6 | https://www.postgresql.org/support/versioning/ |

## Backend (direct dependencies)

| Dependency | Version | Purpose |
|---|---|---|
| `spring-boot-dependencies` (platform) | 4.1.2 | manages all Spring/Hibernate/Flyway/driver versions: https://docs.spring.io/spring-boot/system-requirements.html |
| `spring-modulith-bom` (platform) | 2.1.2 | Modulith versions; test starter built against Boot 4.1.2 |
| `spring-modulith-api` | 2.1.2 | `@ApplicationModule` metadata |
| `spring-boot-starter-webmvc` | managed | REST API, static SPA |
| `spring-boot-starter-security` | managed (Security 7.1.2) | session auth, CSRF, headers |
| `spring-boot-starter-validation` | managed | Bean Validation of requests |
| `spring-boot-starter-actuator` | managed | liveness/readiness probes |
| `spring-boot-starter-data-jpa` | managed (Hibernate 7.4.13) | persistence |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | managed (12.4.0) | schema validation (app) and migration (task); PG 18 verified by Redgate docs |
| `postgresql` (runtime) | managed (42.7.14) | JDBC driver; reads pgpass |
| `spring-boot-starter-test`, `-webmvc-test`, `-security-test` | managed | JUnit 6, AssertJ, MockMvc |
| `spring-modulith-starter-test` | 2.1.2 | module verification and docs |
| `angus-mail` (in `serverconfig`) | managed (2.0.5) | SMTP client for delivery and test emails (Jakarta Mail implementation already used by `spring-boot-starter-mail`) |
| `greenmail-junit5` (test) | 2.1.14 | in-process SMTP server for mail integration tests |
| `io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson3` | 2.1.0 (added 2026-10-28) | official MCP Java SDK: stateless Streamable HTTP servlet transport, schema types, tool input validation (ADR 0010). Brings `reactor-core` (Boot-managed 3.8.8) and `com.networknt:json-schema-validator` 3.0.6; its Jackson 3 module uses Boot's `jackson-databind` 3.1.7 (built against 3.1.4). Verified on Maven Central and by integration tests plus an end-to-end run with the official MCP TypeScript SDK 1.32.2 client |

## Packaging (release builds only, 2026-10-26)

| Item | Version | Verification |
|---|---|---|
| CycloneDX Gradle plugin `org.cyclonedx.bom` | 3.4.1 (2026-08-11) | Gradle plugin portal metadata; ran with Gradle 9.8.2 (`cyclonedxDirectBom`, CycloneDX 1.6 output) |
| PostgreSQL binaries `io.zonky.test.postgres:embedded-postgres-binaries-*` | 18.6.0 | Maven Central; SHA-1 pinned per target in `distribution/postgres-binaries.properties` |
| Apache Commons Compress / XZ for Java (build script classpath only, never shipped) | 1.28.0 (2025-07-27) / 1.12 | Maven Central metadata; Unix tar.gz writing with modes and symlinks, reading PostgreSQL `.txz` |
| Temurin 25.0.4.1+1 `jmods` for linux-x64/arm64, macos-x64/arm64 | 25.0.4.1+1 | api.adoptium.net (`image_type=jmods`); SHA-256 pinned in `distribution/runtime-jmods.properties` |
| Java runtime | linked from the build JDK (Temurin 25.0.4.1) with `jlink` | JEP 493 linking from the run-time image works on Temurin 25 without `jmods` (verified locally) |

## Frontend (direct dependencies)

Runtime: `react`/`react-dom` 19.3.1, `@tanstack/react-router` 1.170.46,
`@tanstack/react-query` 5.104.5, `openapi-fetch` 0.17.0, `react-hook-form` 7.91.0,
`@hookform/resolvers` 6.0.0, `zod` 4.6.7, `lucide-react` 1.61.0,
`@fontsource-variable/ibm-plex-sans` 5.3.0, `@fontsource/ibm-plex-mono` 5.3.0.

Development: `typescript` 5.9.3, `vite` 8.3.5, `@vitejs/plugin-react` 6.1.2, `vitest`
5.0.5, `jsdom` 30.1.3, `@testing-library/{react 16.3.3, dom 10.4.2, user-event 14.6.8,
jest-dom 7.0.1}`, `eslint` 9.39.5, `@eslint/js` 9.39.5, `typescript-eslint` 8.72.0,
`eslint-plugin-react-hooks` 7.1.1, `eslint-plugin-jsx-a11y` 6.10.2, `globals` 17.13.0,
`openapi-typescript` 7.13.0, `@playwright/test` 1.64.1, `@axe-core/playwright` 4.14.0,
`@types/{react 19.3.0, react-dom 19.3.0, node 24.19.2}`.

Verification: versions from the npm registry (`npm view`), peer ranges checked
(`npm view <pkg> peerDependencies`), result checked with `pnpm peers check`
→ "No peer dependency issues found". Deliberate holds: TypeScript 5.9 (not 7.0) and
ESLint 9 (not 10): see ADR 0003.

## Landing page (`website/`, verified 2026-10-27)

Static Next.js export; versions from `npm view <pkg> version peerDependencies`.

| Dependency | Version | Purpose |
|---|---|---|
| `next` | 16.4.3 | static export of the landing page (`output: 'export'`) |
| `react`, `react-dom` | 19.3.1 | UI runtime |
| `tailwindcss`, `@tailwindcss/postcss` | 4.3.3 | utility CSS mapped to the product design tokens |
| `motion` | 14.6.0 | entrance, reveal and scroll animations; respects reduced motion |
| `lucide-react` | 1.61.0 | icons (same set as the SPA) |
| `@fontsource-variable/ibm-plex-sans`, `@fontsource/ibm-plex-mono` | 5.3.0 | self-hosted product typefaces |
| `@fontsource/ibm-plex-sans` (dev) | 5.3.0 | static WOFF for the build-time share image (the share-image renderer cannot read the variable WOFF2) |
| `eslint` + `eslint-config-next` (dev) | 9.39.5 + 16.4.3 | lint (Next core-web-vitals + TypeScript rules) |
| `typescript`, `@types/*` (dev) | 5.9.3 | type checking |

`unrs-resolver`'s optional postinstall is declined in `website/pnpm-workspace.yaml`
(`allowBuilds`); its prebuilt platform binary is used.
