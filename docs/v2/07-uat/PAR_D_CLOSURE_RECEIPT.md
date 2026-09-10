# PAR-D CLOSURE RECEIPT — typed parallel reconciliation & structured concurrency

Cycle window: 181cb0d2..c8dc34c1 (base for rule 16: cbd0db1a = E-EM-11 HEAD).
Published trunk SHA: 5b024cd8 (fast-forward push to origin/main, 2026-09-10; no force-push).
ADR: `docs/v2/04-adrs/ADR-0076-typed-parallel-reconciliation.md`.
Global round gate: `timeout 1270 ./gradlew -p v2 check` — 20m45s (within budget),
**NON-ZERO due exclusively to known baseline debt**.
**PAR-D delta gate: PASS. New failures: 0. Baseline widened: 0.**

## P6 durable replay (matrix rows, all GREEN)

- fresh success/success (W0/P6-1): Start(all), aggregate closes SUCCEEDED
- terminal success replay (W6/P6-3): ReuseSuccess — 0 child executions,
  0 fabricated ParallelBranchStarted/Finished (HF1 + installDist P6 test)
- terminal failure handling (W6/P6-4): ReuseFailure — exact durable outcome
- partial restart (W2/P6-5 + D3): ResumeBranches(incomplete only); terminal
  branch children NOT re-executed; new ParallelBranchStarted only for the
  branch with a real execution transition (W2 test: single EchoOutputCaptured)
- aggregate stale reconstruction (W5/P6-6): CloseFromChildren from
  all-SUCCEEDED evidence, 0 executions
- divergence (W7/P6-7): RejectDivergence before any effect
- ambiguous terminal evidence (P6-8): FAILED child row without a lossless
  semantic carrier -> RejectAmbiguousOutcome (ambiguity != Failure);
  reconstruction is allowed ONLY from all-SUCCEEDED evidence
- non-terminal child evidence (D3 RED->GREEN fix): INCOMPLETE -> ResumeBranches,
  never conflated with ambiguity

## Structured concurrency

- AwaitAll = typed policy (fold failure > unstable > success, lowest index);
  byte-equivalent with the pre-PAR-D join
- FailFast = DESIGNED / NOT EXPOSED / NOT IMPLEMENTED
- supervisorScope = execution mechanism; orphan
  `CoroutineScope(Dispatchers.Default)` removed (fitness-enforced); scope
  exits only when every branch resolves
- CancellationException != INFRASTRUCTURE failure != durable terminal truth
- coroutines do not decide durable truth: durable facts -> pure
  reconciliation -> typed ParallelDecision -> coroutine execution

## Durable aggregate

- one ordinary OperationJournal row, kind COMPOSITE from birth
  (append RUNNING -> append terminal; upsert never repairs kind — trap pinned
  by ParallelAggregateJournalContractTest)
- typed ParallelAggregateId (runId + stageIndex + control kind); no OpId sentinels
- structural fingerprint over branch names; structure change -> divergence
- reuse and close require NO schema migration
- close time persists the exact typed outcome as a lossless semantic carrier

## Observability (E-EM-11 laws preserved)

- reused terminal branch -> no new Started/Finished
- resumed incomplete branch -> lifecycle events only for the actual
  execution transition
- StageStarted < branch events < StageFinished law intact (UatDsl003 Z2)

## Architecture

- ParallelReconciler (pipeline-domain, pure) = decision authority
- CanonicalDurableRunCoordinator = sole effect executor (single writer)
- BranchReconciler production reachability = 0 (inventory: 0 call sites,
  0 constructors, 0 fields, 0 wiring; orphan of the retired
  walkParallelFrame era) -> DELETED with its self-referential test
- ADR-0038/0040 SUPERSEDED; ADR-0042 PARTIALLY SUPERSEDED (observable laws
  kept, mechanism replaced, unconditional resume Started emission retired)
- anti-resurrection fitness: FArchParallelReconciliationAuthorityTest 4/4
  (source absence, symbol ban, single definition/ consumer, no orphan scope)
- direct StepSpec execution = 0; CanonicalDurableRunCoordinator = sole
  production execution authority (FArchLeg1 4/4)
- parallel reconciliation authorities = 1

## Verification inventory

- HF0 ParallelReconcilerTest 13/13 (W0..W7 + P6-1..P6-9 rows)
- ParallelAggregateJournalContractTest 2/2 (COMPOSITE birth + trap pin)
- HF1 ParallelReconcileCoordinatorTest 4/4 (fresh, reuse, close, W2 resume)
- installDist UatDsl003ParallelTest 9/9 incl. P6 durable rerun
- FArchParallelReconciliationAuthorityTest 4/4; FArchLeg1 4/4
- regression sweep GREEN: retry/echo durable suites, UatParallelBlockDurable,
  DurableShellExecutorAdversarialTest 28/28

## Rule 16 exact diff (base cbd0db1a vs PAR-D HEAD)

CanonicalDurableRunCoordinatorTest: base 12/24 failing = head 12/26 failing.
**Same 12 row identities, same root causes (CASE A)** — legacy test
composition without registry metadata wiring; production unreachable
(acceptance UATs run through installDist). Per-row list in the E-EM-11
receipt §KNOWN BASELINE DEBT; verified identical by name.
Other gate-failing suites (deltas vs E-EM-11 baseline ledger: none):
CanonicalRuntimeCapabilityAccess 1, DualExecutionSeam 3,
DurableProtocolInvocation 1, LegacyExecutionAdapter 1, CompatibilityCorpus 1,
UatCompat001 1, UatLocal005 1, UatLocal007 2, UatLocal008 2, UatLocal009 3,
ScriptTextEscaper 3, StepSpec sealed hierarchy 1, WithCredentialsCompile 4.
New failures: 0. Baseline widened: 0.

## Explicit open items

- FailFast: DESIGNED / NOT EXPOSED / NOT IMPLEMENTED
- contextStack parallel branch execution isolation: OPEN follow-up
  (pre-existing; PAR-D does not worsen it — branches remain linear-only,
  fail-closed on non-linear bodies; a reproducible race would reclassify
  this as a blocker)
- The 12 coordinator composition failures remain OPEN baseline debt,
  untouched and not rebaselined.
