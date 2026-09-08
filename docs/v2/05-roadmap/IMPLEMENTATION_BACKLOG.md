# Implementation Backlog

El backlog está ordenado por dependencia y riesgo. Los IDs pueden convertirse directamente en issues.

## Epic E0 — Baseline
- **E0-01** Crear source sets/módulos V2 sin `compileKotlin.exclude`.
- **E0-02** Upgrade V2 toolchain a Kotlin 2.4.10.
- **E0-03** Crear CI build/test/document examples.
- **E0-04** Añadir dependency fitness rules.
- **E0-05** Clasificar V1 KEEP/ADAPT/REWRITE/RETIRE.
- **E0-06** Eliminar/renombrar snapshots Repomix obsoletos o regenerarlos desde source actual.
- **E0-07** Alinear README con capabilities verificadas.

## Epic E1 — Domain/Event spine
- **E1-01** Typed IDs Run/Stage/Step/Attempt/Worker/Lease/Event.
- **E1-02** Failure taxonomy.
- **E1-03** Run/Attempt state machines.
- **E1-04** EventEnvelope domain model.
- **E1-05** EventStore port.
- **E1-06** InMemory store.
- **E1-07** SQLite reference store.
- **E1-08** deterministic Clock/IdGenerator ports.
- **E1-09** projector/reducer contract.

## Epic E2 — Kotlin scripting
- **E2-01** `PipelineScriptEngine` SPI.
- **E2-02** Kotlin24 ScriptDefinition.
- **E2-03** explicit classpath builder.
- **E2-04** source diagnostics mapping.
- **E2-05** compilation cache key/storage.
- **E2-06** compiler compatibility corpus harness.
- **E2-07** kotlinc fallback harness.
- **E2-08** stable/RC/EAP matrix workflow.

## Epic E3 — DSL/Step SDK
- **E3-01** pipeline/stages/stage/steps builders.
- **E3-02** environment/post/options.
- **E3-03** agent abstraction.
- **E3-04** context capability API.
- **E3-05** `@Step` v2 annotation.
- **E3-06** StepDescriptor schema.
- **E3-07** KSP descriptor generator.
- **E3-08** generated LSP/docs metadata.
- **E3-09** `echo`, `sh`, `error`, `sleep`.
- **E3-10** JenkinsSurface metadata/compat levels.

## Epic E4 — Durable runtime
- **E4-01** DurableOperation abstraction.
- **E4-02** operation fingerprint.
- **E4-03** result journal.
- **E4-04** replay cursor.
- **E4-05** divergence detection.
- **E4-06** effect/replay policy.
- **E4-07** `script {}` bridge.
- **E4-08** retry Attempts.
- **E4-09** durable timeout.
- **E4-10** parallel Frames/join. **(M3-R4.2 — ✅ CLOSED v0.13.2-rc1 via ADR-0033/0034/0035; 8 commits T-01..T-08, 210/210 tests + 12/12 archtests, 3 NEW domain types OpId.branchIndex + ParallelFrame + BranchSpec; EC-6 kill+resume BEHAVIORAL test deferred to M3-R4.3 — foundation-only per ADR-0035)**
- **E4-19** `OperationJournal.beginOperation` double-suffix fix (HIGH P0 from M3-R4.2, DEBT coup-1/smell-1): when caller passes pre-formatted opId + branchIndex separately, the journal currently double-suffixes op_id and corrupts data. **(M3-R4.3 — ✅ CLOSED v0.13.3-rc1 via ADR-0037 Option A caller-passes-formatted + OpIdContractTest 11→16 cases)**
- **E4-20** `BranchReconciler` real implementation (MEDIUM arch-1 from M3-R4.2): replace stub class with `reconcileRunningOperations()` that queries journal, fetches last durable checkpoint per branch, returns `ReconciledBranch(opId, lastStage, status, suggestedAction)`. **(M3-R4.3 — ✅ CLOSED v0.13.3-rc1 via ADR-0038 + BranchReconcilerTest 8 cases). **NOTE: BranchReconciler implementation present but NOT yet wired into PipelineOrchestrator.run() resume path. Integration deferred to M3-R4.4.****
- **E4-21** `ParallelFrameExecutor` concurrent execution (MEDIUM arch-2 from M3-R4.2): replace no-op stub with `coroutineScope + async(Dispatchers.IO) { ... }.awaitAll()` for ALL_COMPLETE/FIRST_SUCCESS/ANY_COMPLETE join policies. **(M3-R4.3 — ✅ CLOSED v0.13.3-rc1 via ADR-0039 + ParallelFrameExecutorConcurrentTest 6 cases including timing). **NOTE: walkParallelFrame at PipelineRun.kt:1277 still uses sequential `forEachIndexed`. Wiring deferred to M3-R4.4.****
- **E4-22** `executeDurableStep` refactor (MEDIUM arch-3 from M3-R4.2): collapse 11 positional params into a single `DurableWalkContext` data class (clock + opJournal + cursorStore + branchReconciler + opContext). **(M3-R4.3 — ✅ CLOSED v0.13.3-rc1 via DurableWalkContext + DurableWalkContextTest 3 cases)**
- **E4-23** `advancePastParallelFrame` hardcoded strings (LOW smell-2 from M3-R4.2): replace `'parallel-frame'` / `'parallel-frame-completed'` literal cursor keys with runId-derived keys. **(M3-R4.3 — ✅ CLOSED v0.13.3-rc1 via ADR-0035/C-032)**
- **E4-24** `kotlinx-coroutines-core` 1.11.0 dependency in `:pipeline-step-sdk:runtime` (ADR-0039 support dep for T-07). **(M3-R4.3 — ✅ CLOSED v0.13.3-rc1)**
- **E4-25** `UatDurable009KillResumeBranchTest` behavioral test (EC-6, deferred from M3-R4.2): kill+resume test for parallel branches. **(M3-R4.3 — ⚠️ PARTIAL v0.13.3-rc1: 4 scenarios authored (BranchReconcilerTest + UatDurable009 4 cases) but no-replay invariant assertion deferred because BranchReconciler not yet wired into PipelineOrchestrator resume path. On resume all branches re-execute regardless of prior state. M3-R4.4 will wire the reconciler and re-author the no-replay assertion.)**
- **E4-11** durable process task/reattach model. **(M3-R3 — closed v0.12.0-rc1)**
- **E4-12** Replay cursor race fix (DEBT-2026-08-24-REPLAY-CURSOR-RACE, CRITICAL pre-existing M3-R1; WHERE clause on `saved_at` causes 60% flake in same-millisecond overwrite). **(M3-R4.1 — ✅ CLOSED v0.13.0-rc1 via ADR-0030/CAS stage_index)**
- **E4-13** Structured `OpId` data class (F01 HIGH, introduced M3-R3): replace hidden `$runId-s$stageIndex-$stepIndex` parsing in `PipelineRun.kt:234-238` with typed parse/format API or dedicated journal columns. **(M3-R4.1 — ✅ CLOSED v0.13.0-rc1 via C-031 OpId data class + parse/format)**
- **E4-14** Structured `run_id` column in `operation_journal` (F04 HIGH, pre-existing updated): replace `WHERE j.input LIKE '%"runId":"$runId"%'` substring match with indexable column. **(M3-R4.1 — ✅ CLOSED v0.13.0-rc1 via C-032 run_id column + index + backfill)**
- **E4-15** `OperationJournal` single-instance / global-lock pattern (F13 HIGH, pre-existing updated): `synchronized(this)` per-instance fails under multi-instance construction (UatDurable006); needs database-level lock or single-instance contract. **(M3-R4.1 — ✅ CLOSED v0.13.0-rc1 via ADR-0032/C-033 DbLock + busy_timeout)**
- **E4-16** Clock-port cohesion in `:pipeline-application` (coup-002 deferred from M3-R3): route the remaining 23 `Instant.now()` bypass sites through the Clock port to close the systemic debt identified by ADR-0028 §Decision. **(M3-R4.1 — ✅ CLOSED v0.13.0-rc1 via ADR-0031/C-020, 0 Instant.now() remaining)**
- **E4-17** Reconciliation output-field inspection (DEBT-2026-08-24-UAT006-RECONCILE-OUTPUT-NULL, MEDIUM partial M3-R3): currently marks FAILED on terminal status but does not inspect `output` for failure indicators; closes partial fix. **(M3-R4.1 — ✅ CLOSED v0.13.0-rc1 via C-027.1 status-only reconciliation)**
- **E4-18** Machine-derived test counts in `apply-progress.yaml` (DEBT-2026-08-24-APPLY-FABRICATED-COUNTS, MEDIUM acknowledged M3-R3): apply contract amendment to forbid manual counts; counts must come from `./gradlew` output. **(M3-R4.1 — ⚠️ PARTIAL: apply-progress.yaml is machine-derived (E4-18.1 ✅), but prompts/sddk/phases/{apply,verify}.md are external framework symlinks requiring framework-maintainer action (E4-18.2 ❌). Deferred.)**

## M3-R4.4 — branch reconciler wiring integration (CLOSED v0.13.4-rc1)
- **E4-26** Wire `BranchReconciler.reconcileRunningOperations()` into resume path. **(M3-R4.4 — ✅ CLOSED: BranchReconciler IS wired into `walkPipelineSpecDurable`; uses LOCAL instance instead of `ctx.branchReconciler` per ADR-0040 design — functional behavior correct, structural deviation owned by M3-R5)**
- **E4-27** Replace `walkParallelFrame` sequential `forEachIndexed` with concurrent dispatch. **(M3-R4.4 — ✅ CLOSED v0.13.4-rc1: `walkParallelFrame` now uses `coroutineScope { ... }.awaitAll()` via `walkBranchDurable` delegation per ADR-0041)**
- **E4-28** `UatDurable009KillResumeBranchTest` no-replay assertion (EC-6(d)). **(M3-R4.4 — ⚠️ PARTIAL v0.13.4-rc1: counter assertions verified in scenario 1 only (counterFile0/counterFile2 == "1" after Run 2). Scenario 2 had different runIds across Run 1/Run 2 due to test fixture; deferred to M3-R5 with same-spec fixture)**
- **E4-29** Resolve `dup-6` (two reconcileRunningOperations implementations). **(M3-R4.4 — ✅ CLOSED v0.13.4-rc1: inline deleted, `InlinedReconcileDeletedTest` grep assertion PASS)**
- **E4-30** Tighten `beginOperation` API surface (coup-3, MEDIUM carry-forward from M3-R4.3). **(M3-R4.5 — ✅ CLOSED v0.13.5-rc1 via M3-R5: branchIndex parameter completely removed; journal uses opId as-is per ADR-0037 Option A strict)**
- **NEW M3-R4.4 backlog (owned by M3-R5 debt-mop)**:
  - **E4-31** Refactor `walkPipelineSpecDurable` to use `ctx.branchReconciler` instead of LOCAL `BranchReconciler` instance. **(M3-R5 — ✅ CLOSED v0.13.5-rc1: PipelineOrchestrator.run() constructs BranchReconciler and threads via DurableWalkContext; ctx.branchReconciler LIVE; local construction removed)**
  - **E4-32** Add same-spec fixture to UatDurable009 scenario 2 to fully close EC-6(d). **(M3-R5 — ✅ CLOSED v0.13.5-rc1: scenario 2 now uses threeBranchParallelSpec for Run 1+Run 2; counterFile0/counterFile2 == "1" assertions re-enabled)**
  - **E4-33** Remove 12-param executeDurableStep legacy overload (INC-008). **(M3-R5 — ✅ CLOSED v0.13.5-rc1: 9-param ctx overload removed; 12-param retained with @compat permanent KDoc)**
  - **E4-34** Verify ctx.branchReconciler LIVE (FIND-arch-design-dev). **(M3-R5 — ✅ CLOSED v0.13.5-rc1: DurableWalkContextTest asserts ctx.branchReconciler is LIVE and accessible)**

## M3-R5 — debt mop (CLOSED v0.13.5-rc1)
- **Carry-forwards closed in M3-R5** (per M3-R4.4 debt-report.json follow_up + M3-R5 carry-forward-triage.md):
  - 5 findings CLOSED: FIND-coup-new-1 (MEDIUM), FIND-arch-design-dev (MEDIUM), FIND-overeng-3-confirmed (LOW), FIND-coup3 (LOW), E4-28
  - 8 findings ACCEPT-AS-RISK: dup-1/2/3/4 (LOW), mutable-clock (LOW), smell-1/2/3 (LOW)
  - 15 findings ROLL-FORWARD-TO-M4: arch-5/6, overeng-1/2/4, smell-4/5/6/7/9/10/11/12/13/14 (all LOW)
- **INC-008 (LOW P3)**: 12-param executeDurableStep legacy overload retained with @compat permanent KDoc. Accepted as-is; cost of removal exceeds benefit.
- **E4-30**: ✅ CLOSED (branchIndex removed)
- **E4-31**: ✅ CLOSED (ctx.branchReconciler LIVE)
- **E4-32**: ✅ CLOSED (EC-6(d) fully verified)
- **E4-33**: ✅ CLOSED (9-param ctx overload removed)
- **E4-34**: ✅ CLOSED (ctx.branchReconciler LIVE verification)
- **ADR-0037**: Updated to Option A (strict) — branchIndex completely removed
- **EC-6**: ✅ CLOSED — UatDurable009 scenarios 1 and 2 both pass with counter invariants verified
- **Local tag: v0.13.5-rc1** (peels to `1451f7b...`, FF-merged to feat/m3-r5-debt-mop)
- **gap_status=CLOSED**

## Epic E5 — Protocol/Gateway
- **E5-01** `.proto` v1 repo layout/governance. **(M4-R1 — ✅ CLOSED via ADR-0043 + ADR-0044 + F-ARCH-013 + golden fixtures; FArch012 third leg + prefix-matching scanner + 11-import effectiveness test)**
- **E5-02** hello/negotiation.
- **E5-03** commands/events mappings.
- **E5-04** local outbox/ACK.
- **E5-05** reconnect/replay.
- **E5-06** heartbeat/liveness.
- **E5-07** WorkerLease/fencing.
- **E5-08** WebSocket transport.
- **E5-09** Gateway service.
- **E5-10** protocol conformance suite.
- **E5-11** Worker capability trust promotion via governed catalog process. **(BLOCKED — requires product/architecture decision on trust model; no governed catalog exists in E5-01 scope; documented in ADR-0045 §Blocker)**

## Epic ML — Ecosistema de ejecución local (ADR-0046)
> Priorizado sobre E5-02..E5-10 (aplazados). Ámbito local-only, sin controller externo.

- **L-01** ✅ CLOSED — `sh` durable Jenkins-fiel: patrón durable-task (script.sh + result.txt atómico + log fichero + heartbeat + cookie + `-xe`/shebang). **Gate: UAT-LOCAL-001; cierra UAT-REC-002.** (ML-R1 — v0.16.0)
- **L-02** ✅ CLOSED — workspace por stage + environment (PATH/JAVA_HOME/M2_HOME) + `returnStdout` (output file) + timeouts reales. **Gate: UAT-LOCAL-002.** (ML-R2/R3 — v0.17.0)
- **L-03** ✅ CLOSED — sandbox profile local: confinement workspace/env best-effort (ADR-0016). **Gate: UAT-LOCAL-003.** (ML-R3 — v0.17.0)
- **L-04** ✅ CLOSED — credentials provider local + redacción secretos en logs/events/journal. **Gate: UAT-LOCAL-004 (partial UAT-SEC-001).** (ML-R4 — v0.18.0)
- **L-05** ✅ CLOSED — `checkout`/git step. **Gate: UAT-LOCAL-005.** (ML-R5 — v0.19.0)
- **L-06** ✅ CLOSED — steps ecosistema: writeFile/readFile, archiveArtifacts mínimo, wrappers maven/gradle. **Gate: UAT-LOCAL-006.** (ML-R6 — v0.21.0)
- **L-07** ✅ CLOSED — smoke E2E sobre repos reales famosos (Gradle/Maven wrapper). **Gate: UAT-LOCAL-006.** (ML-R8 — v0.22.0)
- **L-09** ✅ CLOSED — Jenkins catalog steps: workflow-control (dir/deleteDir/cleanWs/timeout/retry), error-handling (catchError/warnError/unstable), milestone, utility (pwd/isUnix/load/waitUntil), decorators (timestamps/ansiColor), node no-op + 3-state outcome model. **Gate: UAT-LOCAL-011 + UAT-LOCAL-012 + UAT-LOCAL-013.** (ML-R9 — v0.23.0)
- **L-10 (H0 extract)** — extract `WithCredentialsExecutor` to provider-agnostic hexagonal architecture (zero behavior change). Strategy: new module `:pipeline-credentials-executor` + 4 driven ports in `pipeline-credentials-api/spi/` + `LocalCredentialProvider` adapter wrapping `LocalSecretStore` + `LocalFileMaterialization` extracted from `CredentialMaterializer`. Exit criterion: UAT008 baseline preserved (19 PASS / 8 FAIL byte-identical to pre-H0 XML `579b7ffb…`); `./gradlew -p v2 check` incremental green; FArch001/002/003 continue GREEN; sealed-class counts preserved (StepSpec=28, DomainEvent=39, BoundPurpose=7). Source: cycle `p-733fb505b5a6bd2d/ml-r10-credentials-parity` proposal addendum (replan, 2026-08-30). Owner: apply phase post-replan. Note: closes 0 of the 8 failing UATs (F-1..F-8); H0 is necessary but not sufficient — per-binding-kind provider implementations + audit-emit re-introduction deferred to ml-r10.1.
- Carry-in: FIND-M4R1-016/022 (roll-forward de M4-R2) si aplican al tocar protocol/step-sdk.

### LFC1-followup — INC-021 debt closures (cycle `p-733fb505b5a6bd2d/lfc1-followup-direct`, B-direct, v0.32.2)

Pre-existing follow-up incidences opened during INC-021 archive, plus adjacent canonical step support that the INC-021 release deferred. All CLOSED in v0.32.2 (2026-09-07, head `1b1a5a7c`). See `archive-manifest.md` in cycle artifacts dir.

- **LFC1-01 (INC-022)** ✅ CLOSED — `UatStep003` diagnostics-empty failure: test now asserts exactly one `StepFailed` + empty `RunFinished.diagnostics` (typed runtime-failure contract). Commits: `e1ceca6`. Exit criterion: `UatStep003ErrorAbortTest` PASS.
- **LFC1-02 (INC-023)** ✅ CLOSED — `UatLocal005 RG-004` timeout: canonical `timeout` projection added to `CanonicalDurableRunCoordinator` + `ShOptions.timeoutMs`. Commits: `70e29d4`. Exit criterion: `CanonicalDurableRunCoordinatorTest.timeout*` + `UatLocal005* RG-004` PASS.
- **LFC1-03 (INC-026)** ✅ CLOSED — INC-021b CLI durable UX: `DurableRunPolicy` sealed algebra (`ReusePriorRun` default + `--rerun` flag, mutual-exclusion check), real CLI/SQLite acceptance test. Commits: `307cacc`. Exit criterion: `UatDurableDefaultReuseCliTest` + `MainCliParsingTest` + `FArchLfc1CanonicalBridgeTest` PASS.
- **LFC1-04 (INC-027)** ✅ CLOSED — INC-021c corpus fixtures 06/08/09: `StageScope.sh` preserves `isScriptBlock = false` by default; fixture `06-loop.pipeline.kts` escapes `${'$'}i` per rule 13. Commits: `2fe96cf` (amended with CP-001 corpus-untouched test update). Exit criterion: `CompatibilityCorpusTest` 13/13 PASS + `PipelineDslTopStepsTest` PASS + `UatLocal005CorpusUntouchedTest` PASS.
- **LFC1-05** ✅ CLOSED — canonical `dir` block + shell recovery + continuation: `BlockShellScope` + `CanonicalContinuation` added to `CanonicalDurableRunCoordinator`. Commit: `70e29d4`. Exit criterion: `UatLocal011 SC-011-01` pwd oracle + `CanonicalDurableRunCoordinatorTest` dir/timeout/milestone PASS.
- **LFC1-06** ✅ CLOSED — `warnError`→`Unstable` projection + canonical milestone record-only + in-memory fail-closed gate: `WorkflowControlProjection` enum added; `core.milestone` registered in `ALL_PLUGIN_IDS`; `MilestoneAborted` typed event emitted on out-of-order ordinal; CLI in-memory path applies the same eligibility gate as the durable path. Commit: `78d5814`. Exit criterion: `UatLocal012 warnError` projection 8/0/0 PASS + `UatLocal013 SC-013-02` out-of-order milestone PASS + `CliNonCanonicalInMemoryExitsTwoTest` PASS + `DslCompiledPipelineCompilerTest` PASS + `CanonicalCoreStepCommandRegistryTest` PASS.
- **LFC1-07** ✅ CLOSED — AGENTS.md STEP SEMANTICS (MANDATORY) section codified: Jenkins familiarity (per `docs/v2/00-context/JENKINS_REFERENCE_BASELINE.md`), per-step typed domain events (never return-value-only), fail-closed coverage on every run path (durable + in-memory). Commit: `741ebc0`. Follow-up: ADR draft for STEP SEMANTICS policy (INC-ADR-STEP-SEMANTICS, P2, follow-up cycle recommended).

#### Quarantined observations carried forward (NOT regressed by LFC1-followup, NOT closed)

- **INC-024** — UatLocal011WorkflowControlTest SC-011-04/05/09/10/11/12 (6/13 expected-red, Group F top steps: `load`/`deleteDir`/`pwd`/`cleanWs`/`waitUntil`/`isUnix`) at the eligibility gate. Separate slice required to add canonical step support for each family. **P1, owner: next LFC1.x cycle.**
- **WS-S-006/007/008/009/010** (UatLocal005EnvSpecialCharsTest) — 5 environmental drift failures (asdf Java 24 PATH drift + shell metachars in env values). Pre-existing, not regressed by LFC1-followup.

### v0.33.0 — INC-024 partial closure + INC-baseline-env-drift (cycle `p-733fb505b5a6bd2d/p1a-workflow-control-steps` + `p-733fb505b5a6bd2d/p1b-utility-steps` + `p-733fb505b5a6bd2d/p3-env-drift-fix` + `p-733fb505b5a6bd2d/p2-adr-step-semantics`, B-direct)

INC-024 closed for **5 of 6** Group F top step families; `load` step still quarantined (architectural gap: coordinator does not yet support step-yielding). INC-baseline-env-drift closed (5 of 5 env-special-chars tests). ADR-0069 codifies the STEP SEMANTICS policy that governed this slice. All four cycles on top of v0.32.2 (`c88d5c88`); tag `v0.33.0`.

- **LFC1.1 (INC-024 partial, 5/6)** ✅ CLOSED — `deleteDir` + `cleanWs` + `load` (P1a) + `pwd` + `isUnix` + `waitUntil` (P1b) now have canonical implementations with typed domain events (`DirDeleted`, `WsCleaned`, `LoadEvaluated`, `PwdResolved`, `UnixDetected`, `WaitUntilPolled`, `WaitUntilCompleted`) registered in `CanonicalCoreStepCommand.ALL_PLUGIN_IDS` (7 → 13). UAT-LOCAL-011 SC-011-04/05/09/10/12 now PASS; SC-011-11 (`load`) remains @Disabled pending coordinator support for step-yielding. Commits: `81018559` (P1a + P1b bundled) + `7b76903c` (test alignment). Exit criterion: `CanonicalCoreStepCommandRegistryTest` 13 sealed subclasses + 15 tests PASS; `UatLocal011WorkflowControlTest` 12/12 non-disabled PASS; `CompatibilityCorpusTest` 14/14 PASS (fixture 11 moved from broken to expected-pass).
- **LFC1.2 (INC-baseline-env-drift, 5/5)** ✅ CLOSED — `CanonicalDurableRunCoordinator.projectShellOptions` now merges `EnvironmentSpec.values` into `ShOptions.env`. Root cause: `withCredentials`/`environment { }` block env values were never reaching the subprocess (the stage's `environment` field was being silently ignored). Commit: `c45694b6`. Exit criterion: `UatLocal005EnvSpecialCharsTest` 5/5 PASS (WS-S-006/007/008/009/010).
- **LFC1.3 (INC-ADR-STEP-SEMANTICS)** ✅ CLOSED — ADR-0069 documents the STEP SEMANTICS policy that AGENTS.md added in v0.32.2 as a formal architectural decision. Status: Accepted (2026-09-07). Commit: `f3d05931`. Exit criterion: `docs/v2/04-adrs/ADR-0069-step-semantics-policy.md` + README index entry.
- **LFC1.4 (test alignment)** ✅ CLOSED — `CanonicalCoreStepCommandRegistryTest` bumped to 13 subclasses; `CompatibilityCorpusTest.fixture13` stays in `runtimeFailureFixtures` (timestamps decorator still non-canonical — INC-024 partial); `CliNonCanonicalInMemoryExitsTwoTest` uses `timestamps` instead of `deleteDir` as the non-canonical trigger (deleteDir is now canonical). Commit: `7b76903c`.

#### Quarantined observations carried forward to v0.33.x

- **INC-024 residual** — UAT-LOCAL-011 SC-011-11 (`load` step) remains @Disabled. Coordinator does not yet support step-yielding steps (a `load` step that produces child steps to be injected into the execution flow). Requires coordinator-level changes. **P1, separate slice.**
- **`timestamps` decorator** (INC-024 partial) — fixture 13 stays in `runtimeFailureFixtures` because `timestamps {}` block is not yet canonical. **P2, separate slice.**
- **UatLocal005CheckoutGitTest** — pre-existing git-wrapper fail-closed (no git identity configured in test environment). Not regressed by this slice. **P3.**
- **INC-MLR9-BASELINE-DRIFT** — pre-existing documentation/inventory drift, not a runtime regression.

#### Verification evidence

- 15 fresh JUnit XML canaries across 3 modules: 114 tests total, 108 PASS, 6 fail (= INC-024 quarantined), 0 errors.
- Verdict: PASS (all 10 mandatory gates satisfied).
- Archive manifest: `archive-manifest.md` (sha256 `a35c11d6…`).
- Tag: `v0.32.2` annotated, peels to `1b1a5a7c`.
- **INC-MLR9-BASELINE-DRIFT** (P3) — `v2/compatibility/baseline.json` has 10 `FixtureSnapshot` entries (01..06 + 10..13); missing 07/08/09 from ML-R7. Runtime tests pass: `UatLocal005CorpusUntouchedTest` CP-001 byte-identity + CP-002 fixture count = 13 both PASS — drift is documentation/inventory, NOT a runtime regression. Action (backlog): backfill `baseline.json` with real sha256sum entries for `07-writeFile-readFile.pipeline.kts` + `08-withEnv-pipeline.pipeline.kts` + `09-archive-artefacts.pipeline.kts`; re-run `UatLocal005CorpusUntouchedTest`, `CorpusNormalizerTest`, `CorpusSnapshotDifferTest`, `CompatibilityCorpusTest`, `UatCompat001CorpusSmokeRunTest`; update spec scenario `CB-L9-002` if count widening rationale changes (13 vs 12). Origin: FIND-MLR9-DV-001 (verify S-1).

## Epic E6 — Kubernetes/Credentials
- **E6-01** WorkerProvisioner port.
- **E6-02** WorkerTemplate/WorkerPool manifests.
- **E6-03** K8s Pod provisioner.
- **E6-04** capabilities discovery.
- **E6-05** scheduler hard/soft matching.
- **E6-06** Pod lifecycle/reconciliation.
- **E6-07** security context baseline.
- **E6-08** CredentialProvider port.
- **E6-09** Jenkins credentials adapter.
- **E6-10** K8s/CSI/OIDC projection.
- **E6-11** redaction pipeline.

## Epic E7 — Jenkins adapter
- **E7-01** plugin skeleton modern Jenkins baseline.
- **E7-02** KotlinPipelineDefinition.
- **E7-03** SCM definition.
- **E7-04** KotlinPipelineExecution persistence model.
- **E7-05** FlowStart/End projection.
- **E7-06** Stage/Step FlowNodes/actions.
- **E7-07** log projection/storage.
- **E7-08** cancel/interrupt.
- **E7-09** `onLoad`/restart recovery.
- **E7-10** PodTemplate bridge spike.

## Epic E8 — Plugins
- **E8-01** plugin manifest/API compatibility resolver.
- **E8-02** lockfile.
- **E8-03** git/checkout.
- **E8-04** credentials-binding façades.
- **E8-05** junit parser/result model.
- **E8-06** archiveArtifacts direct upload.
- **E8-07** stash/unstash.
- **E8-08** container/docker.
- **E8-09** Kubernetes helper DSL.
- **E8-10** httpRequest.
- **E8-11** input/approval.
- **E8-12** OCI packaging/signature hook.

## Epic E9 — Graph/Supply-chain
- **E9-01** ExecutionGraph projector.
- **E9-02** ProvenanceGraph projector.
- **E9-03** query API.
- **E9-04** ArtifactStore port/direct upload.
- **E9-05** digest verification.
- **E9-06** SBOM entity/relations.
- **E9-07** provenance generation.
- **E9-08** signature/attestation model.
- **E9-09** deployment relation/policy.
- **E9-10** fork/diff MVP.

## Epic E10 — Production hardening
- **E10-01** OpenTelemetry traces/metrics.
- **E10-02** snapshots/compaction.
- **E10-03** gRPC/mTLS gateway.
- **E10-04** warm pool.
- **E10-05** data-locality scoring.
- **E10-06** chaos suite.
- **E10-07** security assessment.
- **E10-08** performance budgets/SLO.
- **E10-09** upgrade/rollback tests.
- **E10-10** release provenance/SBOM.

## Epic E-EM — Execution Model (ADR-0065; refinement of LFC-4/5/6)

Owned by cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze`
(successor of blocked `lfc4-000`). EM-N ↔ LFC-N.M mapping:
`LOCAL_FOUNDATION_CONSOLIDATION.md §EM ↔ LFC mapping`. E4-25
(UatDurable009) is preserved above as existing parallel durable evidence.

- **E-EM-01 (EM-0/LFC-4.0)** — ✅ CLOSED via v0.30.0 (99e9920)
- **E-EM-02 (EM-1/LFC-4.1)** — ✅ CLOSED via v0.30.0 (99e9920)
- **E-EM-03 (EM-2/LFC-4.2)** — ✅ CLOSED via v0.30.0 (99e9920)
- **E-EM-04 (EM-3/LFC-4.3)** — ✅ CLOSED — Jenkins-faithful sh contract locked (EM-3, 2026-09-06) [JENKINS_SH_CONTRACT.md] [UAT_JENKINS_EXECUTION_PARITY.md]
- **E-EM-05 (EM-4..EM-10 / LFC-4.4..LFC-6.3)** — body steps, durable timeout,
  real retry/catchError, context blocks, production scripted runtime,
  differential gate, legacy removal per EM_DEAD_CODE_AUDIT.md. EM-10 has
  completed its typed active-call-site slice: linear, parallel-branch, and
  branch `withEnv` shell paths now carry `ShellInvocationResult`; only the
  deprecated compatibility adapter remains, pending fixtures and the full
  exit gate.
- **E-EM-06** — ✅ DONE: 91==91 base-vs-head byte-identical at 99e9920
- **LFC-2 (Honest Jenkins-like DSL closure)** — 🟡 IN PROGRESS — itemized list + exit gate in
  `LFC2_HONEST_DSL_CLOSURE.md`; DSL-surface closures landed (stage bookends, G3 naming, ERR-S). Parallel/
  retry/timeout canonical parity deferred to E-EM-11; DSL debt rows tracked there.
- **E-EM-11 (canonical M2-R1 event parity + retry/timeout/parallel semantics)** — 🔲 BLOCKED-ON-EM.
  Evidence 2026-09-08: `dispatchBody` treats `core.retry`/`core.timeout` as generic block bodies —
  iterated ONCE, no retry loop, no deadline, no `RetryAttempt*`/`TimeoutScheduled`. It emits NO
  `ParallelBranchStarted/Finished` and cannot run a `StageBody.Parallel` stage (throws "supports only
  linear stage steps" L276). So the M2-R1 parallel/retry/timeout surface (events AND runtime semantics)
  lives only in the superseded legacy `PipelineRun`, which Main no longer routes to. This is a LF-0208
  spine-migration gap (same family as ERR-S-004 bookends), NOT a DSL-surface defect, and overlaps the
  EM-4..EM-10 "durable timeout, real retry" backlog. Requires real retry/timeout/parallel semantics +
  per-step event projection in the canonical coordinator, plus the composable-vs-stage-body parallel ADR.
  Unblocks quarantined `UatDsl003ParallelTest` + three `UatDsl001JenkinsFamiliarityTest` full-grammar
  methods (LFC-2 T1). Exit: those UATs green on the canonical path; per-step observability (mandate)
  for retry/timeout/parallel. This is a design-gated EM milestone (own ADR/spec change), NOT a bounded
  LFC-2 slice.

### LFC-2 recovery slices (OPEN, 2026-09-08, source base `7c9ce5c7`)

Seven disabled full-grammar/parallel UAT methods remain acceptance obligations, not passed gates.
Design: `openspec/changes/lfc-2-honest-dsl-closure/design.md` (repository-root relative).

| Backlog | Milestone → exit criterion | Gate / owner |
|---|---|---|
| LFC2-H01 | LFC-2 → unsupported retry/timeout blocks, stage retry options and parallel shapes rejected across whole plan before effects | compiler + coordinator admission + proposed Lfc2AdmissionCliTest, application seam |
| LFC2-H02 | LFC-2 → post/when never silently discarded or unconditional; unsupported DSL forms reject before body evaluation | DSL/compiler negative contracts + post/when CLI probes, scripting/application seams |
| LFC2-H03 | LFC-2 → runtime values/polling are real and observable or fail-closed before closure evaluation | pwd/isUnix/waitUntil contracts and runtime-value boundary decision, scripting/domain seams |
| E-EM-11 T1 | LFC-2 dependency → real bounded retry, attempt identity and persisted outcomes | compiler payload, coordinator, journal and real restart UAT, EM runtime seam |
| E-EM-11 T2 | LFC-2 dependency → shared persisted deadline, cancellation/recovery and timeout events | typed clock tests + real CLI kill/resume, EM runtime seam |
| E-EM-11 T3/T4 | LFC-2 dependency → composability decision and seven UAT obligations actually executed green | ADR + compiler/parallel journal contracts + re-enabled original UATs, EM/domain seams |

These are design work units, not authorization to change production in the current diagnostic round.

### LFC-2 reconstitution (step constitution / plugin seam / certification; 2026-09-08, APPROVED)

Change: `openspec/changes/lfc2-step-constitution-plugin-seam`. Authority: ADR-0070..0074 + specs.
This is the extensibility half of the LFC-2 gate; the DSL-surface honesty half stays with
`lfc-2-honest-dsl-closure`. Full slice order/gates in that change's `tasks.md`.

| Slice | Milestone → exit criterion | Gate / owner |
|---|---|---|
| B1 Step Constitution | LFC-2 → sealed `ExecutionNode` + open `StepRegistry` (`StepDefinition`/`StepContract`); fail-closed admission; no per-Step switch | HF0; STEP_CONSTITUTION / ADR-0070 |
| B2 ScenarioRunner | LFC-2 → `.pipeline.kts` executable by ScenarioRunner; four inventories converge | HF1; EXECUTABLE_SCENARIO_CORPUS / ADR-0071 |
| B3 PipelineExtension (HF1) | LFC-2 → pipeline-test-rule tagged HF1 + `StepContractSuite` | HF1; PIPELINE_TEST_HARNESS / ADR-0072 |
| B4 generic Step seam | LFC-2 → Invoke → Registry → typed adapter → handler; capability admission | HF0/HF1; ADR-0070 |
| B5 migrate `echo`+`sh` | LFC-2 → both run via the seam; concrete dispatcher cases deleted | HF1/HF2; ADR-0070 |
| B6 RealPipelineExtension | LFC-2 → HF2 forked real distribution | HF2; ADR-0072 |
| B7 external plugin proof | LFC-2 → external plugin runs with **zero core change** (extensibility gate) | HF2; ADR-0070/0071 |
| B8 Step certification | LFC-2 → StepContractSuite/PluginContractSuite; Step reaches CERTIFIED | HF0..HF2; ADR-0074 / STEP_PLUGIN_CERTIFICATION |
| B9 strict DSL | LFC-2 → DslMarker/scopes/smart constructors; fake-return closure; source fidelity | HF1; DSL_SPEC |
| B10 BodyInvoker/BranchInvoker | LFC-2 → block Steps re-enter engine; no `dispatch*Block` collection | HF1/HF3; ADR-0073 / BLOCK_STEP_EXECUTION |
| B11 dir/withEnv/timestamps | LFC-2 → context-only blocks via BodyInvoker | HF1; ADR-0073 |
| B12 retry/timeout | LFC-2 → real semantics over BodyInvoker (steers E-EM-11 D1/D2) | HF3; E-EM-11 + ADR-0073 |
| B13 composable parallel | LFC-2 → Named Bodies + BranchInvoker; durable branch machinery | HF3; E-EM-11 D3 + ADR-0073 |
| B14 durable scripted runtime values | LFC-2 → real pwd/isUnix; no fake returns | HF1/HF3; ADR-0070/0074 |
| B15 typed when/post | LFC-2 → conditions/post on typed outcomes | HF1; ADR-0074 |
| B16 formal `@KotlinScript` + source fidelity | LFC-2 → formal scripting, source mapping, final corpus | HF1/HF2; ADR-0071 |
| B17 LFC-2 GATE | LFC-2 → `lfc-2-honest-dsl-closure` green AND extensibility proof CERTIFIED | all HF; LFC2_HONEST_DSL_CLOSURE |

These are approved Phase B design/implementation work units (change `lfc2-step-constitution-plugin-seam`,
APPROVED 2026-09-08), executed with per-slice gates per `tasks.md`. LFC-2 stays OPEN until B17.

## Dependency rule

No empezar E8 por amplitud funcional antes de haber demostrado E4/E5/E6/E7 con el walking skeleton. Hacerlo produciría plugins sobre un runtime aún no validado.
