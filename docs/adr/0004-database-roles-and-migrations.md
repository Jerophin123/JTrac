# ADR 0004: Database roles, migrations and credentials

- Status: Accepted
- Date: 2026-10-24

## Context

The local PostgreSQL 18.6 cluster (D: drive, loopback only, SCRAM-SHA-256) was
provisioned with:

- `jtac_owner`: owns databases `jtac_dev`, `jtac_test` and schema `jtac` in each;
  not superuser, cannot create databases or roles.
- `jtac_app`: login role with `USAGE` on schema `jtac` and, through default
  privileges defined *for role jtac_owner*, `SELECT/INSERT/UPDATE/DELETE` on tables,
  `USAGE/SELECT` on sequences and `EXECUTE` on functions. No `CREATE`, no `TEMP`.
- `search_path = jtac` for both roles in both databases. `CONNECT` on `postgres`
  revoked from `PUBLIC`.

Changing only the connection user does not solve schema ownership: objects must be
created by `jtac_owner` for the default privileges to apply.

## Decision

1. **The application never migrates.** At startup it connects as `jtac_app` and runs
   Flyway **validate** only (`FlywayValidationConfiguration`); a schema that is behind
   or diverges from the build fails startup with an actionable message.
2. **Migrations run as `jtac_owner` in a separate JVM** (`com.jtac.DatabaseMigrations`,
   no Spring context) through Gradle tasks: `migrateDevDb`, `migrateTestDb`,
   `validateDevDb`, `infoDevDb`. Owner credentials therefore never appear in the
   runtime configuration. `integrationTest` depends on `migrateTestDb`.
3. **One source of Flyway settings** (`MigrationSettings`) shared by both paths:
   schema `jtac`, `createSchemas=false` (schema provisioned by the DBA),
   `cleanDisabled=true` (no `clean` against any JTAC database), placeholder
   `${appRole}`.
4. **Migration history is read-only for the app:** V1 revokes `INSERT/UPDATE/DELETE/
   TRUNCATE` on `flyway_schema_history` from `${appRole}` (`SELECT` remains for
   validation). Verified by `PlatformIntegrationTests` (SQLSTATE 42501).
5. **Credentials are never stored in the repository, arguments, logs or docs.**
   The PostgreSQL JDBC driver reads `%APPDATA%\postgresql\pgpass.conf` when no
   password is configured; `SPRING_DATASOURCE_PASSWORD` / `JTAC_MIGRATION_PASSWORD`
   environment variables are the alternative for other environments.
6. **No Docker.** Integration tests use the existing `jtac_test` database; tests create
   uniquely named rows and delete them; they never drop, truncate or reset databases.
   Testcontainers is not used.

## Consequences

- Deployments run the migration task (or equivalent job) with owner credentials before
  rolling out the application.
- Adding privileges for `jtac_app` requires an explicit ADR-reviewed migration; tests
  guard against schema-changing privileges.
- Developers need a pgpass entry (or environment variables) for both roles.

## Alternatives considered

- Running Flyway inside the app as `jtac_owner`: rejected, because it puts owner credentials in the
  runtime and gives DDL rights to the serving process.
- Spring profile "migrate" of the full application: rejected, because it starts the whole
  context with elevated credentials.
- Flyway Gradle plugin: viable, but duplicates configuration that the app also needs
  for validation; the shared `MigrationSettings` keeps one definition.
