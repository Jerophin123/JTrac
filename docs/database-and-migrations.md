# Database and migrations

JTRAC requires **PostgreSQL 18** (it uses `uuidv7()` and the built-in `C.UTF-8` collation
provider). No other database is supported or substituted.

## Roles (least privilege, ADR 0004)

| Role (fresh installs) | Development name | Rights |
|---|---|---|
| `jtrac_owner` | `jtac_owner` | owns database `jtrac` and schema `jtac`; runs migrations; not superuser |
| `jtrac_app` | `jtac_app` | `USAGE` on `jtac`, `SELECT/INSERT/UPDATE/DELETE` on tables, `USAGE/SELECT` on sequences, `EXECUTE` on functions, via default privileges of the owner; no `CREATE`, no `TEMP`; read-only on `flyway_schema_history` |

The schema keeps its historical name `jtac` for compatibility with existing databases and
migrations. The running server connects only as the application role and **validates** the
schema at startup; it never migrates.

## Fresh database provisioning

Setup always starts from an **empty** database:

- **Managed**: `initdb` creates a new cluster (`--locale-provider=builtin
  --builtin-locale=C.UTF-8 --data-checksums --auth=scram-sha-256`) in `<data>/pgsql/data`,
  listening on `127.0.0.1` only, with no Unix socket and loopback-only `pg_hba.conf`. Random
  32-character passwords are generated for the superuser, owner and application roles.
- **External**: setup provisions with an administrator account entered once (not stored), or
  prints the script (`DatabaseSetup.provisioningScript`) for a DBA.

Then setup verifies that the owner owns the schema and that the application role can't
create objects or hold administrative rights, applies migrations, and refuses to continue if
the database already contains JTRAC accounts. Development and test databases (`jtac_dev`,
`jtac_test`) are never touched by packaging or installation.

## Migrations

Versioned Flyway scripts in `backend/src/main/resources/db/migration` (copied into each
package under `migrations/` for review). They run as the owner in a separate JVM
(`com.jtac.DatabaseMigrations`), with the password passed through the environment:

| Context | Command |
|---|---|
| Installed server | `jtrac migrate` (also run by setup, `restore` and `update`) |
| Development | `./gradlew migrateDevDb` / `migrateTestDb` / `validateDevDb` / `infoDevDb` |

Rules: never edit an applied migration; add a new `V<n>__description.sql`; `clean` is disabled
for every database.

| Version | Content |
|---|---|
| V1 | identity: user accounts |
| V2 | sessions, events, email outbox, audit log, account tokens |
| V3 | organizations, memberships, invitations, teams |
| V4 | projects, workflows, fields |
| V5 | issues and collaboration |
| V6 | notifications, saved views, dashboards, webhooks |
| V7 | server administrators |
| V8 | consent records (DPDP) |
