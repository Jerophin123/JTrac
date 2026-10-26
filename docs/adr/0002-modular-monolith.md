# ADR 0002: Modular monolith with verified module boundaries

- Status: Accepted
- Date: 2026-10-24

## Context

JTAC needs clear domain boundaries (identity, organizations, projects, workflow, custom
fields, issues, collaboration, attachments, search, notifications, reporting,
integrations, administration, audit) without the operational cost of distributed
services before scale requires it.

## Decision

- One deployable Spring Boot application (`backend/`), package `com.jtac`.
- Each direct sub-package of `com.jtac` is an **application module**. Types in a
  module's base package are its public API; sub-packages (e.g. `internal`) are private.
- Boundaries are verified by **Spring Modulith** in `ModularityTests`
  (`ApplicationModules.verify()`): no cycles, no access to another module's internals.
  The test runs in `./gradlew test` and therefore in every build.
- `shared` is an `OPEN` module for cross-cutting infrastructure only (identifiers,
  problem responses, request correlation, migration settings, SPA delivery). It must
  not depend on a business module or contain business rules.
- Modules are added **only when a milestone needs them**. M1 contains `shared` and
  `identity`. Planned modules and their dependency direction are listed in
  `docs/architecture/overview.md`.
- Cross-module communication: direct calls to another module's public API for queries;
  application events (Spring Modulith event publication registry) for side effects
  such as notifications, search indexing and webhooks, introduced when first needed.

## Consequences

- Module diagrams are generated at `backend/build/spring-modulith-docs/`.
- Extracting a module into a service later is possible because its API surface is explicit.
- Authorization is centralized per module service, never in controllers.

## Alternatives considered

- **Microservices**: rejected for now. No scale or team-topology driver; would add
  distributed transactions and operational overhead.
- **Unchecked package-by-feature**: rejected, because boundaries erode without verification.
- **ArchUnit-only rules**: viable, but Modulith provides module semantics, docs and
  event infrastructure from one dependency.
