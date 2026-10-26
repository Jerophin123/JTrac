# ADR 0008: Identifiers and database collation

- Status: Accepted
- Date: 2026-10-24

## Context

Identifiers must be safe to expose, index-friendly and generatable by the application
before insert. Sorting must behave identically on Windows (development) and Linux
(CI/production).

## Decision

- **Primary keys are UUID version 7** (RFC 9562): time-ordered, generated in the
  application by `com.jtac.shared.Ids` and defaulting to PostgreSQL 18's `uuidv7()` in
  DDL. Authorization never relies on identifiers being unguessable.
- Human-readable keys (e.g. `PROJ-123`, inherited from JTrac's space prefix + sequence)
  are separate columns introduced with projects/issues.
- **Databases use the built-in `C.UTF-8` locale provider** (PostgreSQL 17+), so collation
  does not depend on the operating system's libc/ICU and cannot change silently with
  OS upgrades. User-facing linguistic sorting, where needed, will use explicit ICU
  collations on specific columns.
- Email uniqueness is case-insensitive through a generated column
  `email_normalized = lower(btrim(email))` with a unique index (verified: PostgreSQL 18
  `lower()` handles non-ASCII under `C.UTF-8`).

## Consequences

- `ORDER BY` on text is code-point order unless a column specifies a collation.
- No extension (e.g. `citext`) is required.
