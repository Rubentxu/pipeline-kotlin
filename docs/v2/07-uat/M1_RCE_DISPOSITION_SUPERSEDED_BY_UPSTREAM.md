# M1 · RCE disposition — SUPERSEDED_BY_UPSTREAM

**Date:** 2026-10-04 · **Supersedes:** the RCE upstream-promotion plan recorded in
`pipelinek-runtime-contract-evolution` receipts `RCE-ADMISSION-1` and `RCE-PROMOTION-1..5` · **For:**
the M1 line only

---

## 1. Disposition

```text
rce/scripted-status-authority-reconcile   SUPERSEDED_BY_UPSTREAM(5c021496)
rce/scripted-status-authority-main        SUPERSEDED_BY_UPSTREAM(5c021496)

Neither branch is an admission candidate. Do not promote. Do not merge.
```

Both branches exist only to be **read as evidence**. Neither carries a change that `main` or
`s4-a1b-scripted-shell-spine` still needs.

## 2. Why: S4 closed the same hole, harder

`5c021496` (*refactor(scripted): una sola autoridad durable, D-4 cerrada por eliminacion*) removed
357 lines from `src/main` — the same two classes RCE's refactor was deduplicating, plus the port
that would have let a second authority re-plug itself:

| Removed | Lines | What it was |
|---|---|---|
| `JournaledScriptedOperationRuntime` | 270 | hardcoded fingerprint, its own replay and status |
| `DurableScriptedOperationReconciler` | 87 | a second scripted recovery path |
| port `RunningScriptedOperationReconciler` | — | the seam a second authority could reattach to |

RCE proposed *extract one table and call it from both sites*. S4 decided, before touching code and
with the module-publication facts measured, that the class was neither published API nor ABI — so the
applicable rule was **eliminate, not shim**. That is strictly stronger than RCE's change and leaves
nothing to merge.

`5c021496` also added `SingleDurableAuthorityFitnessTest`, which defends the law by *property* — no
retired piece is declared in production, only the authority may ask for a replay decision, the
scripted surface never reads a durable status to decide, and none hardcodes a `ReplayPolicy`. All
four read code with comments stripped, because the deleted class's own KDoc would otherwise satisfy
the first law. RCE had no equivalent; the guard is better than the one that would have shipped.

**Consequence:** the duplication RCE measured at `DurableScriptedOperationReconciler.kt:71` and
`JournaledScriptedOperationRuntime.kt:144` no longer exists on this branch. The `S4D4ScriptedStatusAuthorityTest`
guard carried by the RCE branches is superseded by the fitness test.

## 3. What RCE still contributes, and it is not the refactor

The refactor is dead. The **evidence** is not, and it is what M1-P1..P4 are built on:

- `ADR-0002` — strategy **D** (segment reservation with recovery) decided on measured contention
  and commit-window grounds, with A/B/C rejected on stated reasons. M1-P1 implements D.
- `05-§OUTPUT-PLANE` — output continuation must be independent of `EventCursor`. M1-P3 implements it.
- The double-render finding: the product rendered console bytes **twice, independently** — whole-file
  via `redactFile`, and a separate `EchoOutputCaptured` event built from in-memory buffers via
  `redactStream`. M1-P2 removes the second one.
- The crash matrix and the refusal that survives adoption: `halt()` models process death, so the
  proven property is *"a process that dies loses nothing it acknowledged"*, **not** power-loss
  durability. M1-P4 must not strengthen that claim.

## 4. What is explicitly *not* claimed by this disposition

- **Not** that RCE's investigation was wasted. It is retired as a *branch* and retained as a
  *harness and argument*. The four product decisions it forced are now made and recorded in
  `docs/v2/04-adrs/ADR-M1-output-authority.md`.
- **Not** any admission of `main`. The M1 line lands on its own branch; `main` adoption and the
  integration full gate are review steps, unchanged by this receipt.
- **Not** a change to S4's `Unstable` or recovery semantics. S4 owns those while F1-C is open, and
  this branch does not touch them.

## 5. Machine-checkable

`M1RceBranchSupersededTest` fails if either RCE branch is treated as admissible: it reads the
disposition table above and refuses any disposition other than `SUPERSEDED_BY_UPSTREAM`, and it
asserts the two classes `5c021496` removed are absent from `src/main` on this branch. The guard was
mutation-proven: reverting either row to an admissible disposition turns it red.
