# ADR-EVO-012 — Conditional compiled-artifact reuse excludes evaluated state

**Status:** Proposed; separate memory and persistence GO decisions.

## Context

Gradle stores real compiled classes and loaded-program caches. The reviewed PipelineK host exposes identity but no persistent artifact lookup/write. Saving APIs alone do not prove reconstruction of all PipelineK entry metadata.

## Decision

Certify v2 identity, phase equivalence and measured value first. Bounded memory reuse requires GO. Persistence separately requires real round-trip, atomic publication, process coordination, corruption/compatibility and installed new-process warm-load tests. Cache compile diagnostics and evaluate with fresh contexts.

Current artifact admission and runtime capabilities remain authoritative on hits. Cache data is user-owned, not a trusted executable exchange. Exclude evaluated IR, script instances, run-bound closures, secrets and execution/recovery results.

## Consequences

NO-GO is valid. Memory cache does not accelerate independent one-shot CLI commands. Persistence uses actual backend representation and cleanup, not a serialized Success flag. The receipt distinguishes memory, persistent and loaded-class reuse.

## Acceptance

Spec 20; UAT-052..059, UAT-062..066 and applicable UAT-081/082 cases. AAT-021/022/023/025/028 cover memory; persistent reuse additionally requires AAT-024. Real invocation counts, never timer-only claims.
