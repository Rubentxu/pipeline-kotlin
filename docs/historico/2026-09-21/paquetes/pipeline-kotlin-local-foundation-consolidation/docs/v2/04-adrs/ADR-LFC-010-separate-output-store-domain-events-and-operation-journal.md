# ADR-LFC-010 — Separate output store, domain events and operation journal

**Status:** proposed

## Context

Large output stored/aggregated as event content violates streaming/memory goals and conflates observability with recovery.

## Decision

Persist stdout/stderr/system in `RunOutputStore`; keep events small/structured; keep replay facts in `OperationJournal`. Events reference output ranges.

## Consequences

Pagination/follow becomes scalable and graph/event stores stay compact.
