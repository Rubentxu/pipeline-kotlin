# TRAIN H3 / PR-019 — Invocation + Recovery consolidation (receipt)

**Slice:** TRAIN H3 (third hardening TRAIN, after H1 / PR-017 and H2 / PR-018)
**Branch:** `h3-invocation-recovery`
**Base:** `main == 56593a0c` (H2 closed, gate-green, merged)
**Head of slice:** `2e69e40a`
**WorkItem:** `3745d8f1-e8f7-4eac-9bdb-998c14dd6bf7` (PR-019)
**Catalog entry:** PR-019 — *consolidar `InvocationEngine` + `RecoveryEngine`; mover compatibilidad
al composition root*, P1, depends on PR-018.

## 1. What this slice changes

Before H3, recovery was split across two places with no seam between them: the
**decision** lived in `DurableInvocationResolver`, and the **interpretation** — the journal
write, the replay-cursor advance and the `StepExecutionBoundary` lifecycle events — sat
inline in a `when` block inside `CanonicalDurableRunCoordinator.dispatch`. One method decided
what a resumed run may do and then performed the consequences, so neither half was separately
testable, and the process-reconciliation compatibility hook was reachable from inside the
class that owns the pure decision.

| Slice | Commit | Change |
| --- | --- | --- |
| 0 | `a6675423`, `e53f9127` | RED-first characterization of the durable recovery contract, 9 cases, with the precedence assertion proven non-vacuous by mutation |
| 1 | `02ea654e` | The four recovery arms move verbatim out of `dispatch` into `RecoveryInterpretationEngine` |
| 2 | `2e69e40a` | The a2 external-subprocess hook becomes a port; the resolver loses all process knowledge |

### The resulting shape

```text
DurableInvocationResolver      decides, pure, no filesystem, no process
  └─ RunningSubprocessRecovery      port: WHEN recovery applies stays here
       └─ ExternalSubprocessRecovery  adapter: WHAT the reconciler found
RecoveryInterpretationEngine  interprets, performs the effects
```

`interpret` is `suspend` and the decision is not. That asymmetry is deliberate and is the
seam: the boundary between the two halves is exactly where the effects begin.

## 2. Result measured (not estimated)

| Measure | `main` (56593a0c) | `2e69e40a` | Delta |
| --- | --- | --- | --- |
| `CanonicalDurableRunCoordinator.kt` | 2045 lines | 2032 lines | −13 |
| `DurableInvocationResolver.kt` | 172 lines | 127 lines | **−45** |
| `RecoveryInterpretationEngine.kt` | — | 135 lines | new |
| `RunningSubprocessRecovery.kt` | — | 111 lines | new |

The resolver is the number that matters for this slice: it lost 45 lines and now has **zero**
imports of `StepReconcilerL1` or `DurableShellExecutor`. It no longer knows a process exists.

## 3. The two arms that are easy to lose

The four arms were moved **verbatim**, including the differences that a rewrite would
flatten. Those differences are the behaviour:

| Resolution | Lifecycle events | Journal write | Cursor advance |
| --- | --- | --- | --- |
| `Diverged` | none — the step never starts | none | none |
| `RecoverRunning` | yes | yes, recovered terminal status | only on success |
| `ReuseCompleted` | **none** | **none** — the row is already terminal | none |
| `RejectedAbort` | yes — the step started, so it must finish observably | none | none |

`Execute` deliberately **stays in the coordinator**, because `Execute` *is* the invocation
rather than a recovery case. It is reported as `RecoveryInterpretation.ProceedToExecution`, a
closed ADT rather than a nullable `StepOutcome`, so a resolution the engine does not handle is
a compile error and not a silent fallthrough.

## 4. A vacuous guard, found and fixed

The first version of the slice-0 precedence test asserted nothing.

Its fixture left the recovery hook inert — no `ExternalSubprocess` policy, no control
directory — so the decision resolved to `Diverged` whichever order the two steps ran in.
Proven by mutation: moving the recovery hook above the divergence gate left the suite
**green**.

That is the worst shape a guard can have: it reads as coverage of an ordering law and is in
fact coverage of the divergence gate alone. The fix arms every precondition the hook needs —
the step declares `ExternalSubprocess`, the journal row is `RUNNING`, and a real control
directory exists — so an operation with no reconcilable control data classifies as `Lost` and
resolves to `RecoverRunning`, a genuinely competing outcome. Verified both ways: RED with the
hook moved, GREEN with production restored byte-exact.

**The generalisable law: a precedence assertion is only real if every competing branch could
have won.** An inert sibling branch makes the test vacuous while still reading as coverage.
Every characterization in this slice was mutation-checked before being accepted as a guard.

## 5. A rejected design, recorded

The compatibility hook was first added as a **coordinator constructor parameter**. Kotlin
rejected it — a public constructor cannot expose an internal port type — and promoting the
type public cascaded into `RunningCanonicalShellRecovery` as well.

That cascade was the useful signal: the seam does not belong at the coordinator boundary. The
port was kept `internal` and seammed at the resolver, which is where the decision and the
observation actually meet. No testability was lost — the characterization constructs the
resolver directly — and the coordinator's 22-argument surface did not widen. The rejected
approach is written down here because the failure is instructive, not because it is
recoverable.

## 6. Gates

| Slice | Gate | Result |
| --- | --- | --- |
| 1 | `check --rerun-tasks` on `02ea654e` | BUILD SUCCESSFUL in 23m 28s — 642 classes, 4101 tests, 0 failures |
| 2 | `check --rerun-tasks` on `2e69e40a` | BUILD SUCCESSFUL in 23m 3s — 642 classes, 4101 tests, 0 failures |

Slice 2's first gate attempt failed in 25 s on `detekt` (`MayBeConstant` on
`REATTACH_TIMEOUT_MS`). Fixed rather than suppressed, then re-run clean.

The repository gate remains trustworthy because of the `a0fa98d1` fix from H2:
`Rp022ThroughputProbe`'s XML still carries the mtime of its deliberate isolated run rather
than being rewritten by the gate window, which is the direct evidence that no wall-clock
measurement participates in the gate.

## 7. What this slice deliberately did not do

- **The coordinator is still 2032 lines.** PR-020 owns getting it under 600 LOC and adding
  the shared-model composition gate; PR-019 was about the recovery seam, not the size.
- **The cycle phase ledger is still stuck.** The cycle
  `p-1f3622e11c093341/h2-body-execution-engine` reports `phase: explore`, `artifacts: 0`,
  `lease: none` although its WorkItem is `Done` and its work is merged. The
  `phase.explore.complete` requirement stayed `requires_met: false` after writing the report,
  registering it in the artifact registry, and storing it with
  `sddk artifact store --kind exploration-report`. Recorded, not papered over.
- **No override seam for the recovery port at the coordinator.** See §5 for why.
