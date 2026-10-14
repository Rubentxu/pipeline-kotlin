# PK-58A R1 — UAT / AAT ID reconciliation

**Train:** 0.58 (R1, Live Follow)
**Date:** 2026-10-13
**Inputs:** M1_FOLLOW_DESIGN.md §6, PK-58A-ports-audit.md §3 (F3),
          M1DCrossJvmFollowTest.kt @DisplayName mapping, R1.1
          `SqliteEventFollowLiveTailTest`, R1.2 `M1DCrossJvmFollowTest.7
          UAT-PK-M1-007`.

## Problem the operator raised (F3, 2026-10-13)

The previous PK-58A T1 audit proposed a UAT/AAT matrix
`UAT-PK-M1-001..008` and stated AAT-M1-01 cross-JVM e2e did not
exist. Both claims were wrong. The cross-JVM test class
`M1DCrossJvmFollowTest` already existed with 6 `@Test` methods
mapped to UAT-PK-M1-001..006 per `M1_FOLLOW_DESIGN.md` §6. The
proposed 007/008 were never written.

R1.4 reconciles the canonical design IDs with the test methods
that actually exist, so the UAT/AAT matrix in
`PK-58A-ports-audit.md` §7 is consistent with the source of truth
on disk.

## Reconciliation table

| ID | Title (canonical per M1_FOLLOW_DESIGN §6) | Test method | R1.x | Status |
| --- | --- | --- | --- | --- |
| UAT-PK-M1-001 | Live tail across the JVM boundary, output plane | `M1DCrossJvmFollowTest.1` | R1.2 | GREEN |
| UAT-PK-M1-002 | Live tail across the JVM boundary, events plane | `M1DCrossJvmFollowTest.2` | R1.2 | GREEN |
| UAT-PK-M1-003 | Output resume from `afterOrdinal` (no dups / no gaps) | `M1DCrossJvmFollowTest.3` | R1.2 | GREEN |
| UAT-PK-M1-004 | Events resume from `EventCursor` (no dups / no gaps) | `M1DCrossJvmFollowTest.4` | R1.2 | GREEN |
| UAT-PK-M1-005 | Two independent processes follow the same run | `M1DCrossJvmFollowTest.5` | R1.2 | GREEN |
| UAT-PK-M1-006 | Events follower reaches `RunTerminal`, emits `Completed` exactly once | `M1DCrossJvmFollowTest.6` | R1.2 | GREEN |
| UAT-PK-M1-007 | **Re-allocated (R1.2):** events follower crosses the natural tail cross-JVM without emitting `Completed` (the F2 falsifier, see below) | `M1DCrossJvmFollowTest.7` | **R1.2** | GREEN (fresh XML 2026-10-13T13:47:37Z) |
| (none) | **F2 in-process falsifier:** `SqliteEventFollowLiveTailTest` covers the same F2 contract in-process | `SqliteEventFollowLiveTailTest.followAtNaturalTailKeepsPolling`, `followEmitsCompletedOnRunFinished` | **R1.1** | GREEN (fresh XML 2026-10-13T13:40:07Z) |
| (none) | **F2 contract annotation:** `EventFollowHandle` KDoc makes live-vs-snapshot-vs-bounded-live-vs-cursor-past-tail explicit | `EventFollower.kt` (KDoc only) | **R1.3** | GREEN (no behavior change) |

## Notes

- **UAT-PK-M1-007 is a re-allocated ID.** The previous PK-58A T1
  audit proposed IDs 007 and 008; both were never written. R1.2
  writes 007 for the F2 falsifier the operator raised on 2026-10-13.
  008 is left unallocated pending the next F-series finding.
- **M1_FOLLOW_DESIGN.md §6 still lists 001..006 only.** The
  design document is the architectural source of truth for the
  M1 contract; R1.2's 007 is a post-F2 adversarial extension
  that the design did not anticipate. Updating the design
  document to mention 007 is part of the R1.5 ABI documentation
  task (R1.5 keeps the change small and avoids drift between
  the design and the test inventory until R1.5 is closed).
- **No AAT-M1-01 in this cluster.** The audit's `AAT-M1-01`
  reference is a misread of the test inventory; the cross-JVM
  test methods are UAT (acceptance), not AAT (adversarial). The
  in-process F2 falsifier is the only adversarial test in this
  cluster, and it is named `SqliteEventFollowLiveTailTest`, not
  `AAT-M1-01`.

## What is NOT in this reconciliation

- R1.4 does not re-baseline the M1_FOLLOW_DESIGN.md IDs; that is
  R1.5 (ABI documentation, planned for `v0.58.0-rc1`).
- R1.4 does not propose new UAT/AAT IDs beyond the 007
  re-allocation; the audit's `008` slot is held for the next
  F-series finding.
- R1.4 does not move any test method. The 7 `@Test` methods in
  `M1DCrossJvmFollowTest` and the 2 in
  `SqliteEventFollowLiveTailTest` are the authoritative
  inventory.

## Cross-references

- `docs/v2/07-uat/PK-58A-ports-audit.md` §3 (F3), §7 (matrix)
- `docs/pipelinek-coordinated-evolution/m1-design/M1_FOLLOW_DESIGN.md` §6
- `v2/pipeline-events-store/src/test/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteEventFollowLiveTailTest.kt` (R1.1)
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/follow/M1DCrossJvmFollowTest.kt` (R1.2)
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/follow/EventFollower.kt` (R1.3)
