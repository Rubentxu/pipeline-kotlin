# E-EM-11 CLOSURE RECEIPT — mandatory event emitters + grammar re-enable

Cycle window: 815a1237..HEAD (post RETRY-D close / ADR-0075 accepted).
Round gate: `timeout 1270 ./gradlew -p v2 check` — 20m48s, NON-ZERO due
exclusively to known baseline failures (ledger below). E-EM-11 change-
scoped gate: PASS, new failures = 0, baseline widened = 0 (rule 16
base-vs-head runs; see Debt section).

## Event mandatory rows (durable/control -> observable projection)

Retry
- RetryAttemptStarted          production (pre-existing) + UAT   PASS
- RetryAttemptFinished         production (815a1237, single
  persistAttemptTerminalTransition boundary; exactly-one per terminal
  transition; replay never fabricates) + UAT T21  PASS
- R1-R6 RETRY-D acceptance matrix              PASS (cycle RETRY-D)
- installDist durable replay (attempt FAILED->SUCCEEDED, reuse)  PASS

Timeout
- TimeoutScheduled             production (30467406, projection at the
  admission boundary: post-decode, effective deadline computed, before
  child execution; exactly one per admitted scheduling)  PASS
- TimeoutScheduled < first child StepStarted   PASS (T22 + dist run)
- invalid payload -> 0 events, 0 children (fail-closed)  PASS (by
  construction, projectShellScope rejects above the emitter)
- expiration/cancellation semantics unchanged  PASS (UatLocal004 4/4)

Parallel
- ParallelBranchStarted/Finished  production + UAT (96f235ab)  PASS
- P1 cardinality, P2 identity pairing, P3 intra-branch ordering,
  P4 outcomes incl. one-branch-failure aggregate, P5 canonical children
  through the single spine, P7, P8 durable identity isolation  PASS
  (UatDsl003ParallelTest 8/8, installDist)
- Z2 StageStarted for parallel stages (22a89c54, classification F):
  exactly one StageStarted at runParallelStage entry, same stage
  identity as StageFinished; law closed:
  StageStarted < ParallelBranchStarted* <= ParallelBranchFinished* <
  StageFinished.  PASS — NOT residual debt.

Grammar
- @Disabled removed unchanged; RED baseline captured (97aa9b9b); all
  REDs classified B (stale fixture).
- grammar-full fixture G3->G1 (a595518f): Deploy pure-parallel; retry/
  timeout exercised via canonical block steps; legacy mixed
  parallel+sibling fixture preserved as G2 negative (fail-closed, exit 1).
- Z1 AgentResolved (classification D, stale M2-R1 local-runtime
  assertion): assertion removed. AgentResolved remains a supported
  DomainEvent/schema variant for future REAL agent resolution
  (requested -> resolver/scheduler -> resolved target -> AgentResolved).
  Not a missing emitter; no fake configured==resolved emitter added.
- UatDsl001JenkinsFamiliarityTest 4/4 GREEN (installDist).

Replay policy
- NEVER-1 (4a34d727, classification A): ReplayPolicy.NEVER means
  "never re-execute journaled history"; fresh invocations execute.
  Fixed in the generic authority EffectReplayPolicy.decide (no
  stepKey special-case). Doc table updated. RERUN-as-"execute now" is
  documented naming debt.
- core.error fresh -> typed failure (FailureKind from payload, e.g.
  USER + message), journal FAILED; NOT INFRASTRUCTURE 'Replay aborted'.
- core.error journaled re-execution -> canonical INFRASTRUCTURE
  'Replay aborted' rejection, 0 handler executions (coordinator law).
- UatStep003ErrorAbortTest hardened (false green eliminated).
- Effects.ABORTS_PIPELINE (step semantics) remains distinct from
  ReplayDecision.ABORT (admission rejection).
- Follow-up (latest commit): stale row in ReplayOutputDecouplingTest
  updated to the corrected contract (classification D, test-only).

## Architecture
- direct StepSpec execution                    = 0
- production execution authorities             = 1
  (CanonicalDurableRunCoordinator; Z2 emitter lives inside it)
- core.echo / core.sh / example.uppercase      = CERTIFIED (unchanged)
- certified Step count change in E-EM-11       = 0

## KNOWN BASELINE DEBT — NON-BLOCKING (rule 16, NOT E-EM-11 regressions)

CanonicalDurableRunCoordinatorTest: 12/26 failures, identical set at
base production code (rule-16 runs recorded in 4a34d727 message and
gate logs). Cause: legacy test composition without registry-aware
metadata wiring ("No canonical core metadata registered for
core.echo/core.sh") — production unreachable; the acceptance UATs run
through installDist with the production composition. Rows:
- Exactly-once discipline - 3 steps emits 3 StepStarted/StepFinished
- withCredentials cleanup failure folds a successful body to failure
- withCredentials acquires scope overlays env and always closes
- journals a supported block child with its body path
- ReplayDecision ABORT emits one failed lifecycle without dispatching
- journals and checkpoints a linear canonical echo run
- dispatch decodes each StepNode before delegating to the typed dispatcher
- run emits StepFinished after dispatch
- run emits StepStarted before dispatch
- No step events for ReplayDecision SKIP
- dispatch returns SCHEMA for a fresh structurally-valid but typed-invalid
- continues after a default catchError failure and returns unstable

Other gate-failing suites (outside the E-EM-11 impact closure; delta
production files = CanonicalDurableRunCoordinator.kt,
EffectReplayPolicy.kt only): CompatibilityCorpusTest(1),
UatCompat001CorpusSmokeRunTest(1), UatLocal005CheckoutGitTest(1),
UatLocal007SandboxProfileTest(2), UatLocal008CredentialsTest(2 —
documented pre-existing at 815a1237, rule 16), UatLocal009TopStepsTest(3),
PipelineDslSealedHierarchyTest(1), ScriptTextEscaperTest(3),
WithCredentialsCompileIntegrationTest(4).

## OPEN SEPARATE DECISIONS (explicitly NOT closed by E-EM-11)
- P6 parallel durable replay/reuse policy: OPEN. E-EM-11 proved event/
  runtime parity; it defined no reuse guarantee. Any fitness may assert
  only: reuse paths that occur MUST NOT fabricate branch events.
- Post-E-EM-11 Kotlin deepening backlog: ParallelFailurePolicy
  (FailFast/AwaitAll typed), Retry reconciliation ADT refinement, typed
  Deadline, Flow-based output observability, context-parameter
  capabilities, "coroutines execute the plan; durable truth decides"
  (B13 objective).
- Candidate AGENTS.md law (docs-only follow-up, validated by NEVER-1):
  "Replay policy governs reuse/re-execution of existing durable history;
  it must not suppress a legitimate first execution unless admission
  denial is explicitly part of that policy."
