# JTAC: engineering instructions

JTAC is a **greenfield** project-management / issue-tracking platform: Spring Boot 4.1
modular monolith on JDK 25 (`backend/`), React + TypeScript SPA (`frontend/`),
PostgreSQL 18. Start with `README.md`, `docs/architecture/overview.md` and `docs/adr/`.

## Always consult the JTrac skill for domain questions

Load the project skill **`jtrac`** (`../.claude/skills/jtrac/`) before designing or
changing domain behaviour (workflows, transitions, field permissions, custom fields,
history, permissions, search, attachments, notifications) and read its
**"Greenfield rebuild mode"** section: it states which legacy rules apply to JTAC and
which only apply to the legacy WAR. Its references are the verified record of JTrac's
behaviour; treat anything not listed there as unverified.

## Non-negotiables

1. **Inspect before changing.** Verify that a file, class, endpoint or library exists;
   never assume. Never modify the legacy JTrac 2.3.3 distribution or the reverse-engineering report.
2. **Module boundaries** (ADR 0002): each direct sub-package of `com.jtac` is a module;
   only its base package is public; `internal` sub-packages are private. Run
   `./gradlew test` (includes `ModularityTests`) after structural changes. Add a module
   only when a milestone needs it. `shared` holds infrastructure, never business rules.
3. **No business logic or authorization in controllers.** Services own rules,
   transactions and access checks; frontend visibility is never authorization.
4. **Database** (ADR 0004): the app runs as `jtac_app` (DML only) and only *validates*
   the schema. Schema changes are new Flyway files in
   `backend/src/main/resources/db/migration` (never edit an applied migration), applied
   with `./gradlew migrateDevDb` / `migrateTestDb` as `jtac_owner`. Never drop, truncate
   or reset `jtac_dev`/`jtac_test`; never create roles or weaken privileges to make
   tests pass: propose the SQL and its impact instead.
5. **Secrets**: never put passwords in tracked files, command arguments, logs, test
   output or docs. Credentials come from pgpass or environment variables. Don't print
   `pgpass.conf`.
6. **API** (ADR 0006): update `api/openapi.yaml` first; endpoints live under `/api/v1`;
   errors are RFC 9457 problems with a `urn:jtac:problem:*` type via
   `GlobalProblemHandler` (or an `ApiProblemException` subclass). Regenerate the client
   with `pnpm gen:api`. `OpenApiContractTests` must stay green.
7. **Security** (ADR 0005): session cookie + CSRF; never store tokens or credentials in
   `localStorage`/`sessionStorage`; keep the CSP free of inline scripts/styles.
8. **Frontend** (ADR 0007): consume semantic tokens from `src/styles/tokens.css`, CSS
   Modules only, lucide icons, logical CSS properties (RTL). Every token pair must keep
   ≥ 4.5:1 contrast in light and dark. No mock data presented as real; label any
   placeholder explicitly.
9. **Dependencies** (ADR 0003): verify compatibility from official sources and peer
   ranges before adding or upgrading; pin exact frontend versions; update ADR 0003 and
   `docs/dependencies.md` in the same change.
10. **Tests are evidence.** Never weaken assertions, skip, or mock away the behaviour
    under test. Report only checks that actually ran. Do not commit automatically.

## Verification commands

```powershell
.\gradlew.bat build                                   # backend: unit, modules, migrate jtac_test, integration, contract
cd frontend; pnpm typecheck; pnpm lint; pnpm test; pnpm build
# e2e: see README.md "Single-origin build and E2E"
```

## Conventions

- Java: records for DTOs, constructor injection, package-private by default,
  `-Werror` compile; UUIDv7 ids via `com.jtac.shared.Ids`.
- Integration tests: annotate with `@DatabaseIntegrationTest`; create uniquely named
  data and delete it afterwards.
- Frontend: features own their queries/mutations (`src/features/<name>`); shared
  primitives live in `src/ui`; API access only through `src/api/client.ts`.
- Commits: small, reviewable; ADR for any significant decision.
