# Contributing to JTrac

Thanks for wanting to make JTrac better. This guide gets you from clone to merged pull request.

## Ground rules

- Be kind: the [Code of Conduct](CODE_OF_CONDUCT.md) applies everywhere.
- Contributions are licensed under the Apache License 2.0 ([TERMS.md](TERMS.md) section 7).
- Security issues go through [SECURITY.md](SECURITY.md), never public issues.
- One topic per pull request; small, reviewable commits with clear messages
  (`feat(mail): ...`, `fix(linux): ...`).

## Development setup

| Tool | Version |
|---|---|
| JDK | 25 (Temurin recommended), as `JAVA_HOME` |
| Node.js / pnpm | 24.15+ / 12 (via `corepack enable`) |
| PostgreSQL | 18, for integration tests (`jtac_test` database, see below) |

```bash
git clone https://github.com/Jerophin123/JTrac.git && cd JTrac
cd frontend && pnpm install --frozen-lockfile && cd ..
./gradlew test                     # unit, modularity and API-contract tests (no database)
./gradlew :serverconfig:test :launcher:test
cd frontend && pnpm typecheck && pnpm lint && pnpm test
```

Integration tests need a PostgreSQL 18 database `jtac_test` with roles `jtac_owner` and
`jtac_app` provisioned as in [ADR 0004](docs/adr/0004-database-roles-and-migrations.md) (the
CI workflow `.github/workflows/ci.yml` contains the exact SQL); then `./gradlew build`.

## Project rules (they keep JTrac secure and maintainable)

1. **Module boundaries** (ADR 0002): only a module's base package is public; `internal`
   packages are private. `ModularityTests` must stay green.
2. **No business logic or authorization in controllers.** Services own rules, transactions and
   access checks. Frontend visibility is never authorization.
3. **Schema changes** are new Flyway migrations (`backend/src/main/resources/db/migration`);
   never edit an applied migration. The application only validates the schema.
4. **API first**: update `api/openapi.yaml`, then the code; errors are RFC 9457 problems; run
   `pnpm gen:api` for the typed client. `OpenApiContractTests` must pass.
5. **Secrets** never go into the repository, arguments, logs or test output.
6. **Frontend**: semantic design tokens only, CSS Modules, lucide icons, logical CSS properties
   (RTL), WCAG 2.1 AA contrast in light and dark themes.
7. **Tests are evidence**: never weaken assertions or skip tests to get green.

## Pull requests

- Describe the change, why, and how you tested it.
- Add or update tests and documentation (`docs/`, `README.md`) in the same pull request.
- CI must pass. Maintainers may ask for changes; that's normal and friendly.

## Reporting bugs and ideas

Use the issue templates. For bugs include version, platform, steps, expected vs actual, and
relevant log lines from `jtrac logs` (remove personal data first).
