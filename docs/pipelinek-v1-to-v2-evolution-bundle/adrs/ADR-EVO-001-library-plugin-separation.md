# ADR-EVO-001 — Shared Library and Plugin are distinct artifact kinds

**Status:** Proposed

## Context

Both libraries and plugins may need to be visible to script compilation, which makes it tempting to model both as generic JARs.

## Decision

Treat them as distinct typed artifact kinds.

- Library = authoring dependency; no runtime contributors.
- Plugin = runtime extension; may register Steps/directives/events/capabilities through published seams.

Reject library artifacts containing plugin contributor service declarations.

## Consequences

Positive: clear security/replay boundary, predictable tooling, no hidden effects.  
Negative: two manifest schemas and two catalogue projections are required.  
Rejected alternative: one generic extension JAR with optional behaviours, because it makes effect authority ambiguous.
