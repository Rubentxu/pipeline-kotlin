# CRIC-M1 — `output.follow.v1` and `events.follow.v1` design ADR

**Status:** PROPOSED (no code yet; contract pinned before implementation).
**Worktree:** `pk-cric-m1` (branch `feat/cric-m1-output-events-live`) at `91579c66`.
**Anchor:** the audit `M1_OUTPUT_EVENTS_AUDIT.md` in this directory.
**Authoritative scope:** this ADR does NOT modify `coordination/INTERFACE_CONTRACT.md`
unilaterally; it proposes the contract additions and refuses the temptation to
duplicate existing read ports.

## 0. Constraint: do not duplicate

The audit (Q1, Q3, Q5) shows that every primitive the user asks for in
TRACK B is already implemented somewhere in this repo:

| TRACK B requirement | Already exists in repo (re-use, do not duplicate) |
|---|---|
| Lectura incremental de output, con offsets estables | `OutputReadPort.read(stream, cursor, maxBytes)` + `OutputCursor(stream, committedOffset)` (output/src/main/kotlin/.../output/OutputReadPort.kt:19-44, OutputCursor.kt:57-105) |
| Estado `Open`/`Sealed` | `OutputTailState.Open(committedEnd) / Sealed(finalEnd)` (output/OutputTailState.kt:42-69) |
| Límites de página | `OutputPage(bytes, stream, from, next, committedEnd)` (OutputCursor.kt:121-157) |
| Reanudación | `OutputCursor.encode/decode` wire token `out-cursor-v1:` (OutputCursor.kt:77-104) |
| Lectura incremental de DomainEvents con cursor estable | `EventTail.readAfter(run, cursor?, limit): EventPage` (events/EventHistoryPorts.kt:159-161) + `EventCursor(runId, lastSequence)` (events/EventHistoryPorts.kt:22-53) |
| Payload tipado | `DomainEvent` sealed hierarchy + `EventRecordSlice` (events/DomainEvent.kt:15-1436, events/EventRecordRead.kt:151-191) |
| Diferenciación EOF / lag / pérdida / corrupción / indisponibilidad | `OutputRefusal.{OffsetBeyondCommitted, RecoveryNotCompleted, DanglingCommit}` + `EventRecordRead.Undecodable(sequence, kind, eventId, reason)` + `EventPageDrain.Outcome.Stalled(page, stuckAfter)` |
| Lectores independientes y concurrentes | `OutputFrameIndex.framesOfRun(runId, afterOrdinal, limit)` is read-only and side-effect-free; multiple readers from different processes observed in `SegmentFrameIndex.kt:204-215` |
| Identidades coherentes entre output y eventos | Both planes use `runId: String` (output/OutputChannel.kt:109-148, events/EventHistoryPorts.kt:22-53). Cross-plane projection at `ResourceRef(kind, segments)` (domain/identity/ResourceRef.kt:23-74). |
| Preservación de bytes / UTF-8 fragmentado / stdout vs stderr | Stream id is `{runId}/{operationId}/{channel.token}` (output/OutputChannel.kt:46-52); channel enum `STDOUT/STDERR`; byte offsets are absolute per stream (no global ordering invented). |
| Backpressure acotado | `LiveOutputDrain(reader, frameLimit, pollIntervalMs)` (application/observation/LiveOutputDrain.kt:50-133) and `EventPageDrain.drain(tail, run, query, cursor, limit)` (application/observation/EventPageDrain.kt:51-144) — both bound rows per poll. |
| Cancelación limpia | `LiveOutputDrainResult.Stopped` (LiveOutputDrain.kt:136-156) — explicit stop result, no thread leak. |
| No esperar al terminal para entregar bytes/eventos | `ObservationOutputFollower.replay` (application/observation/ObservationOutputFollower.kt:34-117) — pure polling, no terminality requirement; `FollowDecision.ReadAgain/Finished` (observation/ObservationWakeup.kt:121-128) decides based on `tailStates` and `runFinished`. |

The right shape for the new contract is therefore a **thin public port** in
the published `pipeline-output` and `pipeline-events` modules that composes
the existing read ports and follower machinery, plus a small refusal-extension
envelope for follow-specific failure modes. Not a re-implementation.

## 1. Public capability identifiers (first-of-kind)

The audit (Q7) confirms there is no public capability registry in the repo.
We introduce two first-of-kind identifiers:

- `output.follow.v1` — incremental live tail of stdout/stderr from a
  running run, exposed by `pipeline-output`.
- `events.follow.v1` — incremental live tail of `DomainEvent` from a
  running run, exposed by `pipeline-events`.

These are the only two new capability IDs this M1 block introduces. Other
identifiers (`output.read.v1`, `output.tail.v1`, `event.read.v1`,
`output.frame-index.v1`, `output.retention.v1`, `output.wakeup.v1`) are
already implied by the existing read ports and remain unchanged. The
`Capabilities.kt` in `v2/pipeline-application` is **internal step
capability DI**, not external capability advertisement; the new IDs
go in a sibling `Capabilities.kt` per published module, marked
`@PublishedApi` so Fabric can introspect them.

## 2. Public type surface

### 2.1 `output.follow.v1` — in `v2/pipeline-output/`

```kotlin
// New file: v2/pipeline-output/src/main/kotlin/.../output/follow/OutputFollower.kt

sealed interface OutputFollowState {
    data class Live(val committedEnd: Long) : OutputFollowState           // maps to OutputTailState.Open
    data class Sealed(val finalEnd: Long) : OutputFollowState              // maps to OutputTailState.Sealed
    data object Terminal : OutputFollowState                               // run reached terminal state
    data class Unobservable(val reason: OutputRefusal) : OutputFollowState  // read refused
}

data class OutputFollowOptions(
    val streams: List<OutputStreamAddress>,   // which stdout/stderr to follow
    val pageMaxBytes: Int = 64 * 1024,        // matches OutputReservation.DEFAULT_APPEND_WINDOW
    val pollIntervalMs: Long = 25L,           // matches MainObserveCli.FOLLOW_IDLE_MILLIS
    val includeFrames: Boolean = true,        // use OutputFrameIndex when true
    val maxRecords: Int = 256,                // matches ObservationReplayLimit.DEFAULT
    val until: FollowUntil = FollowUntil.Unbounded,
)

sealed interface FollowUntil {
    data object Unbounded : FollowUntil
    data class UntilSealed(val runId: RunId) : FollowUntil
    data class UntilSequence(val eventsLastSequence: Long) : FollowUntil
    data class UntilBytesRead(val limit: Long) : FollowUntil
}

interface OutputFollower {
    /** Cold follow. The returned Flow re-emits pages on each poll. */
    fun follow(runId: RunId, options: OutputFollowOptions): Flow<OutputFollowEvent>
    /** Cancel an active follow. Idempotent. */
    suspend fun cancel(followHandle: FollowHandle)
}

data class FollowHandle(val runId: RunId, val startedAt: Instant)

sealed interface OutputFollowEvent {
    data class Bytes(val page: OutputPage, val newState: OutputFollowState) : OutputFollowEvent
    data class StateChanged(val state: OutputFollowState) : OutputFollowEvent
    data class Refused(val refusal: OutputRefusal) : OutputFollowEvent
    data object Completed : OutputFollowEvent
}
```

### 2.2 `events.follow.v1` — in `v2/pipeline-events/`

```kotlin
// New file: v2/pipeline-events/src/main/kotlin/.../events/follow/EventFollower.kt

sealed interface EventFollowState {
    data class Live(val lastSequence: Long) : EventFollowState
    data class Sealed(val finalSequence: Long) : EventFollowState
    data object RunFinished : EventFollowState                       // DomainEvent.kind == "RunFinished" observed
    data class Unobservable(val refusal: EventFollowRefusal) : EventFollowState
}

sealed interface EventFollowRefusal {
    data class UnknownRun(val runId: RunId) : EventFollowRefusal
    data class RetentionLost(val runId: RunId, val lastSeenSequence: Long) : EventFollowRefusal
    data class LagExceeded(val runId: RunId, val observedLag: Duration) : EventFollowRefusal
    data object Cancelled : EventFollowRefusal
}

data class EventFollowOptions(
    val query: EventQuery = EventQuery.All,
    val pollIntervalMs: Long = 25L,
    val maxRecords: Int = 256,
    val until: FollowUntil = FollowUntil.Unbounded,
)

interface EventFollower {
    fun follow(runId: RunId, options: EventFollowOptions): Flow<EventFollowEvent>
    suspend fun cancel(followHandle: FollowHandle)
}

sealed interface EventFollowEvent {
    data class Page(val slice: EventRecordSlice, val newState: EventFollowState) : EventFollowEvent
    data class StateChanged(val state: EventFollowState) : EventFollowEvent
    data class Refused(val refusal: EventFollowRefusal) : EventFollowEvent
    data object Completed : EventFollowEvent
}
```

## 3. Refusal extension policy

Per the audit (Q6) the project convention is **closed `sealed interface` per
port, no `Either`/`Result` re-use**. The new contracts:

- `OutputFollower` does NOT introduce a parallel refusal hierarchy.
  Follow-specific refusals extend `OutputRefusal` (output/OutputRefusal.kt:14-60)
  as new sealed cases:
  ```kotlin
  sealed interface OutputRefusal {
      // ... existing cases ...
      data class StreamLostRetention(val stream: OutputStreamId, val lastCommitted: Long) : OutputRefusal
      data class FollowCancelled(val handle: FollowHandle) : OutputRefusal
  }
  ```
- `EventFollower` similarly extends a new `EventFollowRefusal` type, but
  `EventRecordRead.Undecodable` stays the source of truth for row-level
  refusals and is surfaced inside `EventFollowEvent.Page` (the page carries
  its own `refusals` list).

## 4. Composition contract (the rule the implementations MUST obey)

The new ports compose the existing read ports, they do not replace them:

```text
OutputFollower.follow(run, opts)
  -> for each declared stream: OutputFrameIndex.streamsOfRun(run)
  -> poll loop:
       for each stream: OutputTailPort.tailState(stream)  -> OutputFollowState
       for each declared stream: OutputFrameIndex.framesOfRun(run, afterOrdinal, limit)
       for each new frame: OutputReadPort.read(stream, cursor, maxBytes) -> OutputPage
       yield OutputFollowEvent.Bytes(page, newState)
       if all streams Sealed and until reached: yield Completed
       else sleep(pollIntervalMs)

EventFollower.follow(run, opts)
  -> poll loop:
       EventTail.readAfter(run, cursor, limit) -> EventPage
       for each record: surface as EventFollowEvent.Page (carries refusals)
       if DomainEvent.kind == "RunFinished" observed: yield StateChanged(RunFinished)
       if all events drained and no new ones for pollIntervalMs and until reached: yield Completed
       else sleep(pollIntervalMs)
```

The existing `ObservationOutputFollower.replay` and `EventPageDrain.drain`
in `v2/pipeline-application/observation/` are the reference implementation
to follow. The M1 implementations live in `v2/pipeline-output/` and
`v2/pipeline-events/` and use the same patterns; the application-level
`ObservationOutputFollower` becomes a thin adapter over the new published
ports.

## 5. UAT matrix (new for M1)

The user's TRACK B lists UAT-PK-001 / -003 / -004 / -007 as obligatorias. Of
those:

- UAT-PK-001 (runtime identity 3-component SemVer) — **already PASS in
  v0.48.0-rc3** (docs/v2/07-uat/evidence/v0.48.0-rc3/producer-uat.log).
- UAT-PK-003 (pipeline doctor) — **already PASS** in the same file.
- UAT-PK-004 (validate) and UAT-PK-007 (run) — N/A en productor; the
  certifier owns the static and dynamic run contracts.

New UATs added for M1 (to be defined in detail when the implementation lands):

- **UAT-PK-M1-001**: end-to-end live tail — a second process consumes
  `output.follow.v1` while PK continues to run more steps; bytes arrive
  before the run reaches terminal state.
- **UAT-PK-M1-002**: incremental follow across stages — a run with
  multiple stages; the consumer reads across stage boundaries without
  dropping bytes.
- **UAT-PK-M1-003**: UTF-8 boundary preservation — a step that emits a
  multi-byte codepoint split across two `OutputAppendPort` calls; the
  consumer sees the bytes in order, with the codepoint reconstructed
  from the byte stream (no false codepoint boundary).
- **UAT-PK-M1-004**: cancellation — a follow in progress, the consumer
  calls `cancel()` mid-poll; the Flow terminates with
  `EventFollowRefusal.Cancelled` (or `OutputRefusal.FollowCancelled`),
  no thread leak, no half-page emitted.
- **UAT-PK-M1-005**: retention boundary — a stream whose bytes were
  pruned between two polls; the consumer sees
  `OutputRefusal.StreamLostRetention` (or `EventFollowRefusal.RetentionLost`)
  and the contract documents the exact semantic (cursor position
  preserved, last-known extent reported, no silent EOF).
- **UAT-PK-M1-006**: lag reporting — a consumer that polls slower than
  the producer writes; once lag exceeds a configurable threshold
  (default 1s), the follow emits a `Refused(LagExceeded)` event and
  closes, so the consumer can decide between reset-and-retry vs
  escalate. The poll-by-poll contract never silently drops events
  (Q3 audit).
- **UAT-PK-M1-007**: cross-plane identity coherence — a consumer that
  holds an `output.follow.v1` handle and an `events.follow.v1` handle
  for the same run; the `runId` strings are equal at the byte level,
  and a `RunStarted` event sequence matches the first frame observed
  on the output side (no invented global ordering, just the
  per-plane observation order).

## 6. Push / wakeup transport (out of scope for the first cut)

The audit (Q5 + Q7) flags that `ObservationWakeup.EventsCommitted/OutputAdvanced`
exists as a vocabulary but no real emitter is wired (`MainObserveCli.kt:255-258`:
"There is no `EventsCommitted` emitter yet (OBS-D bis)"). The M1 first cut
**does not** wire a push transport; the `OutputFollower.follow` / `EventFollower.follow`
return `Flow<...>` that the implementation can satisfy with the existing
polling loop, and the `Wakeup` ADT is the future-proofed extension point
for a push transport. Polling is the documented baseline; push is a
non-breaking future addition.

## 7. Cross-plane identity (the rule)

The audit (Q4) shows three identity vocabularies in the repo and no
unified cross-plane contract. The M1 ports do **not** introduce a
fourth. They use:

- `runId: String` for both ports (the existing convention).
- `OutputCursor(stream, committedOffset)` for output (existing).
- `EventCursor(runId, lastSequence)` for events (existing).
- `OutputStreamAddress(runId, operationId, channel)` for output streams
  (existing; `operationId` is the runtime `OpId.format()` string).
- `EventRef` / `ResourceRef` for envelope-level identity (existing).

A future M-block (out of scope here) can introduce typed `RunId` /
`OperationId` value classes in the read ports; until then, strings.

## 8. Refusal of premature publication

The user said: "Define las capacidades que correspondan a `output.follow.v1`
y `events.follow.v1`, pero no las anuncies hasta que estén realmente
implementadas y demostradas."

Concretely:
- The capability IDs `output.follow.v1` and `events.follow.v1` are
  registered in the published `Capabilities` object **only after** the
  implementations land, the contract tests pass, and at least
  UAT-PK-M1-001 + UAT-PK-M1-002 + UAT-PK-M1-004 are green in CI.
- Until then, consumers that probe for these IDs see
  `OutputRefusal.UnknownStream` / `EventFollowRefusal.UnknownRun` and
  fall back to the existing request/response `output.read.v1` /
  `event.read.v1` reads, per the `INTERFACE_CONTRACT.md` rule
  ("ausencia de `output.follow.v1` requiere fallback anunciado o refusal
  tipado, nunca un falso LIVE").
- The release-model v2 invariant applies: the IDs are advertised as
  `EXPERIMENTAL` until a candidate is `CERTIFIED` against the contract
  test suite, then promoted to `STABLE` in a later release.

## 9. Implementation plan (in dependency order)

1. **Surgical tests first** (one per failure mode from §5). Each test
   uses the real `SegmentOutputStore` + `EventHistoryReader` so the
   implementation is grounded in the production stores.
2. **`OutputFollower` implementation** in `v2/pipeline-output/.../follow/`
   composing `OutputReadPort` + `OutputFrameIndex` + `OutputTailPort`.
3. **`EventFollower` implementation** in `v2/pipeline-events/.../follow/`
   composing `EventTail` + `EventQuery` + a `RunFinished` observer.
4. **Refusal extension** in `OutputRefusal` and the new
   `EventFollowRefusal`.
5. **Public capability registry** as a sibling `Capabilities.kt` in
   each published module.
6. **Application-level adapter**: `ObservationOutputFollower` and
   `EventPageDrain` are refactored to call the new public ports. No
   behaviour change for `pipeline run` and `pipeline observe`.
7. **UATs** as in §5.
8. **Full gate** (`./gradlew check`).
9. **Feature branch push** (`feat/cric-m1-output-events-live`). Do
   **not** merge to `main` until RC3 certifier verdict arrives, per the
   user's "no interferir con la certificación de RC3" rule.

## 10. Open questions (held for the certifier and Fabric)

- Q1 (certifier): the existing `wu-rp-053-workspace-contract` is a
  batch-read scenario. Does it cover a follow-style read? If not, the
  M1 work proposes a new UAT-PK-M1-001-style scenario as an addition
  to the certifier's battery.
- Q2 (Fabric): the example consumer at
  `examples/fabric-contract-consumer/` currently uses
  `pipeline-events` only. Will the M1 follow contracts be added to the
  same example, or is a new `examples/output-events-follow-consumer/`
  needed?
- Q3 (cross-repo contract): the `INTERFACE_CONTRACT.md` reference to
  `output.follow.v1` (line 6) and the analogous `events.follow.v1` need
  to be promoted from "absent" to "EXPERIMENTAL" with the same wording
  Fabric uses on its side. This promotion happens once Q1 and Q2 are
  answered and the implementation lands — not unilaterally here.
