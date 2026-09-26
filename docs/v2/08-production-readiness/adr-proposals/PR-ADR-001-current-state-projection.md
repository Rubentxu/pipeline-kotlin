# PR-ADR-001 — Materialized Current-State Projection

**Status:** PROPOSED

## Context

The audit found multiple state-bearing documents with different timestamps and incompatible claims. Historical receipts are valuable and should remain immutable, but they are not an appropriate current-state database.

## Decision

Introduce one generated current-state projection.

Inputs:
- Git HEAD;
- release/candidate identity;
- UAT receipts;
- external harness verdict;
- open blocking defects;
- PR reconciliation data.

Output:
- deterministic Markdown/YAML committed or generated as defined by the repository policy.

Historical receipts and `WORK_JOURNAL` remain append-only evidence.

## Consequences

Positive:
- agents resume from a machine-checkable state;
- stale SHA references become detectable;
- historical evidence is no longer overloaded as current status.

Negative:
- generator maintenance is required;
- precedence rules must be explicit.

## Acceptance

- same inputs -> same digest;
- conflicting candidate SHAs -> fail;
- generated state embeds source identities.
