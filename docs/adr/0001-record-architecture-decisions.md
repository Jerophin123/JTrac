# ADR 0001: Record architecture decisions

- Status: Accepted
- Date: 2026-10-24

## Context

JTAC is a greenfield rebuild informed by the reverse-engineered JTrac 2.3.3
(`../../JTRAC-REVERSE-ENGINEERING-REPORT.md`, project skill `.claude/skills/jtrac`).
Many decisions (versions, security model, data ownership) must stay traceable as the
platform grows.

## Decision

Significant decisions are recorded as numbered ADRs in `docs/adr/`, using the sections
*Context, Decision, Consequences, Alternatives considered*. An ADR is never edited to
change its meaning; it is superseded by a new ADR that links back to it.

A decision is "significant" when it affects module boundaries, the public API, persisted
data, security, a dependency or runtime version, or the developer workflow.

## Consequences

- Reviews can point at the ADR that justifies a change.
- Version upgrades update ADR 0003 and `docs/dependencies.md` in the same change.
