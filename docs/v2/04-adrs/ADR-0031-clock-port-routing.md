# ADR-0031: Clock port routing in pipeline-application

- **Status:** Accepted for M3-R4.1
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.1 Implementation:** T-07 (E4-16)

## Context

The `:pipeline-application` module had 23 call sites directly invoking `Instant.now()` to timestamp events. This bypassed the `Clock` port established in ADR-0028, creating a systemic debt issue where deterministic testing was compromised and clock-dependent behavior could not be controlled.

The challenge was to route all time-source calls through the `Clock` port without breaking existing code paths or introducing API incompatibilities.

## Decision

Add `Clock` as a constructor parameter to all functions and helper methods that emit timestamped events, with `SystemClock()` as the default value for backward compatibility.

### Modified Functions

**Top-level execution functions:**
- `execute(scriptPath, store, clock)` — added clock parameter with `SystemClock()` default
- `walkPipelineSpec(...)` — added clock parameter with `SystemClock()` default

**Durable execution:**
- `walkPipelineSpecDurable(...)` — already had clock; no change needed
- `emitDurableStepEvents(...)` — already had clock; no change needed
- `executeDurableStep(...)` — added clock parameter with `SystemClock()` default

**Event emission helpers:**
- `emitStepEvents(...)` — added clock parameter with `SystemClock()` default
- `emitParallelStepEvents(...)` — added clock parameter with `SystemClock()` default
- `emitParallelBranchEvents(...)` — added clock parameter with `SystemClock()` default
- `emitStepFinished(...)` — added clock parameter with `SystemClock()` default
- `emitSleepStepEvents(...)` — added clock parameter with `SystemClock()` default
- `emitErrorStepEvents(...)` — added clock parameter with `SystemClock()` default
- `emitAgentResolvedEvent(...)` — added clock parameter with `SystemClock()` default
- `emitRetryAttemptEvents(...)` — added clock parameter with `SystemClock()` default
- `emitTimeoutScheduledEvent(...)` — added clock parameter with `SystemClock()` default

**Orchestrator:**
- `PipelineOrchestrator.run()` — uses injected clock for RunStarted/RunFinished

### Backward Compatibility

All functions use `SystemClock()` as the default, preserving existing call sites without requiring changes. The durable execution path already injects `clock`, so no change needed there.

## Alternatives Considered

1. **Global static clock** — rejected; creates hidden dependency that's hard to override in tests.

2. **Thread-local clock** — rejected; adds complexity without benefit over constructor injection.

3. **Clock as implicit context** — rejected; violates explicit dependency injection principle.

## Consequences

- All timestamped events now route through the `Clock` port
- Deterministic testing is possible by injecting a controlled clock implementation
- Existing code paths continue to work unchanged due to default parameter values
- The 23 `Instant.now()` bypass sites are eliminated

## Evidence and Provenance

- E4-16 criterion from ROADMAP.md §E4-16
- ADR-0028 documents the Clock port design
- ClockContractTest validates clock routing in emitStepEvents
- PipelineOrchestrator uses injected clock for all RunStarted/RunFinished events
