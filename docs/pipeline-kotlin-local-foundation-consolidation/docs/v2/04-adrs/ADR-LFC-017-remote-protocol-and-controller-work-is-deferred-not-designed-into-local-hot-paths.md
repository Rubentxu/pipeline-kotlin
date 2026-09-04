# ADR-LFC-017 — Remote protocol and controller work is deferred, not designed into local hot paths

**Status:** proposed

## Context

Remote protocol concepts can force IDs, serialization and lifecycle decisions before local semantics are proven.

## Decision

Archive/defer protocol/controller modules and ADRs from active milestones. Preserve only local contracts that are independently useful. Reopen remote execution after 1.0 via new spikes/ADRs.

## Consequences

Local architecture can stabilize without speculative network complexity. Some future remote requirements may require versioned extensions, which is acceptable.
