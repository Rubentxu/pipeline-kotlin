# ADR-LFC-001 — V2 local-first is the active product

**Status:** proposed

## Context

The repository contains valuable V2 local runtime work while older V1 and future remote/controller concepts remain visible. This creates scope ambiguity and encourages architecture to optimize for an unimplemented distributed future.

## Decision

V2 local-first execution is the only active product critical path until the local foundation and 1.0 gates close. Protocol/controller/Jenkins runtime integration is deferred and removed from the active build where unconsumed. V1 is quarantined as legacy/history rather than repaired on the V2 critical path.

## Consequences

The project becomes easier to reason about and release. Future distributed work must reuse stable local contracts. Some speculative code may be deleted and recovered later from Git history if needed.

## Rejected/Deferred alternatives

Maintaining remote protocol work in parallel; continuing to repair V1 and V2 simultaneously.
