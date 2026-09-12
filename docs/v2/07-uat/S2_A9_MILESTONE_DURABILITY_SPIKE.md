# S2-A9 — Durability / Restart Spike

> Cycle: `cycle/lfc2-e1-milestone`
> Slice: S2-A9 (`core.milestone`)
> Spike: Milestone state persistence across replay/restart
> Date: 2026-09-12

## 1. Problem

The G0 for milestone said the candidate must be journal-aware. The current spike says
reset-on-replay "matches legacy". These two statements contradict each other.

This spike evaluates whether milestone ordinal state can survive a coordinator restart
without introducing a new subsystem.

## 2. Option A — LEGACY PARITY (current behavior)

**Store lives at coordinator lifetime; restart loses ordinal; limitation accepted.**

### How it works today

`MilestoneStateStore` is created at `CanonicalDurableRunCoordinator` construction:

```kotlin
private val milestoneStateStore: MilestoneStateStore = MilestoneStateStore()
```

On a fresh coordinator (fresh JVM / fresh run with same `--db`):
- The store is fresh
- `lastReachedOrdinal` is `null`
- Milestone 10 that succeeded in run A is re-run in run B and reaches again

This is the same as the legacy `CanonicalMilestoneNodeDispatcher`, which also resets on
a fresh coordinator instance.

### Evidence: no new subsystem required

The `MilestoneReached` event is already journaled as a durable event. Its `ordinal` field
carries the value. Any downstream consumer that needs the last reached ordinal can
re-hydrate it by scanning the `MilestoneReached` events for the maximum `ordinal`.

### Limitation

If the same pipeline run crashes and restarts with the same `--db` (same coordinator
instance?), the in-memory store is lost. **However:** in the current LFC-2E1 model,
each `run` invocation creates a new coordinator instance, so this scenario does not apply.
The limitation only affects the theoretical case of a coordinator that survives a crash
and resumes with the same instance — which is not the LFC-2E1 architecture.

## 3. Option B — DURABLE MILESTONE (would require new subsystem)

**Store rehydrates `lastReachedOrdinal` from journaled `MilestoneReached` events.**

### Rehydration approach (PwdResolved-style)

The `OperationJournal.listForRun(runId)` returns all journaled operations for a run.
Each `MemoizedOperation.output` contains the encoded `MilestoneOutput`. The
`MilestoneOutput` carries `ordinal` and `status: MilestoneStatus`.

To rehydrate the store on coordinator construction:

```kotlin
// Pseudocode in coordinator init
val lastOrdinal = journal
    .listForRun(runId)
    .filter { it.output != null }
    .mapNotNull { op ->
        val decoded = milestoneOutputCodec.decode(
            EncodedStepValue(op.output.result.toString())
        )
        if (decoded.status is MilestoneStatus.Reached) decoded.ordinal else null
    }
    .maxOrNull()

if (lastOrdinal != null) {
    milestoneStore.advance(lastOrdinal)  // idempotent: store = lastOrdinal
}
```

### Assessment: REQUIRES NEW SUBSYSTEM

The `OperationJournal` interface has `listForRun(runId)` — this is the right API.
The `MemoizedOperation.output.result` carries the encoded `MilestoneOutput` — the right data.

However, there is a **timing problem**: `MilestoneStateStore` is created at coordinator
construction (in the constructor), but `runId` is only known at `run()` call time.
The rehydration would need to happen inside `run()` after `runId` is known, which means
either:

a) The store is lazily initialized (breaks the non-null invariant), or
b) The store starts empty and is patched after `runId` is known (two-phase init), or
c) The store is created with the `runId` as a constructor parameter.

All three options require modifying the coordinator construction protocol and the
capability injection chain. This is a non-trivial architectural change that introduces
a new initialization concern (two-phase store init) into the coordinator startup path.

Additionally, `MilestoneOutputCodec.decode` is an internal detail of `CoreMilestoneStep`.
The coordinator should not need to know about the step's output codec to rehydrate state.

**Conclusion for Option B:** Feasible but requires a new coordinator initialization
protocol and tight coupling between the coordinator and the milestone step's codec.
This is a meaningful architectural extension, not a trivial addition.

## 4. Decision

**Option A — LEGACY PARITY** is frozen for LFC-2E1.

Rationale:
1. Matches legacy behavior exactly (milestones reset on coordinator restart)
2. No new subsystem required (rehydration would need a two-phase init protocol)
3. The `MilestoneReached` event carries the ordinal as a durable artifact — any
   consumer that needs historical ordinal can scan events independently
4. The LFC-2E1 architecture creates a fresh coordinator per `run` invocation, so the
   "surviving coordinator" restart scenario does not apply

**Explicit debt (LFC-2E1 scope):** If a future architecture supports coordinator
survival across crashes (re-attachment), milestone ordinal rehydration via event scan
must be revisited. Filed as `ponytail: milestone-durable-restart` for future cycles.

## 5. ADR amendment

No new ADR required. The decision is documented here and in the per-gate receipts.
The LEGACY PARITY behavior is the current documented behavior of `MilestoneStateStore`
(no persistence beyond coordinator lifetime).

---
**SPIKE CLOSED — Decision frozen: Option A (LEGACY PARITY).**
**No new subsystem. Milestone ordinal resets on coordinator restart (matches legacy).**
