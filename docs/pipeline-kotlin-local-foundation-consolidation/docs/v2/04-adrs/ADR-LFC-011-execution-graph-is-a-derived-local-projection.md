# ADR-LFC-011 — Execution graph is a derived local projection

**Status:** proposed

## Context

Graphing is valuable for observability and future control planes but coupling it to execution would add premature database/runtime constraints.

## Decision

Build graph state asynchronously/rebuildably from canonical IR + durable facts. Begin with a simple local projector and portable export formats.

## Consequences

Graph storage can evolve later without changing execution correctness.
