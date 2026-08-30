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
- Carry-in: FIND-M4R1-016/022 (roll-forward de M4-R2) si aplican al tocar protocol/step-sdk.

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

## Dependency rule

No empezar E8 por amplitud funcional antes de haber demostrado E4/E5/E6/E7 con el walking skeleton. Hacerlo produciría plugins sobre un runtime aún no validado.
