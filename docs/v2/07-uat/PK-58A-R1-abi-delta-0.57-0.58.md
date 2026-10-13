# PK-58A R1.5 — ABI delta 0.57 → 0.58 (events.follow.v1)

**Train:** 0.58 (R1, Live Follow)
**Date:** 2026-10-13
**Inputs:** R1.1 commit b4c70dc1, R1.2 commit 3f33d00a, R1.3 commit
          6660ad16, R1.4 commit a0388e9c; canonical contracts
          `EventFollower.kt` (events) and `OutputFollower.kt`
          (output, unchanged in R1).

## Summary

**No public ABI change in 0.58.** The R1 cluster is a
behaviour-and-documentation correction, not a surface-area
change. Consumers of `pipeline-events` and `pipeline-output`
that compiled against 0.57 will compile and run against 0.58
without modification. The change is in the **runtime
semantics** of `SqliteEventFollowHandle` (the durable-store
adapter), not in the public type surface.

## What is and is not changed

### Public type surface (unchanged)

| Type | Module | Status |
| --- | --- | --- |
| `EventFollower` | `pipeline-events` | unchanged (KDoc extended) |
| `EventFollowHandle` | `pipeline-events` | unchanged (KDoc extended) |
| `EventFollowOptions` | `pipeline-events` | unchanged |
| `EventFollowUntil` | `pipeline-events` | unchanged |
| `EventFollowState` (sealed) | `pipeline-events` | unchanged |
| `EventFollowRefusal` (sealed) | `pipeline-events` | unchanged |
| `EventFollowEvent` (sealed) | `pipeline-events` | unchanged |
| `OutputFollower` | `pipeline-output` | unchanged (R1 did not touch) |
| `OutputFollowHandle` | `pipeline-output` | unchanged |
| `OutputFollowOptions` | `pipeline-output` | unchanged |
| `OutputFollowUntil` | `pipeline-output` | unchanged |
| `FollowState` (sealed) | `pipeline-output` | unchanged |
| `OutputRefusal` (sealed) | `pipeline-output` | unchanged |
| `OutputFollowEvent` (sealed) | `pipeline-output` | unchanged |

### Runtime semantics (changed)

- `SqliteEventFollowHandle.drainOnce` step **3e** (natural tail
  with `hasMore=false`) no longer emits
  `EventFollowEvent.Completed`. The handle re-polls while the
  consumer keeps the handle open. `Completed` is now reserved
  for the two terminal conditions the design names:
  - **3c** — `EventFollowUntil.UntilSequence` reached
    `lastSequence`
  - **3d** — a `RunFinished` event observed in the page

  This is a **consumer-visible behavioural change**: a consumer
  that opened a follow with the default `EventFollowUntil.Unbounded`
  and a quiet run will no longer see `Completed` until either
  the run is sealed by `RunFinished` or the consumer calls
  `close()`. The change is forward-compatible with the
  `M1_FOLLOW_DESIGN.md` contract: a consumer that wants
  snapshot semantics closes the handle after the first empty
  `Page`; a consumer that wants live follow keeps the handle
  open and the follow continues to poll.

- `SqliteEventFollowHandle.FollowIterator.hasNext()` no longer
  caps at "twice then give up" on a quiet cycle. The loop
  re-polls while the handle is open. The bounded `idle()` sleep
  between polls still honours `pollIntervalMs` (default 25 ms).
  This is an implementation detail, not a public ABI change.

### KDoc surface (extended, no public type change)

- `EventFollower.kt` `EventFollowHandle` class KDoc gained a
  "Live follow vs snapshot" section that enumerates the four
  consumer-side patterns (live follow, snapshot, bounded live,
  cursor-past-tail) and a "Cancellation" section. Cross-references
  the R1.1 in-process and R1.2 cross-JVM falsifiers.

## Compatibility matrix

| Consumer pattern | Behaviour in 0.57 | Behaviour in 0.58 | Action required |
| --- | --- | --- | --- |
| Open follow + drain first empty Page + close | Works (considers `Completed` as a side-effect) | Same: no `Completed` emitted, consumer closes manually | None |
| Open follow + iterate until `Completed` | Sees spurious `Completed` at natural tail (F2) | Sees `Completed` only at 3c / 3d (correct) | **Review loop body**: if the consumer treated every `Completed` as a signal to break and never re-open, it now waits for the real terminal. This is the F2 fix the cluster lands. |
| Open follow with `EventFollowUntil.UntilRunFinished` | Sees `Completed` at the natural tail even if no `RunFinished` yet | Sees `Completed` only after observing `RunFinished` in a page (3d) | **Verify** any test that wrote events before opening the follow still works (it should, because `RunFinished` will be in the first page). |
| Open follow with `EventFollowUntil.UntilSequence` | Sees `Completed` at the bound | Same: sees `Completed` at the bound (3c) | None |
| Open follow with cursor past the tail | Sees `Refused(UnknownRun)` (cursor-past-tail → UnknownRun translation) | Same | None |

## What this doc is NOT

- This is not the v0.58.0-rc1 release notes. The release notes
  will be produced at R1.6 (release-time). This doc is the
  **ABI delta** the operator asked for in the R0-R4 roadmap
  for R1.5, so a future contributor can answer "what changed
  in 0.58" without reading the commit log.
- This is not the conformance suite. The conformance suite
  (StepContractSuite, M1DCrossJvmFollowTest) is the runtime
  evidence. The ABI doc is descriptive; the conformance suite
  is the proof.
- This is not an M1_FOLLOW_DESIGN.md update. R1.5 keeps the
  design document untouched in this cluster; the design
  document is the architectural source of truth and the
  F2 finding was an adversarial finding on top of the design,
  not a redesign.

## Cross-references

- R1.1 commit: `b4c70dc1` (F2 fix + in-process falsifier)
- R1.2 commit: `3f33d00a` (UAT-PK-M1-007 cross-JVM falsifier)
- R1.3 commit: `6660ad16` (EventFollowHandle KDoc)
- R1.4 commit: `a0388e9c` (UAT/AAT ID reconciliation)
- `docs/v2/07-uat/PK-58A-ports-audit.md` §2 (F2), §3 (F3), §7
- `docs/pipelinek-coordinated-evolution/m1-design/M1_FOLLOW_DESIGN.md` §4 §5
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/follow/EventFollower.kt`
- `v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/follow/OutputFollower.kt`
- `v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteEventFollowHandle.kt`
