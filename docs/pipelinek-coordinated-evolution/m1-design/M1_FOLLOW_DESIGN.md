# CRIC-M1 — `output.follow.v1` and `events.follow.v1` design ADR (REVISION 2)

**Status:** PROPOSED. Revision 2 reflects five corrections applied on
2026-10-10 after the operator review of revision 1 (`e070d79a`):
1. The event follow cannot compose `EventTail.readAfter()` because that
   port returns `EventPage(envelopes: List<PipelineEventEnvelope>, ...)` —
   wire envelopes, not typed `DomainEvent`. A new public read-only port
   that combines pagination with typed `DomainEvent` + `Undecodable` rows
   is required. Backed by `EventStore.readRecords()` semantics, no
   `append` / `appendAssigned` exposed.
2. `pipeline-output` and `pipeline-events` do not depend on
   `kotlinx-coroutines` and must not introduce it. The published follow
   contract is a **pull API** (an iterator-style handle), not a
   `Flow`. Coroutines, if used, are an application-layer adapter
   (`pipeline-application`), not a published contract.
3. The UAT traceability was wrong. The IDs `UAT-PK-001` / `-003` /
   `-004` / `-007` are owned by the Fabric coordinated-evolution
   matrix (`docs/fabric-coordinated-evolution/acceptance/UAT.md` in
   the Fabric repo) and have specific meanings there. This ADR
   renames the M1-specific tests to avoid the collision.
4. Three decisions in revision 1 were premature and are removed:
   `@PublishedApi` was offered as a runtime capability discovery
   mechanism (it is not; the user-facing capability IDs are a static
   published object or a string in this ADR, not annotated members).
   `UAT-PK-M1-007` tried to enforce equality between event sequence
   and output frame ordinal; the two are distinct authorities and
   are not equal by construction. A 1 s `LagExceeded` refusal was
   introduced as if a slow reader were defective; it is not, and the
   threshold is a configurable report, not a refusal.
5. A real proof of M1 must cross the JVM boundary: another process
   observes events and output while PK continues to execute. The
   e2e test is `M1-D` in §10 below.

**Worktree:** `pk-cric-m1` (branch `feat/cric-m1-output-events-live`) at `91579c66`.
**Anchor:** the audit `M1_OUTPUT_EVENTS_AUDIT.md` in this directory.

## 0. Re-use, do not duplicate

The audit (`M1_OUTPUT_EVENTS_AUDIT.md` Q1, Q3, Q5) shows that every
primitive the M1 contract needs exists somewhere in this repo. The
corrections in this revision sharpen the rule:

| TRACK B requirement | Existing primitive (re-use) | What changes for M1 |
|---|---|---|
| Lectura incremental de output, con offsets estables | `OutputReadPort`, `OutputCursor`, `OutputPage` (output/OutputReadPort.kt:19-44, OutputCursor.kt:57-105) | none — wrap in a pull-style follow handle |
| Estado `Open` / `Sealed` per stream | `OutputTailState.Open(committedEnd) / Sealed(finalEnd)` (output/OutputTailState.kt:42-69) | none — surface as `FollowState.StreamSealed` |
| Límites de página | `OutputPage(bytes, stream, from, next, committedEnd)` (OutputCursor.kt:121-157) | none |
| Reanudación | `OutputCursor.encode/decode` wire token `out-cursor-v1:` (OutputCursor.kt:77-104) | none |
| Lectura incremental de **typed** DomainEvents con cursor | `EventStore.readRecords()` (events/EventStore.kt:146-164) returns `EventRecordSlice(records, nextCursor, hasMore)` with typed `DomainEvent` + `Undecodable` rows | **NEW public read-only port** that exposes this through the published module without the write side. The `append` / `appendAssigned` methods are not exposed. |
| Refusals (eventos) | `EventRecordRead.Undecodable(sequence, kind, eventId, reason)` (events/EventRecordRead.kt:67-102) | none — surfaced inside the typed page |
| Refusals (output) | `OutputRefusal.{OffsetBeyondCommitted, RecoveryNotCompleted, DanglingCommit}` (output/OutputRefusal.kt:14-60) | extend with follow-specific cases (e.g. `StreamLostRetention`); never duplicate as a new hierarchy |
| Lectores independientes y concurrentes | `OutputFrameIndex.framesOfRun(runId, afterOrdinal, limit)` is read-only and side-effect-free | none — the new follow reads the same `framesOfRun` and the same `OutputReadPort` from each consumer's process |
| Identidades coherentes entre output y eventos | `runId: String` (both planes) + `OutputCursor` + `EventCursor` | none — keep `String` for the public ports; typed `RunId` is a future M-block concern |
| Preservación de bytes / UTF-8 fragmentado / stdout vs stderr | `OutputStreamAddress(runId, operationId, channel)`; channel enum `STDOUT/STDERR`; byte offsets absolute per stream | none — UTF-8 is a stream-bytes concern, not a follow-cursor concern; the contract guarantees that no read truncates inside a multi-byte codepoint at the byte-offset level (a codepoint may straddle two pages, but the consumer sees the bytes in order and the codepoint is reconstructed by the consumer from the byte stream) |
| Backpressure acotado | `LiveOutputDrain(reader, frameLimit, pollIntervalMs)` (application/observation/LiveOutputDrain.kt:50-133); `EventPageDrain.drain` (application/observation/EventPageDrain.kt:51-144) | none — the new follow uses the same `pageMaxBytes` / `maxRecords` knobs |
| Cancelación limpia | `LiveOutputDrainResult.Stopped` | the new follow is a pull iterator: consumer stops calling `next()` and the implementation cleans up the store handles via a `close()` on the follow handle. No thread is owned by the contract |
| No esperar al terminal para entregar bytes/eventos | `ObservationOutputFollower.replay` (application/observation/ObservationOutputFollower.kt:34-117); `FollowDecision.ReadAgain/Finished` (observation/ObservationWakeup.kt:121-128) | none — the new follow emits `Bytes` / `Page` events as soon as committed bytes are observable, regardless of run terminality |

## 1. Public capability identifiers (first-of-kind, statically published)

The audit (Q7) confirms there is no public capability registry. The
new identifiers are:

- `output.follow.v1` — incremental live tail of stdout/stderr from a
  running run, exposed by `pipeline-output`.
- `events.follow.v1` — incremental live tail of `DomainEvent` from a
  running run, exposed by `pipeline-events`.

These are static strings published in a `Capabilities` object per
module (one in `:pipeline-output`, one in `:pipeline-events`). They
are NOT discovered through `@PublishedApi` (that annotation is for
inline-function visibility, not runtime capability advertisement).
The M1 first cut advertises them as `EXPERIMENTAL`; promotion to
`STABLE` happens only after a candidate is `CERTIFIED` against the
M1 contract test suite.

## 2. New public types — pull API, no `Flow`, no `RunId` from `pipeline-domain`

### 2.1 `output.follow.v1` — in `v2/pipeline-output/`

The contract is an **iterator-style handle**: a `OutputFollowHandle` is
returned from `OutputFollower.open(...)`, and the consumer calls
`handle.iterator()` to obtain a `Iterator<OutputFollowEvent>`. The
handle is closed by the consumer (try-with-resources via `use { }`).
No `Flow`, no `suspend`, no `kotlinx-coroutines` on the published
classpath. `runId: String` (not typed `RunId`).

```kotlin
// New file: v2/pipeline-output/src/main/kotlin/.../output/follow/OutputFollower.kt

interface OutputFollower {
    fun open(runId: String, options: OutputFollowOptions): OutputFollowHandle
}

interface OutputFollowHandle : AutoCloseable {
    fun iterator(): Iterator<OutputFollowEvent>
    override fun close()
}

data class OutputFollowOptions(
    val streams: List<OutputStreamAddress> = emptyList(),
    val pageMaxBytes: Int = 64 * 1024,
    val pollIntervalMs: Long = 25L,
    val includeFrames: Boolean = true,
    val maxRecords: Int = 256,
    val until: FollowUntil = FollowUntil.Unbounded,
)

sealed interface FollowUntil {
    data object Unbounded : FollowUntil
    data class UntilAllSealed(val runId: String) : FollowUntil
    data class UntilBytesRead(val limit: Long) : FollowUntil
}

sealed interface OutputFollowEvent {
    data class Bytes(val page: OutputPage, val newState: FollowState) : OutputFollowEvent
    data class StateChanged(val state: FollowState) : OutputFollowEvent
    data class Refused(val refusal: OutputRefusal) : OutputFollowEvent
    data object Completed : OutputFollowEvent
}

sealed interface FollowState {
    data class Running(val openStreams: List<OutputStreamId>, val sealedStreams: List<OutputStreamId>) : FollowState
    data class StreamSealed(val sealedStreams: List<OutputStreamId>) : FollowState
    data object RunTerminal : FollowState
    data class Unobservable(val refusal: OutputRefusal) : FollowState
}
```

The three terminal-state values are **deliberately distinct**:
- `FollowState.StreamSealed(sealedStreams)` — every declared stream is
  sealed, but the run is not yet observed as terminal in the event
  plane. The Output Plane alone does not authoritatively know the run
  is over (cf. `OutputTailState` KDoc: "Putting an outcome here would
  make the Output Plane a second authority over execution results").
- `FollowState.RunTerminal` — only emitted after the event plane
  observes a `RunFinished` event. **Only the event-plane observation
  is authoritative for run terminality.** The Output follow learns of
  terminality by joining the event plane; the join happens in the
  application-layer adapter (`pipeline-application`/`observation/`).
- `FollowState.Unobservable(refusal)` — the follow could not
  continue. The refusal is from the existing `OutputRefusal`
  hierarchy; follow-specific cases are added as new sealed cases
  (§3 below).

### 2.2 `events.follow.v1` — in `v2/pipeline-events/`

Same shape, iterator-style, no coroutines.

```kotlin
// New file: v2/pipeline-events/src/main/kotlin/.../events/follow/EventFollower.kt

interface EventFollower {
    fun open(runId: String, options: EventFollowOptions): EventFollowHandle
}

interface EventFollowHandle : AutoCloseable {
    fun iterator(): Iterator<EventFollowEvent>
    override fun close()
}

data class EventFollowOptions(
    val query: EventQuery = EventQuery.All,
    val pollIntervalMs: Long = 25L,
    val maxRecords: Int = 256,
    val until: EventFollowUntil = EventFollowUntil.Unbounded,
    /** Reporting-only threshold; see §3.3. NOT a refusal trigger. */
    val lagReportInterval: Duration = Duration.ofSeconds(1),
)

sealed interface EventFollowUntil {
    data object Unbounded : EventFollowUntil
    data class UntilRunFinished(val runId: String) : EventFollowUntil
    data class UntilSequence(val lastSequence: Long) : EventFollowUntil
}

sealed interface EventFollowEvent {
    data class Page(val slice: EventRecordSlice, val newState: EventFollowState) : EventFollowEvent
    data class StateChanged(val state: EventFollowState) : EventFollowEvent
    data class Refused(val refusal: EventFollowRefusal) : EventFollowEvent
    data object Completed : EventFollowEvent
}

sealed interface EventFollowState {
    data class Live(val lastSequence: Long) : EventFollowState
    data object RunFinished : EventFollowState        // DomainEvent.kind == "RunFinished" observed
    data class Unobservable(val refusal: EventFollowRefusal) : EventFollowState
}

sealed interface EventFollowRefusal {
    data class UnknownRun(val runId: String) : EventFollowRefusal
    data class RetentionLost(val runId: String, val lastSeenSequence: Long) : EventFollowRefusal
    data object Cancelled : EventFollowRefusal
}
```

Note: `RunFinished` carries no `finalSequence` — the `finalSequence` is
the last `EventRecordSlice.nextCursor` observed in the last `Page`
event before the `StateChanged(RunFinished)`. Asking for a separate
`finalSequence` field is a denormalisation that the consumer can
re-derive from the cursor.

### 2.3 New event-typed read-only port (the M1-A block)

`EventTail.readAfter()` returns `EventPage(envelopes: List<PipelineEventEnvelope>, ...)`,
i.e. wire envelopes, NOT the typed `DomainEvent` payload that Fabric
needs. The M1 design therefore introduces a **new public read-only
port** in `pipeline-events`:

```kotlin
// New file: v2/pipeline-events/src/main/kotlin/.../events/identity/EventRecordReadPort.kt
// (sibling to EventStore; only the read side is exposed)

interface EventRecordReadPort {
    fun readRecords(
        runId: String,
        after: EventCursor?,
        query: EventQuery,
        limit: Int,
    ): EventRecordReadResult
}

sealed interface EventRecordReadResult {
    data class Page(val slice: EventRecordSlice) : EventRecordReadResult
    data class Refused(val refusal: EventRecordReadRefusal) : EventRecordReadResult
}

sealed interface EventRecordReadRefusal {
    data class UnknownRun(val runId: String) : EventRecordReadRefusal
    data class CursorBeyondTail(val runId: String, val requestedSequence: Long, val tailSequence: Long) : EventRecordReadRefusal
    data class StorageError(val cause: String) : EventRecordReadRefusal
}
```

The implementation is the existing `EventStore.readRecords()` (which
already returns `EventRecordSlice`), wrapped behind a new public port
that does NOT expose `append` / `appendAssigned`. The port is added
to the existing `maven-publish` `sdk` publication
(`pipeline-events/build.gradle.kts:62-78`).

## 3. Refusal extension policy

### 3.1 Output Plane

`OutputRefusal` (output/OutputRefusal.kt:14-60) gains two new sealed
cases for follow-specific conditions:

```kotlin
sealed interface OutputRefusal {
    // ... existing cases ...
    data class StreamLostRetention(val stream: OutputStreamId, val lastCommitted: Long) : OutputRefusal
    data class FollowCancelled(val handle: OutputFollowHandle) : OutputRefusal
}
```

The new cases extend the existing hierarchy; they do NOT introduce a
parallel refusal type.

### 3.2 Event Plane

The existing `EventRecordRead.Undecodable` is the source of truth for
row-level refusals and is surfaced inside `EventRecordSlice.refusals`.
Follow-level refusals are added to a new `EventFollowRefusal`
hierarchy that mirrors the Output convention. No `Either`/`Result`.

### 3.3 Lag is not a defect

A slow consumer is not a defect. `EventFollowOptions.lagReportInterval`
controls **how often** the follow reports the observed lag (via a
`LagObserved` event) but does NOT raise a refusal. The previous
`EventFollowRefusal.LagExceeded` is removed. A consumer that wants
to abort on its own threshold checks the lag itself in the
`LagObserved` handler.

## 4. Composition contract (the rule the implementations MUST obey)

```text
OutputFollower.open(runId, opts).iterator() :
  initial: emit StateChanged(Running) with all declared streams
  poll loop:
    for each declared stream: OutputTailPort.tailState(stream)
      Open    -> running
      Sealed  -> sealed
      null    -> OutputRefusal.UnknownStream -> emit Refused and stop
    for each open stream: OutputFrameIndex.framesOfRun(runId, afterOrdinal, limit)
      for each new frame: OutputReadPort.read(stream, cursor, pageMaxBytes) -> OutputPage
        emit Bytes(page, newState)
    if all sealed and until reached: emit StateChanged(RunTerminal) and Completed
    else sleep(pollIntervalMs)

EventFollower.open(runId, opts).iterator() :
  initial: emit StateChanged(Live(0))
  poll loop:
    EventRecordReadPort.readRecords(runId, cursor, query, maxRecords) -> EventRecordReadResult
      Page     -> emit Page(slice, newState)
                 if slice contains RunFinished: emit StateChanged(RunFinished) and Completed
      Refused  -> emit Refused and stop
    if until reached: emit Completed
    else sleep(pollIntervalMs)
```

The Output follow learns of `RunTerminal` by joining the event plane
in the application-layer adapter (`pipeline-application/observation/`).
The event follow is the source of truth for terminality; the Output
follow is the source of truth for byte delivery.

## 5. UAT traceability (corrected)

The IDs `UAT-PK-001` / `-003` / `-004` / `-007` belong to the Fabric
coordinated-evolution matrix (`docs/fabric-coordinated-evolution/acceptance/UAT.md`
in the Fabric repo). The M1 design **does not reuse** those IDs; it
introduces `UAT-PK-M1-001` through `UAT-PK-M1-006` (one less than
revision 1; the UAT-PK-M1-007 "cross-plane sequence = frame ordinal"
assertion is removed because the two authorities are not equal by
construction).

| M1 UAT | What it proves | Where it lives |
|---|---|---|
| UAT-PK-M1-001 | End-to-end live tail across the JVM boundary — a second process reads `output.follow.v1` and `events.follow.v1` while PK is still running more stages, with cursors preserved across the boundary. | `M1-D` cross-JVM e2e test, executed in this repo against the real `SegmentOutputStore` + `EventHistoryReader`, using a separate `ProcessBuilder` for the consumer. |
| UAT-PK-M1-002 | Incremental follow across stages — a run with multiple stages; the consumer reads across stage boundaries without dropping bytes or events. | `M1-C` composition test, in-process. |
| UAT-PK-M1-003 | Cancellation — a follow in progress is closed via `handle.close()`; the iterator stops emitting, no half-page delivered, no store-side thread leak. | `M1-C` composition test, in-process. |
| UAT-PK-M1-004 | Retention boundary — a stream whose bytes are pruned between two polls surfaces `OutputRefusal.StreamLostRetention` (or `EventRecordReadPort.StorageError` for events); the cursor position is preserved and the last-known extent is reported. | `M1-C` composition test, against the real `SegmentOutputStore` with retention calls. |
| UAT-PK-M1-005 | UTF-8 split across pages — a step emits a multi-byte codepoint split across two `OutputAppendPort` calls; the consumer sees the bytes in order and reconstructs the codepoint from the byte stream. No truncation, no invented re-ordering. | `M1-C` composition test, against the real `SegmentOutputStore`. |
| UAT-PK-M1-006 | Slow consumer is not a defect — a consumer that polls slower than the producer writes sees `EventFollowEvent.LagObserved` reports at the configured interval; the follow does NOT refuse on its own. The consumer decides whether to abort. | `M1-C` composition test, in-process. |

The M1 UATs do not collide with the Fabric UAT matrix. The
UAT-PK-001/-003/-004/-007 in the Fabric matrix remain the authority
for live-tail correctness; this repo's M1 UATs are the
implementation-side tests that prove the M1 contract satisfies them.

## 6. Push / wakeup transport

Out of scope for the first cut. The vocabulary (`ObservationWakeup`)
exists but no real emitter is wired in this repo. The M1 first cut is
polling. A future non-breaking addition can swap the implementation
to honour `ObservationWakeup`; the public types do not change.

## 7. Cross-plane identity

`runId: String` for both published ports. `OutputCursor` for output.
`EventCursor` for events. No typed `RunId` / `OperationId` introduced
in `pipeline-output` or `pipeline-events`. The audit (Q4) lists this
as a future M-block concern.

## 8. Refusal of premature publication

The capability IDs are advertised as `EXPERIMENTAL` only after the
implementation lands and at least UAT-PK-M1-001, UAT-PK-M1-002,
UAT-PK-M1-003 are green in the cross-JVM e2e test. Until then, a
consumer that probes for these IDs sees
`OutputRefusal.UnknownStream` / `EventRecordReadRefusal.UnknownRun`
and falls back to the existing request/response `output.read.v1` /
`event.read.v1` reads, per the `INTERFACE_CONTRACT.md` rule
("ausencia de `output.follow.v1` requiere fallback anunciado o
refusal tipado, nunca un falso LIVE").

## 9. Application-layer adapter

The Flow-based shape from revision 1 is moved to the application
layer as a coroutine adapter, not a published contract:

```kotlin
// New file: v2/pipeline-application/src/main/kotlin/.../application/observation/follow/
//   OutputFollowFlowAdapter.kt and EventFollowFlowAdapter.kt
//
// These wrap OutputFollowHandle / EventFollowHandle in a Flow for
// Kotlin consumers, using a coroutine to drive the iterator.
```

`ObservationOutputFollower.replay` and `EventPageDrain.drain`
(application/observation/) are refactored to use the new public
ports; the existing `pipeline observe --follow` and `pipeline run`
behaviour is preserved.

## 10. Implementation plan (in dependency order)

This revision reorders the work as the user requested. Each block is
a work-unit commit on `feat/cric-m1-output-events-live`; no
cross-block code is mixed.

### Block M1-A — typed-events read port

- New file `v2/pipeline-events/.../events/identity/EventRecordReadPort.kt`
  with the interface in §2.3.
- Add the new port to the `sdk` publication in
  `pipeline-events/build.gradle.kts:62-78`.
- Surgical test
  `v2/pipeline-events/src/test/kotlin/.../events/identity/EventRecordReadPortTest.kt`:
  1. round-trips a typed `DomainEvent` (e.g. `RunStarted`) and
     asserts the typed payload, not a `PipelineEventEnvelope`,
  2. round-trips an undecodable row and asserts
     `EventRecordRead.Undecodable(sequence, kind, eventId, reason)`
     is preserved,
  3. asserts `EventRecordReadRefusal.UnknownRun` for an unknown run,
  4. asserts `EventRecordReadRefusal.CursorBeyondTail` for an
     out-of-range cursor.

### Block M1-B — output follow port

- New files `v2/pipeline-output/.../output/follow/OutputFollower.kt`
  with the iterator-style public types in §2.1.
- Add `OutputRefusal.StreamLostRetention` and
  `OutputRefusal.FollowCancelled` to the existing
  `OutputRefusal` hierarchy.
- Surgical test
  `v2/pipeline-output/src/test/kotlin/.../output/follow/OutputFollowerContractTest.kt`:
  1. `Open` stream → `FollowState.Running` and a `Bytes` event with
     the page,
  2. `Sealed` stream + non-terminal run → `FollowState.StreamSealed`,
  3. `Sealed` stream + `RunFinished` observed in the event plane
     → `FollowState.RunTerminal` then `Completed`,
  4. unknown stream → `Refused(OutputRefusal.UnknownStream)`,
  5. `close()` mid-iteration → iterator returns no further events,
  6. UAT-PK-M1-005 (UTF-8 split across pages) is a contract test on
     the byte stream, not the follow cursor.

### Block M1-C — event follow port

- New files `v2/pipeline-events/.../events/follow/EventFollower.kt`
  with the iterator-style public types in §2.2.
- Add `EventFollowRefusal.{UnknownRun, RetentionLost, Cancelled}`.
- Surgical test
  `v2/pipeline-events/src/test/kotlin/.../events/follow/EventFollowerContractTest.kt`:
  1. typed events delivered, refusal rows preserved inside the page,
  2. `RunFinished` event → `StateChanged(RunFinished)` then
     `Completed`,
  3. unknown run → `Refused(EventFollowRefusal.UnknownRun)`,
  4. `close()` mid-iteration → no further events,
  5. UAT-PK-M1-006 (slow consumer, no refusal).

### Block M1-D — cross-JVM e2e + example consumer

- New `examples/output-events-follow-consumer/` consumer that
  imports the published `pipeline-output` and `pipeline-events`
  artifacts and consumes `output.follow.v1` and `events.follow.v1`
  in a separate `Process` (the M1-D test runner spawns it as a
  child process).
- E2E test that:
  1. starts a PK run that emits stages over a few seconds,
  2. spawns the consumer as a child process with
     `ProcessBuilder`,
  3. asserts the consumer sees `StageStarted` events and
     `Bytes` events before the run reaches terminal state
     (UAT-PK-M1-001),
  4. closes the consumer mid-run and asserts the PK run is not
     interrupted (UAT-PK-M1-002 / -003 mapped to a single
     cross-JVM assertion),
  5. asserts UTF-8 codepoints are preserved across pages
     (UAT-PK-M1-005).
- Full gate at the end of the block
  (`:pipeline-output:test :pipeline-events:test
  :pipeline-application:test` on this branch; no `./gradlew check`
  that would compete with the RC3 certifier).

### Block M1-E — capability registration

- `Capabilities.kt` in `:pipeline-output` and `:pipeline-events`
  exposing the new capability IDs as static strings + a
  `capabilityRegistry(): List<Capability>` helper for the
  application layer to query.
- Cross-reference in
  `coordination/INTERFACE_CONTRACT.md` line 6: the `output.follow.v1`
  reference changes from "absent" to "EXPERIMENTAL in
  pipeline-output 0.48.0-rc3+ and pipeline-events 0.48.0-rc3+;
  promoted to STABLE after first M1-certified candidate".

## 11. Open questions (held for the certifier and Fabric)

- Q1 (certifier): does the existing `wu-rp-053-workspace-contract`
  exercise a follow-style read? If not, the M1 work proposes
  UAT-PK-M1-001 as an addition to the certifier's battery (the
  e2e test in `M1-D` is the producer-side counterpart).
- Q2 (Fabric): the example consumer at
  `examples/fabric-contract-consumer/` currently uses
  `pipeline-events` only. Will the M1 follow contracts be added to
  the same example, or is a new `examples/output-events-follow-consumer/`
  needed? Default in this design: separate example, M1-D.
- Q3 (cross-repo contract): the `INTERFACE_CONTRACT.md` reference
  to `output.follow.v1` (line 6) and the analogous `events.follow.v1`
  need to be promoted from "absent" to "EXPERIMENTAL" with the
  same wording Fabric uses on its side. This promotion happens
  once Q1 and Q2 are answered and the M1-D e2e test is green — not
  unilaterally here.
- Q4 (M2 / M3): the user has authorised opening investigations and
  tests of M2 (`inspect`/`recover`/`cancel` audit) and M3
  (retention/range invariants) in independent branches. Those
  are out of scope for the M1 work; this ADR does not introduce
  any M2 or M3 contract.
