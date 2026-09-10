# ADR-PAR-D: Typed Parallel Reconciliation — superseding BranchReconciler (ADR-0038/0040)

Status: ACCEPTED (PAR-D closure)
Date: 2026-09-10
Supersedes: ADR-0038 (BranchReconciler re-attachment), ADR-0040 (reconciler-driven
resume orchestration). Partially supersedes ADR-0042 (see below).
Evidence: `docs/v2/07-uat/PAR_D_CLOSURE_RECEIPT.md`

## Context

The pre-PAR-D parallel reconciliation authority, `BranchReconciler`
(ADR-0038), was an orphan: zero production call sites, zero constructors,
zero wiring (inventory 2026-09-10; see receipt §Reachability). Its model
was coupled to the retired `walkParallelFrame`/`PipelineOrchestrator`
durable walk and to `ReplayCursorStore` checkpoints. The canonical
production parallel path is `CanonicalDurableRunCoordinator.runParallelStage`
through the single dispatch spine (E-EM-11), which had no aggregate
durable identity and no typed replay decision (the P6 gap).

## Decision

1. **One reconciliation authority.** Parallel durable reconciliation is a
   pure, plan-only decision in `ParallelReconciler` (pipeline-domain):
   durable facts → `ParallelReconciliationState` (observation) →
   `ParallelDecision` (decision). `CanonicalDurableRunCoordinator` is the
   single writer/effect executor. ADR-0075 thinker/writer pattern reused.

2. **Durable aggregate without schema change.** The parallel stage keeps a
   COMPOSITE `OperationJournal` row born via `append(CompositeOperation
   RUNNING)` and closed by `append(terminal)`, carrying the exact typed
   outcome (`success | unstable | failure + message`) as a lossless
   semantic carrier. Structural fingerprint over branch names; identity is
   a typed `ParallelAggregateId` (never an `OpId` sentinel).

3. **Crash-window contract (W0..W7).** Fresh/Admitted → Start(all);
   partial (terminal + non-terminal evidence) → ResumeBranches(incomplete
   only); all children terminal + stale aggregate → CloseFromChildren
   (only provable from all-SUCCEEDED evidence); terminal aggregate →
   exact ReuseSuccess/ReuseUnstable/ReuseFailure; fingerprint mismatch →
   RejectDivergence; terminal-but-unprovable evidence (e.g. FAILED child
   row that may hide an Unstable step outcome, no carrier) →
   RejectAmbiguousOutcome. Terminal children are never re-executed;
   reuse/close decisions project no branch lifecycle events.

4. **Structured execution.** The branch join runs inside a caller-bound
   structured scope; branch outcomes are typed values, never exceptions
   as control flow. `CancellationException` is an execution mechanism,
   never mapped to a durable terminal truth or generic INFRASTRUCTURE
   failure. Coroutines execute a previously determined typed decision;
   they are not the authority for durable truth.

5. **Failure policy.** `ParallelFailurePolicy.AwaitAll` types the
   pre-existing behavior (wait all started branches; fold failure >
   unstable > success by lowest branch index) — byte-equivalent default.
   `FailFast` is DESIGNED in the ADT, NOT exposed, NOT implemented.

## Supersede disposition

- **ADR-0038 — SUPERSEDED.** `BranchReconciler` deleted; its valid laws
  survive re-homed: completed branch → no redispatch, no duplicate
  lifecycle events (P6-3/P6-6 tests); ambiguous/stuck recovery → fail
  closed (P6-8); RUNNING-branch re-attachment → ResumeBranches with a
  real execution transition (W2 test).
- **ADR-0040 — SUPERSEDED.** Resume orchestration no longer passes
  through the legacy durable walk or checkpoint-based re-attachment;
  resume is the coordinator executing the reconciler's ResumeBranches
  decision through the canonical spine.
- **ADR-0042 — PARTIALLY SUPERSEDED.** Its observable laws SURVIVE as
  PAR-D rows: (a) terminal reused branch → no redispatch, no duplicate
  lifecycle events (P6-3/P6-6); (b) recoverable/incomplete branch → a new
  execution transition MAY project branch lifecycle (W2 test asserts the
  new branch's Started only); (c) ambiguous durable evidence → fail
  closed (P6-8). Its MECHANISM (unconditional Started emission for
  NEEDS_REATTACH branches, walkParallelFrame event placement) is
  replaced by decision-gated projection. The old clause "emit
  ParallelBranchStarted for ALL branches on resume" is explicitly
  RETIRED: reuse projects nothing.

## Consequences

- Parallel reconciliation authorities = 1; enforced by
  `FArchParallelReconciliationAuthorityTest` (source-level absence of
  `BranchReconciler` + single `ParallelReconciler` reference site).
- Aggregate reuse requires no journal/schema migration.
- Known follow-up (explicitly NOT part of PAR-D): parallel branch
  execution context isolation (shared mutable `contextStack` between
  branch coroutines for scope-pushing steps inside branches). PAR-D
  preserves the pre-existing behavior; branches with linear echo/sh
  steps do not push overlays. If a reproducible race appears, this is a
  blocker, not a follow-up.
