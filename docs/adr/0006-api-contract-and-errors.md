# ADR 0006: Contract-first API and RFC 9457 errors

- Status: Accepted
- Date: 2026-10-24

## Context

The frontend, tests and future integrations need one authoritative API description, and
consistent, safe error responses.

## Decision

- **Contract first:** `api/openapi.yaml` (OpenAPI 3.1) is the source of truth.
  - The frontend generates types from it (`pnpm gen:api` → `src/api/schema.d.ts`,
    committed; `pnpm check:api` detects drift) and calls the API through `openapi-fetch`.
  - `OpenApiContractTests` fails the build if a documented `/api/v1` operation is not
    implemented or an implemented one is not documented.
- **Versioning:** all endpoints live under `/api/v1`. Breaking changes require `/api/v2`.
  Health probes are unversioned under `/actuator/health/{liveness,readiness}`.
- **Errors:** every error is `application/problem+json` (RFC 9457) produced centrally by
  `GlobalProblemHandler`, including Spring Security failures (routed through
  `ProblemSecurityHandlers`). Each problem has a stable `type` URN
  (`urn:jtac:problem:<name>`), `title`, `status`, `detail`, and `requestId` (also in the
  `X-Request-Id` header). Validation failures add `errors[{field,message}]`; rate
  limits add `retryAfterSeconds`. Stack traces, SQL and parser internals are never
  returned.
- Clients branch on `type`, never on `title`/`detail`.

## Consequences

- Adding an endpoint = update `openapi.yaml`, implement, regenerate the client; the
  contract test enforces the first two.
- Problem types are part of the public contract; renaming one is a breaking change.

## Alternatives considered

- Code-first spec generation (springdoc): deferred, because it avoids a dependency whose Boot 4.1
  compatibility would need separate verification, and keeps the contract reviewable.
