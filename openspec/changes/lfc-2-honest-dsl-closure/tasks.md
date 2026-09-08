# Tasks: lfc-2-honest-dsl-closure (LFC-2)

Evidence: HEAD 65f75fcc (EM-5/6 catchError closed). Confirmed pre-existing DSL failures on clean
Part B: UatDsl001 (full-grammar CLI exit 1 + fixture timeline), UatEvt001 (G3 naming), UatDsl003
(parallel G2 compiler), ERR-S-004 (stage bookends folded). Base = clean Part B (65f75fcc).

## T0 — Triage + itemize LFC-2 — STATUS (2026-09-08)
Root-caused the confirmed pre-existing DSL failures (ran grammar-full/hello via the installed binary,
fresh installDist = not stale):
- **G2 parallel composability (ONE root cause)**: `Stage 'Deploy' cannot mix a parallel body with
  sibling steps` (`DslCompiledPipelineCompiler.stageNode:106`). Root cause of UatDsl001 full-grammar
  ×3 AND UatDsl003. grammar-full stage("Deploy") = `parallel{...}` + sibling `echo`. Design decision:
  make `parallel` a composable step (siblings allowed), Jenkins-scripted style. Needs ADR.
- **ERR-S-004 stage bookends = spine-migration REGRESSION**: UatEvt001 expects exactly 9 events with
  StageStarted@3 + StageFinished@7, and UatDsl001-mutating expects a `StageStarted(stageName=hello)`.
  These were written for the pre-spine runner (PipelineRun) that emitted stage bookends; Main migrated
  to the canonical coordinator (LF-0208), which emits none. Landing StageStarted/StageFinished in the
  coordinator RESTORES the contract and FIXES ERR-S-004 + UatDsl001-mutating + UatEvt001 structure.
- **G3 naming (stale test)**: after bookends, UatEvt001 still fails only at `stepName=="echo"` vs the
  coordinator's `<stage>/<type>-<index>` (`hello/echo-0`, correct G3 contract). Evolve the assertion.
Revised ordering: (a) T3 stage bookends FIRST (restores contract, fixes 3 tests, lowest net blast);
then (b) T2 G3 naming; then (c) T1 parallel G2 + ADR. Remaining tasks updated below.

## Itemize + roadmap gate
- Write the LFC-2 itemized list + exit gate into `docs/v2/05-roadmap` (representative Jenkins
  fixtures compile to expected IR; no fake-return DSL). Mark each LFC-2 gate item present/absent
  (@DslMarker narrow receivers, closed StageBody, .pipeline.kts @KotlinScript, incomplete steps
  post/when/waitUntil/pwd/isUnix, git/scmGit duplicate, shell dollar, durable script {} boundary).

## T3 (do first) — stage bookends restore (ERR-S-004) — ✅ DONE (commit 800f1006)
Land StageStarted/StageFinished in the coordinator run(); update the few coordinator count tests.
Verifies ERR-S-004, UatDsl001-mutating, UatEvt001 structure. All green.

## T2 — step-naming reconciliation (G3, UatEvt001 line ~114) — ✅ DONE (commit 800f1006)
Evolve the `stepName=="echo"` assertion to the `<stage>/<type>-<index>` contract (`hello/echo-0`).
UatEvt001 fully green.

## T1 — parallel composability (G2) + ADR — NEXT, DESIGN-GATED, SPINE-GAP
Fresh ground truth (2026-09-08, binary current): parallel.pipeline.kts + grammar-full Deploy BOTH fail
solely at compile `stageNode:106` ("cannot mix a parallel body with sibling steps"); exit 1, no events.
TWO independent layers:
1. DSL/compiler (stageNode): rejects `parallel{}` + sibling steps (G2).
2. Canonical coordinator (CanonicalDurableRunCoordinator.run:275-276): only executes StageBody.Steps;
   a whole-body StageBody.Parallel stage THROWS "supports only linear stage steps". Main routes
   non-canonical-capable pipelines to the coordinator; the real parallel executor lives ONLY in the
   superseded legacy PipelineRun (emits ParallelBranchStarted/Finished). The LF-0208 spine migration
   never wired parallel into the canonical coordinator => parallel is non-functional on the promoted
   production path (spine gap, same family as ERR-S-004 bookends).
DESIGN FORK (needs ADR + roadmap authority):
- (A) declarative-faithful: `parallel` stays stage-terminal (whole body). Then fixtures mixing
  parallel+sibling are invalid and must be reformulated; STILL requires wiring StageBody.Parallel
  into the canonical coordinator (spine) for a pure-parallel stage to run -> UatDsl003 still blocked.
- (B) scripted-faithful composable step: `parallel` among siblings -> new composable parallel IR node
  + canonical-coordinator concurrency -> larger spine change.
EITHER fork needs canonical-coordinator parallel support (EM durable-runtime spine), which LFC-2
declares Out of Scope (EM track). Honest LFC-2 disposition: reclassify UatDsl001-full-grammar parallel
clause + UatDsl003 as BLOCKED-ON-EM (spine), not DSL-fake; add EM backlog item for canonical parallel;
quarantine/rebaseline the two fixtures per "tests never block legitimate development" until the spine
item lands.

## T4 — remaining LFC-2 gate items
- Incomplete/fake-return steps the gate names (post/when/waitUntil/pwd/isUnix, ...); shell dollar
  handling / source rewriting; durable `script {}` boundary.

## Verify
- UatDsl001/003, UatEvt001, ErrorHandlingTest (ERR-S-004) green.
- Gate: representative Jenkins fixtures compile to expected IR; no fake-return DSL fitness violation.
- Coordinator/EM suites stay green — no unjustified regression.
