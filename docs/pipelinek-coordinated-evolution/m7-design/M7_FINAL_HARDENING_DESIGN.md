# CRIC-M7 — FINAL HARDENING design

**Status:** design, ready for implementation.
**Worktree:** `pk-cric-m7` (branch `impl/cric-m7-final-hardening`) at `a5deb128` (`audit/cric-m7-final-hardening`).
**Anchor:** the M7 audit at `M7_FINAL_HARDENING_AUDIT.md` and the normative text in `coordination/INTERFACE_CONTRACT.md` §1..§12.
**Precedent:** `m6-design/M6_CONTEXT_PRESSURE_DESIGN.md` — same composition discipline, applied to the final block of CRIC guarantees.

This document is the design. It is the input to the M7 implementation in
`pk-cric-m7`. The audit is at `M7_FINAL_HARDENING_AUDIT.md`; this
design consumes §C / §D from that audit and names the API shape.

## 0. Scope and naming

The M7 audit's decision (§D): `BLOCK 7 = PK-CANDIDATE NEEDED (small)`.
M7 closes the M6 I.6 PARTIAL with a typed `EventShapeReadPort` on
`:pipeline-events` (event-driven grouping replaces synthetic
grouping), hardens three M6 seams (B.1 wall-time budget,
B.2 `UnknownOperation` refusal case, B.3 8-case `OperationStatus` set
pin), and produces `v0.54.0-rc1` as the final PK candidate.

The design answers, in order:

1. The new port's signature and the typed-event ADT (§A).
2. The new `IntrospectionRefusal` case (§B).
3. The `runStructure` adapter change (synthetic → event-driven) (§C).
4. The new wall-time budget constant (§D).
5. The capability registration and contract change (§E).
6. The implementation phasing (§F).

## A. The `EventShapeReadPort` and typed events

### A.1 The port

```kotlin
fun interface EventShapeReadPort {
    fun eventShapesForRun(runId: String, options: EventShapeOptions = EventShapeOptions.Default): EventShapeResult

    companion object {
        const val DEFAULT_EVENT_SHAPE_TIMEOUT_MS: Long = 5_000L
    }
}
```

`EventShapeReadPort` is a `fun interface` (SAM) so a consumer can
substitute it with a lambda in tests. The single method
`eventShapesForRun` returns the typed events the journal recorded for
the run, in execution order. The audit's §C names this shape; the
design names the concrete Kotlin.

### A.2 The typed event ADT

```kotlin
sealed interface TypedEvent {
    val runId: String
    val occurredAtMs: Long
}

data class StageStartedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stageIndex: String,
    val stageName: String,
) : TypedEvent

data class StageFinishedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stageIndex: String,
    val outcome: String,
) : TypedEvent

data class StageSkippedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stageName: String,
    val reason: String,
) : TypedEvent

data class StepStartedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stepKey: String,
    val stepName: String,
) : TypedEvent

data class StepFinishedTyped(
    override val runId: String,
    override val occurredAtMs: Long,
    val stageOrdinal: Int,
    val stepKey: String,
    val outcome: String,
) : TypedEvent

// Closed ADT — the contract test pinna the 5-case set.
```

The five cases are the typed events the M6 audit names as the source
of truth for stage / step boundaries. The sealed interface is closed
(a sixth case would be a breaking change requiring consumer
recompile); the contract test pinna the 5-case set per the M7 audit's
B.3 hardening item.

### A.3 The result envelope

```kotlin
sealed interface EventShapeResult {
    data class Events(val events: List<TypedEvent>) : EventShapeResult
    data object Empty : EventShapeResult
    data class Refused(val refusal: EventShapeRefusal) : EventShapeResult
}

sealed interface EventShapeRefusal {
    data class UnknownRun(val runId: String) : EventShapeRefusal
    data object NoEventStore : EventShapeRefusal
    data class StorageError(val cause: String) : EventShapeRefusal
}

data class EventShapeOptions(
    val pageSize: Int = DEFAULT_EVENT_SHAPE_PAGE_SIZE,
    val cursor: EventShapeCursor? = null,
) {
    init {
        require(pageSize in 1..MAX_EVENT_SHAPE_PAGE_SIZE) {
            "pageSize must be in [1, $MAX_EVENT_SHAPE_PAGE_SIZE], got $pageSize"
        }
    }

    companion object {
        const val DEFAULT_EVENT_SHAPE_PAGE_SIZE: Int = 128
        const val MAX_EVENT_SHAPE_PAGE_SIZE: Int = 4096
        val Default: EventShapeOptions = EventShapeOptions()
    }
}

data class EventShapeCursor(
    val eventOrdinal: Int,
) {
    init { require(eventOrdinal >= 0) { "eventOrdinal must be >= 0, got $eventOrdinal" } }
}
```

The result mirrors `RunStructureResult` (M6): a sealed envelope with
`Events` / `Empty` / `Refused` cases. The pagination is bounded
(per-call `pageSize`) with a resume cursor (per-run
`eventOrdinal`). The same rules the M6 audit names for
`runStructure` apply: forward-only, opaque cursor, no backpressure on
the writer.

## B. The `IntrospectionRefusal.UnknownOperation` case

```kotlin
sealed interface IntrospectionRefusal {
    // ... existing cases preserved ...
    data class UnknownOperation(val operationId: String, val attempt: Int) : IntrospectionRefusal
}
```

The new case is additive on the existing `IntrospectionRefusal` ADT;
no breaking change. The M7 audit's B.2 hardening item. The
`inspectOperation` adapter uses this case when the journal has no row
for `(opId, attempt)`; the existing `StorageError` form is preserved
for OTHER refusal modes (e.g. journal load failure).

The contract test `IntrospectionUnknownOperationRefusalTest` pinna
the new case: 3 cases covering the unknown (opId, attempt), the
existing opId with a different attempt, and the new case's
`operationId` / `attempt` fields.

## C. The `runStructure` adapter change

The M6 first cut's `runStructure` adapter used synthetic
single-stage / single-step grouping because the
`PipelineEventEnvelope` strip the type-specific fields of the typed
events. The M7 design replaces this with event-driven grouping
composed of:

1. The new `EventShapeReadPort` to read the typed events.
2. The existing `OperationJournal.listForRun` to read the operations.
3. The existing `OutputFrameIndex.streamsOfRun` to project the
   declared streams per operation.

The grouping is:

```
For each operation, find the most recent StageFinishedTyped event
  whose stageOrdinal matches a StageStartedTyped for the same
  ordinal, and whose occurredAtMs is <= the operation's startedAtMs.
  The operation belongs to that stage.
For each operation in a stage, find the most recent StepFinishedTyped
  event whose stageOrdinal matches the stage and whose occurredAtMs is
  <= the operation's startedAtMs. The operation belongs to that step.
```

When NO stage / step events exist for a run, the adapter falls back
to synthetic single-stage / single-step grouping (the M6 first cut's
default). The M6 PARTIAL closure is therefore: "event-driven grouping
when events exist; synthetic grouping as the fallback when they
don't". Both are tested; the M6 first-cut test rows are REPLACED with
the event-driven rows, the fallback rows are NEW.

The M6 contract test `RuntimeIntrospectionPortStructureAdapterTest`
gets an extension: the existing 8 cases stay (the synthetic fallback
rows are still pinned), and 4 new rows pin the event-driven
grouping. The test class name + structure stays the same.

## D. The new wall-time budget constant

```kotlin
companion object {
    const val DEFAULT_INSPECT_TIMEOUT_MS: Long = 5_000L       // existing
    const val DEFAULT_STRUCTURE_TIMEOUT_MS: Long = 10_000L   // new in M7
}
```

The new constant is added to the existing `RuntimeIntrospectionPort`
companion. The `runStructure` adapter honours it (a paginated read
that exceeds the budget returns a typed `Refused(StorageError("..."))`,
not an exception). The contract test pinna the budget via a
synthetic slow adapter.

## E. The capability registration and contract change

### E.1 The new capability

```kotlin
object Capabilities {
    // ... existing 10 capabilities preserved ...
    val RUNTIME_EVENT_SHAPE_V1: Capability = Capability("runtime.eventshape.v1")
}
```

The M7 audit's §D names `runtime.eventshape.v1` as the new
capability ID. The capability is `EXPERIMENTAL` in `v0.54.0-rc1`;
promotion to `PUBLICADA` happens after the certifier's `CERTIFIED`
verdict.

### E.2 The contract change

The `INTERFACE_CONTRACT.md` §6 block extends with a CRIC-M7
paragraph; the "Capacidades publicadas (CRIC-M7)" table is added
at the end. `CONTRACT_SHA256.txt` is refreshed.

The `CapabilityRegistrationTest` extends:
- A new test that pins the 11th capability (and the
  `RuntimeIntrospectionPortStructureAdapterTest` event-driven
  rows are added to the must-mention list).
- A new test that pins the `CRIC-M7 audit table names the
  certifying tests` rule (mirrors the M6 row).

The total `CapabilityRegistrationTest` case count grows from 6
(M6) to 8 (M7).

## F. Implementation phasing

The M7 implementation ships in 5 commits on
`impl/cric-m7-final-hardening`:

1. **M7.1 (single commit):** add the new types in `:pipeline-events`
   (`EventShapeReadPort`, `EventShapeResult`, `EventShapeRefusal`,
   `EventShapeOptions`, `EventShapeCursor`, `TypedEvent` + 5 cases).
   The port method throws `NotImplementedError` in a stub adapter so
   the build stays green; the M6 PARTIAL closure is wired in M7.3.
2. **M7.2 (single commit):** the M7.1 stub adapter gets a real
   implementation over the existing `EventSink` (the typed event
   decoder is the source of truth for the `stageIndex` / `stepKey`
   fields the M6 audit said the envelope strips). Plus
   `EventShapeReadPortAdapterTest` (6+ cases, real event store).
3. **M7.3 (single commit):** the M6 `runStructure` adapter is
   rewritten to use the new `EventShapeReadPort` for event-driven
   grouping. The synthetic-grouping test rows in
   `RuntimeIntrospectionPortStructureAdapterTest` are replaced with
   event-driven rows; the synthetic fallback is added as a separate
   test method. The M6 I.6 PARTIAL row closes.
4. **M7.4 (single commit):** add the `UnknownOperation` case to
   `IntrospectionRefusal`; update the `inspectOperation` adapter to
   use it; add `IntrospectionUnknownOperationRefusalTest` (3 cases).
   Plus add `DEFAULT_STRUCTURE_TIMEOUT_MS` to the
   `RuntimeIntrospectionPort` companion and pin the budget in
   `RuntimeIntrospectionPortStructureAdapterTest`.
5. **M7.5 (single commit):** register the new capability
   (`runtime.eventshape.v1`) in the `Capabilities` companion; update
   `INTERFACE_CONTRACT.md`; refresh `CONTRACT_SHA256.txt`; bump
   `0.53.0 → 0.54.0` (CRIC-M7 train open); extend
   `CapabilityRegistrationTest` to 8 cases.
6. **M7.6 (single commit):** `release-receipt` for the M7 candidate
   (`v0.54.0-rc1`), then `PK_CONTRACT_HANDOFF` to Fabric, then a
   final "chore(release): bump 0.53.0 -> 0.54.0 (CRIC-M7 train
   open)" — mirroring the M6 release shape.

The M7.6 closeout is the final closeout of the CRIC-1 chain. After
it, the chain has 11 capabilities, 0 PARTIAL rows, and is ready for
the `PAIR_CERTIFIED` with Fabric.

---

**Design author:** PipelineK runtime author (authority for
`:pipeline-runtime` and `:pipeline-events`).
**Design date:** 2026-10-11.
**Audit consumed:** `M7_FINAL_HARDENING_AUDIT.md` (audit `a5deb128`,
246 lines, sections A-G, decision §D "BLOCK 7 = PK-CANDIDATE
NEEDED (small)").
**Status:** ready for implementation in `pk-cric-m7-impl`.
