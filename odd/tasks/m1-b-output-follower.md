# M1-B — real `OutputFollower` against real stores

**Branch:** `feat/cric-m1-output-events-live` (worktree `pk-cric-m1`).
**Anchor commits:** M1-A green at `e257e381`. Design `M1_FOLLOW_DESIGN.md` (revision 2).
**Owner:** M1-B block. Out of scope: M1-C (EventFollower), M1-D (cross-JVM), M1-E (capability registry).

## Objective

Back the public `OutputFollower` contract (defined in
`v2/pipeline-output/.../follow/OutputFollower.kt`) with a real
`SegmentOutputStore` + `SegmentFrameIndex` implementation. The follow
contract is a pull iterator that emits `OutputFollowEvent` over a
polling loop at `FOLLOW_IDLE_MILLIS = 25L` cadence, honours
`afterOrdinal` resumption, translates storage failures to the closed
`OutputRefusal` ADT, and never throws from a total function.

## Problem

M1-A added the public types (`OutputFollower`, `OutputFollowHandle`,
`OutputFollowOptions`, `FollowUntil`, `OutputFollowEvent`,
`FollowState`) and the two new refusal cases
(`OutputRefusal.StreamLostRetention`, `OutputRefusal.FollowCancelled`).
No implementation backs them yet — a consumer that probes
`output.follow.v1` currently gets nothing. The M1 first cut is
incomplete without a real producer.

## Why now

M1-C (EventFollower) and M1-D (cross-JVM e2e) both need a working
Output Follower to compose against. The contract is iterated
additively; landing the Output side first means the e2e test in M1-D
finds composition bugs at JVM boundary rather than at the seams
inside one JVM.

## Scope

**In scope**
- `SegmentOutputFollower` — implements `OutputFollower` against the
  real `SegmentOutputStore` + `SegmentFrameIndex`.
- `SegmentOutputFollowHandle` — implements `OutputFollowHandle`,
  single-observer, AutoCloseable, drives a `Iterator<OutputFollowEvent>`.
- Surgical test `SegmentOutputFollowerTest.kt` with at least 9 cases
  using a real `SegmentOutputStore` + `SegmentFrameIndex` on
  `@TempDir` (no in-memory doubles, no hand-rolled fake stores).

**Out of scope** (held for the next blocks)
- M1-C: real `EventFollower` against `EventStore` (next task).
- M1-D: cross-JVM e2e via `ProcessBuilder` (next task).
- M1-E: capability registration + `INTERFACE_CONTRACT.md` update (next task).
- Anything in `:pipeline-application` (the Flow adapter that wraps the
  iterator is an application-layer concern, not a contract surface).
- v0.48.0 release harness work — frozen.

## Constraints (carry-over from M1-A)

- No `kotlinx-coroutines`, no `Flow`, no `suspend` on the published classpath.
- No `@PublishedApi internal` magic.
- No `LagExceeded` refusal (a slow consumer is not a defect).
- Closed refusal hierarchies — never throw from a total function.
- The Output Plane does NOT authoritatively know if a run is over;
  `OutputFollower` only emits `StreamSealed` then `Completed` if the
  consumer's `FollowUntil` is `UntilAllSealed`. `RunTerminal` is
  application-layer work (the join with the event plane).

## Actionable checklist

- [ ] T1 — write `SegmentOutputFollower` in
  `v2/pipeline-output-store/src/main/kotlin/.../output/store/`.
  Wires `OutputReadPort` + `OutputFrameIndex` + `OutputTailPort` into
  a `OutputFollower.open(runId, opts)`.
- [ ] T2 — write `SegmentOutputFollowHandle` in the same package.
  Single-observer, `AutoCloseable`, drives an
  `Iterator<OutputFollowEvent>` over a polling loop at
  `pollIntervalMs` cadence.
- [ ] T3 — write `SegmentOutputFollowerTest.kt` in
  `v2/pipeline-output-store/src/test/kotlin/.../output/store/` with
  at least 9 cases on a real `SegmentOutputStore` + `SegmentFrameIndex`:
  1. unknown runId → `Refused(UnknownStream)` as the first event;
  2. run with no declared streams → `FollowState.Unobservable` then
     `Completed` (or equivalent);
  3. after `StateChanged(Running)`, yields `Bytes(page, newState)` per
     new frame;
  4. `pageMaxBytes` caps bytes per page, not record count;
  5. `maxRecords` caps records per poll cycle;
  6. `afterOrdinal` skips frames already seen;
  7. `FollowUntil.UntilAllSealed` → `Completed` when every declared
     stream is sealed;
  8. retention pruning between polls → `Refused(StreamLostRetention(...))`;
  9. closing the handle mid-poll → no further events.
- [ ] T4 — run `:pipeline-output-store:test --tests "*OutputFollower*"` green.
- [ ] T5 — run the full module test suite
  (`:pipeline-output-store:test :pipeline-output:test --no-daemon`) green.
- [ ] T6 — commit as one work-unit on
  `feat/cric-m1-output-events-live` with a Conventional Commit message.
- [ ] T7 — `git push --no-verify` to publish the work-unit.

## Implementation sketch

```kotlin
class SegmentOutputFollower(
    private val read: OutputReadPort,
    private val frames: OutputFrameIndex,
    private val tails: OutputTailPort,
) : OutputFollower {
    override fun open(runId: String, options: OutputFollowOptions): OutputFollowHandle =
        SegmentOutputFollowHandle(read, frames, tails, runId, options)
}

class SegmentOutputFollowHandle(...) : OutputFollowHandle {
    private val closed = AtomicBoolean(false)
    private var next: OutputFollowEvent? = null
    private var done = false

    override fun iterator(): Iterator<OutputFollowEvent> = object : Iterator<OutputFollowEvent> {
        override fun hasNext(): Boolean {
            if (closed.get()) return false
            if (next != null) return true
            if (done) return false
            next = pollOnce() ?: run { done = true; null }
            return next != null
        }
        override fun next(): OutputFollowEvent {
            check(hasNext()) { "no more events" }
            val ev = next!!
            next = null
            return ev
        }
    }
}
```

## Acceptance criteria

- All 9+ test cases green on a real `SegmentOutputStore` +
  `SegmentFrameIndex` on `@TempDir`.
- Full module test suite still green
  (`:pipeline-output-store:test :pipeline-output:test`).
- No coroutines on the published classpath.
- No new fields on existing ports; only additive extension (an
  optional `OutputTailPort` collaborator passed in to the adapter).
- `OutputFollower` + `OutputFollowHandle` + tests committed as one
  work-unit commit on `feat/cric-m1-output-events-live`.

## Verification commands

```bash
./gradlew :pipeline-output-store:test --tests "*OutputFollower*" --no-daemon
./gradlew :pipeline-output-store:test :pipeline-output:test --no-daemon
```

## Progress

- [x] T1 — `SegmentOutputFollower` written.
- [x] T2 — `SegmentOutputFollowHandle` written.
- [x] T3 — `SegmentOutputFollowerTest.kt` written (13 cases).
- [x] T4 — `*OutputFollower*` tests green (13/13).
- [x] T5 — full module suite green
  (`:pipeline-output-store:test :pipeline-output:test`).
- [x] T6 — committed on `feat/cric-m1-output-events-live`.
- [x] T7 — pushed with `--no-verify`.

## Evidence

- Test count: 13 (target ≥ 9). The 10 plan-numbered cases plus three
  bonus cases: (a) the `tailStateOrNull` companion helper, (b) a
  `Bytes` page cursor/committedEnd resume property check, and (c) a
  `9b` close-mid-poll test that pins the practical "closed flag
  short-circuits the next hasNext" contract.
- Refusal extension: added `OutputRefusal.StorageError` to the closed
  ADT in `:pipeline-output` so a thrown exception from
  `OutputReadPort.read` / `OutputFrameIndex.framesOfRun` /
  `OutputTailPort.tailState` is caught at the follower boundary and
  surfaced as a typed refusal (mirrors the M1-A
  `EventRecordReadRefusal.StorageError`). The new case is additive;
  no existing case was changed.
- Deferred: a future wire-through of `ObservationWakeup` can swap
  `Thread.sleep` for an interruptible wait, which would also let
  test 9b's "close interrupts mid-sleep" semantic be tightened
  without changing the public type. Not in scope for M1-B.

## Next step

Hand off to M1-C (EventFollower) or to M1-D (cross-JVM e2e) per the
dependency order in `M1_FOLLOW_DESIGN.md` §10.
