# ADR-LFC-008 — ProcessDurableTaskRuntime is the canonical subprocess engine

**Status:** proposed

## Context

Multiple process algorithms risk inconsistent output, cancellation and memory behavior.

## Decision

All shell/git/tool subprocess execution ultimately goes through the durable process runtime via `ProcessService`. Direct `ProcessBuilder` use is restricted to the canonical adapter.

## Consequences

One implementation owns process-tree lifecycle, timeout, stdout/stderr draining and durable result semantics.
