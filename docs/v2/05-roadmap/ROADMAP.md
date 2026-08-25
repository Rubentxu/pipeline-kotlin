# Roadmap V2 — Desarrollo evolutivo guiado por UAT

## Estrategia

El roadmap no sigue “primero domain, después infra, después UI”. Sigue **vertical slices** que atraviesan DSL→runtime→eventos→storage→adapter y terminan en una capacidad demostrable.

Cada milestone tiene cinco gates:

1. **Architecture Gate** — dependencias y ADR respetados.
2. **Quality Gate** — tests/coverage relevante + static analysis.
3. **Compatibility Gate** — schemas/DSL corpus.
4. **Operational Gate** — métricas, logs, failure handling.
5. **UAT Gate** — escenario observable aprobado.

## M0 — Baseline honesta y carril V2

### Objetivo
Poder construir y medir V2 sin seguir escondiendo errores mediante excludes.

### Entregables
- Kotlin 2.4.10 en V2 modules.
- CI real para módulos V2.
- clasificación KEEP/ADAPT/REWRITE/RETIRE de V1.
- nueva estructura de módulos base.
- architecture fitness tests.
- compatibility matrix inicial.
- docs V2 incorporadas.

### No hacer
No “arreglar todo V1” antes de empezar V2.

### Exit/UAT
- `./gradlew check` de V2 green.
- ningún source V2 excluido del compile task.
- UAT-M0-001: sample hello pipeline compila/valida de forma reproducible.

## M1 — Kotlin Scripting vertical + event spine

### Objetivo
Ejecutar `.pipeline.kts` mínimo con Kotlin 2.4.10 y emitir historia estructurada.

### Entregables
- `pipeline-domain`, `application`, `scripting-api`, `scripting-kotlin24`.
- ScriptDefinition con classpath explícito.
- events Run/Compilation.
- event store in-memory + SQLite reference.
- `pipeline validate` y `pipeline run` local mínimos.
- compiler cache key.

### Exit/UAT
- UAT-COMP-001/002: compile success/error source-mapped.
- UAT-EVT-001: run reconstruible desde event log.
- no `wholeClasspath=true` en production path.

## M2 — DSL Jenkins-familiar + Step SDK V2

### Objetivo
Conseguir la experiencia visible de producto antes de distribución.

### Entregables
- `pipeline/stages/stage/steps/post/environment/agent`.
- context parameters.
- KSP StepDescriptor generation.
- Steps: echo, sh, error, sleep.
- Jenkins Familiarity metadata.
- LSP metadata básica.
- compatibility corpus.

### Exit/UAT
- UAT-DSL-001..005.
- UAT-STEP-001..004.
- pipeline Jenkins-like compila sin compiler plugin FIR/IR.

## M3 — Worker runtime local + durable replay

### Objetivo
Demostrar la tesis diferencial: Kotlin dinámico durable sin CPS.

### Entregables
- worker runtime local;
- local journal SQLite WAL;
- durable operation fingerprints/results;
- `script {}`;
- retry/timeout/parallel frames;
- recovery algorithm;
- execution graph projection local.

### Exit/UAT
- matar worker después de `sh` completado y recuperar sin repetirlo;
- replay divergence fail-closed;
- parallel/retry state reproducible.

### Sub-cycles

#### M3-R3 — durable process task/reattach model — **closed v0.12.0-rc1**
Cubrió exit criterion puntos 1 y 2 (kill+recover sin replay; divergence fail-closed).
9 commits atómicos + 1 remediación; 18/18 escenarios COMPLIANT; 129/130 tests pass.
Debt cerrado: 4 entradas M3-R3; roll-forward 3 (E4-12, E4-17, E4-18).
Releases: local-only per HANDOFF §10, gap_status RELEASED_WITH_GAPS.

#### M3-R4 — cerrar M3 exit criterion y mopa de deuda
**Objetivo**: cerrar el exit criterion completo de M3 (los 3 puntos) y resolver la
deuda surgical de M3-R3 + el systemic debt de Clock-port cohesion.

**Sub-ciclos** (uno por SDDK cycle):

- **M3-R4.1 — debt-mop** (path A-lite): ✅ **CLOSED** (v0.13.0-rc1, 2026-08-24)
  - E4-12 Replay cursor race fix (CRITICAL) — ✅ closed
  - E4-13 OpId estructurado (F01 HIGH) — ✅ closed (via ADR-0030/C-031)
  - E4-14 `run_id` column estructurado (F04 HIGH) — ✅ closed (via ADR-0030/C-032)
  - E4-15 Single-instance / global-lock contract (F13 HIGH) — ✅ closed (via ADR-0032/C-033)
  - E4-16 Clock-port cohesion en `:pipeline-application` (23 sitios, coup-002) — ✅ closed (via ADR-0031/C-020)
  - E4-17 Reconciliation output inspection (cierra parcial) — ✅ closed (C-027.1)
  - E4-18 Apply contract amendment (machine-derived counts, sin código) — ⚠️ PARTIAL (framework-side symlink deferred)
  - 13 debt items rolled forward to M3-R5; M3-R4.2 deferred
  - Exit/UAT: 197/197 tests pass (0 flake), 0 CRITICAL/HIGH open
  - ADR-0030 (CAS), ADR-0031 (Clock), ADR-0032 (DbLock) authored
  - Local tag: v0.13.0-rc1; gap_status=RELEASED_WITH_GAPS per HANDOFF §10

- **M3-R4.2 — parallel Frames/join** (path A-full, exit criterion blocker):
  - E4-10 parallel Frames/join (ROADMAP.md:88-91 punto 3).
  - Exit/UAT: `parallel/retry state reproducible` verificado vía UAT;
    no regresiones en single-frame execution.
  - Este sub-ciclo sale de scope de M3-R4.1 por ser feature sustantiva
    (cambia runtime shape), no deuda surgical.
  - **✅ CLOSED 2026-08-24** (cycle `p-733fb505b5a6bd2d/m3-r4-2-parallel-frames`, A-full)
  - 8 commits (T-01..T-08), 13 files changed, +1320/-18 LOC
  - 210/210 tests pass + 12/12 archtests, V1 hygiene PASS, namespace `dev.rubentxu.pipeline.v2.*` compliant
  - Local tag: **v0.13.2-rc1** (peels to `64147798b1546726501aa85d9e4c5ad7340a1052`, FF-merged to main)
  - Remote: `origin/main` synced to 64147798, tag pushed
  - 3 NEW ADRs (ADR-0033 ParallelFrame, ADR-0034 OpId branchIndex, ADR-0035 advancePastParallelFrame)
  - 2 NEW domain types: `ParallelFrame`, `BranchSpec` (in `:pipeline-domain`)
  - V2 capability deltas: 4 NEW (C-..) + 4 MODIFIED (per proposal.md)
  - Debt: 27 findings (14 introduced + 13 carried forward). PASS_WITH_WARNINGS.
  - EC-1..EC-5 COMPLIANT. **EC-6 (kill+resume behavioral test) DEFERRED to M3-R4.3** (foundation-only per ADR-0035).
  - 14 introduced findings (HIGH beginOperation double-suffix P0; MEDIUM BranchReconciler stub P1; MEDIUM ParallelFrameExecutor stub P1; MEDIUM executeDurableStep 11-params P1; LOW advancePastParallelFrame hardcoded strings P1; 9 LOW cosmetic) → M3-R4.3 follow-up.
  - 13 carry-forward (4 dupl + 7 smells + 2 overeng from M3-R4.1 baseline) → M3-R5 debt-mop follow-up.
  - gap_status=CLOSED (released, archived, ledger 277 events valid)

- **M3-R4.3 — branch reconciler wiring** (path A-full, exit criterion blocker):
  - E4-19 beginOperation double-suffix fix (HIGH P0 from M3-R4.2) — closed (C-026 via ADR-0037)
  - E4-20 BranchReconciler real implementation (MEDIUM arch-1 from M3-R4.2) — closed (C-027 via ADR-0038)
  - E4-21 ParallelFrameExecutor concurrent execution (MEDIUM arch-2 from M3-R4.2) — closed (C-030 via ADR-0039)
  - E4-22 executeDurableStep refactor (MEDIUM arch-3 from M3-R4.2) — closed (C-029 via DurableWalkContext)
  - E4-23 advancePastParallelFrame hardcoded strings (LOW smell-2 from M3-R4.2) — closed (C-032 via ADR-0035)
  - E4-24 kotlinx-coroutines-core 1.11.0 added to `:pipeline-step-sdk:runtime` (ADR-0039)
  - E4-25 UatDurable009KillResumeBranchTest behavioral test (EC-6) — **PARTIAL** (4 scenarios authored, but assertion of no-replay invariant deferred because BranchReconciler not yet wired into PipelineOrchestrator resume path; on resume all branches re-execute regardless of prior state)
  - **✅ CLOSED 2026-08-24** (cycle `p-733fb505b5a6bd2d/m3-r4-3-branch-reconciler-wiring`, A-full)
  - 8 commits (T-01..T-08), 23 files changed
  - 238/238 tests pass + 12/12 archtests, V1 hygiene PASS, namespace `dev.rubentxu.pipeline.v2.*` compliant
  - Local tag: **v0.13.3-rc1** (peels to `de25b8285c4cc58d8cb48c99748119cb0456ba04`, FF-merged to main)
  - Remote: `origin/main` synced to de25b82, tag pushed
  - 3 NEW ADRs (ADR-0037 beginOperation contract, ADR-0038 BranchReconciler re-attachment, ADR-0039 ParallelFrameExecutor structured concurrency)
  - 1 NEW domain type: `DurableWalkContext` (in `:pipeline-application`)
  - 1 NEW branch reconciler: `BranchReconciler.reconcileRunningOperations()` with 3 status outcomes (success/needsReattach/stuck)
  - 1 NEW executor: `ParallelFrameExecutor` with coroutineScope + async(Dispatchers.IO) for ALL_COMPLETE/FIRST_SUCCESS/ANY_COMPLETE
  - V2 capability deltas: 6 MODIFIED (C-026/027/029/030/031/032) + 3 NEW (C-034/035/036)
  - Debt: 32 findings (11 introduced: 2 HIGH arch-4/arch-7 + 3 MEDIUM dup-6/coup-3 + 6 LOW; 21 carried forward). PASS_WITH_WARNINGS.
  - EC-1..EC-5 COMPLIANT. **EC-6 (kill+resume behavioral test) PARTIAL** — 4 scenarios authored but no-replay invariant deferred to M3-R4.4 because BranchReconciler implementation not wired into PipelineOrchestrator.run() resume path.
  - **2 introduced HIGH (deferred to M3-R4.4):**
    - arch-4: BranchReconciler not integrated into PipelineOrchestrator.run() resume path. On resume, all branches re-execute regardless of prior state. P0.
    - arch-7: walkParallelFrame at PipelineRun.kt:1277 still uses sequential `forEachIndexed` despite T-07 ParallelFrameExecutor concurrent implementation. P0.
  - **3 introduced MEDIUM (deferred to M3-R4.4):**
    - dup-6: two `reconcileRunningOperations` implementations coexist (BranchReconciler class + inline private fun at PipelineRun.kt:229-317 with TODO[M3-R4.3] comment)
    - coup-3: beginOperation branchIndex parameter is redundant (dual-encoding bug fixed, dual API surface remains)
    - smell-8: 21-parm executeDurableStep overload retained (carry-forward from arch-3 closure)
  - **6 introduced LOW:** arch-5/6, overeng-3/4, smell-7/9 — cosmetic
  - 21 carry-forward (8 from M3-R4.2 still open + 13 from M3-R4.1 baseline) → M3-R5 debt-mop follow-up
  - gap_status=CLOSED (released, archived, ledger 297 events valid)

## M3 — Milestone Closure

**M3 is now 100% COMPLETE** (2026-08-24):

| Sub-cycle | Path | Status | Tag | Commit | Closure date |
|---|---|---|---|---|---|
| M3-R1 — durable foundation | A-lite | ✅ CLOSED | — | cf0eb55… | 2026-08-23 |
| M3-R2 — script retry/timeout | A-lite | ✅ CLOSED | — | — | 2026-08-24 |
| M3-R3 — kill-after-shell recovery | A-lite | ✅ CLOSED | v0.12.0-rc1 | bd06509e | 2026-08-24 |
| M3-R4.0 — V2 namespace migration | A-lite | ✅ CLOSED | v0.13.1-rc1 | 6b816be | 2026-08-24 |
| M3-R4.1 — debt mop (run_id/WAL/DbLock/Clock/CAS) | A-lite | ✅ CLOSED | v0.13.0-rc1 | f3f0560 | 2026-08-24 |
| M3-R4.2 — parallel Frames/join (foundation) | A-full | ✅ CLOSED | **v0.13.2-rc1** | 64147798 | 2026-08-24 |
| M3-R4.3 — branch reconciler wiring | A-full | ✅ CLOSED | **v0.13.3-rc1** | de25b828 | 2026-08-24 |
| M3-R4.4 — branch reconciler wiring integration | A-full | ✅ CLOSED | **v0.13.4-rc1** | 185506c | 2026-08-25 |

**M3 exit criteria status**:
1. ✅ Kill-after-shell-recovery (M3-R3) — gap lifted post-remote-integration
2. ✅ WAL+DbLock+Clock port + run_id column (M3-R4.1) — gap lifted
3. ✅ Parallel Frames/join foundation (M3-R4.2) — EC-1..5 compliant
4. ✅ Parallel Frames/join wiring (M3-R4.4) — EC-1..5 compliant; **EC-6 CLOSED** — UatDurable009 scenario 1 + 2 both pass; same-spec fixture validates no-replay invariant (counterFile0/counterFile2 == "1" after resume). **M3 fully closed end-to-end on the durable-execution axis.**

**Unblocks M4** (Protocol + Gateway) per ROADMAP.

## M4 — Protocol + Gateway

### Objetivo
Separar físicamente control plane y worker.

### Entregables
- Protobuf v1;
- binary WebSocket transport;
- gateway;
- ACK/replay;
- heartbeats;
- leases/fencing;
- protocol compatibility suite;
- optional gRPC spike.

### Exit/UAT
- disconnect/reconnect sin pérdida;
- duplicate events idempotentes;
- old fencing token rechazado;
- cancel remote run.

## M5 — Kubernetes ephemeral workers + credentials

### Objetivo
Ejecutar el walking skeleton en Pods efímeros con identidad/credenciales seguras.

### Entregables
- WorkerProvisioner Kubernetes;
- WorkerPool/ExecutionProfile manifests;
- capabilities scheduler básico;
- Pod lifecycle events;
- Jenkins/Kubernetes credential providers;
- workload identity/CSI path;
- container security profile.

### Exit/UAT
- Pod por run;
- kill/evict Pod y retry/recovery según policy;
- secret no aparece en logs/events;
- `inheritFrom/yaml/defaultContainer` demostrables.

## M6 — Jenkins Workflow engine plugin

### Objetivo
Hacer de Jenkins un adapter real y visible.

### Entregables
- Kotlin Pipeline / Kotlin Pipeline from SCM;
- FlowDefinition/FlowExecution;
- event→FlowNode reducer;
- logs/status/cancel;
- controller restart recovery;
- Jenkins credential adapter;
- Kubernetes PodTemplate bridge spike/adaptación.

### Exit/UAT
- run visible en Jenkins UI;
- controller no compila user pipeline;
- controller restart conserva run;
- worker activity visible como Stage/Step.

## M7 — Ecosistema plugins familiar

### Objetivo
Cubrir el 80% de pipelines reales sin depender de plugins Jenkins.

### Plugins iniciales
- git/checkout;
- credentials-binding;
- junit;
- artifacts/stash;
- docker/container;
- kubernetes helpers;
- httpRequest;
- input/control.

### Entregables
- plugin manifest + lockfile;
- OCI packaging prototype;
- plugin compatibility matrix;
- migration recipes.

### Exit/UAT
Aplicación demo completa checkout→build→test→artifact→approval con sólo plugins V2.

## M8 — Graph/Provenance/Supply-chain + Gateway gRPC

### Objetivo
Convertir historia en conocimiento operacional y escalar conexiones.

### Entregables
- global Execution/Provenance projectors;
- graph query API;
- artifact direct upload;
- SBOM/provenance/signature relations;
- fork/diff MVP;
- gRPC/mTLS gateway production path;
- data-locality scheduler hints.

### Exit/UAT
- preguntar “qué commit produjo este artifact” y obtener chain verificable;
- fork de run seguro + diff;
- 100/500/1000 worker session load tests según entorno.

## M9 — Hardening, performance y policies

### Objetivo
Preparar release candidate.

### Entregables
- chaos suite;
- security profiles;
- policy engine;
- event snapshots/compaction;
- warm pools/caches;
- performance budgets;
- upgrade/rollback runbooks.

### Exit/UAT
- UAT master crítica 100%;
- SLO targets alcanzados en benchmark environment;
- security review sin Critical/High abiertas.

## M10 — V2.0 GA y migración

### Objetivo
Release estable con camino claro desde V1/Jenkins.

### Entregables
- semver/public compatibility policy;
- migration CLI alpha;
- docs/recipes;
- supported matrix;
- deprecation plan V1;
- release artifacts/SBOM/provenance.

### Exit
- dos aplicaciones de referencia migradas;
- upgrade/rollback ensayado;
- release candidate soak estable;
- UAT business/developer sign-off.

## UAT-M0-001 vs UAT-COMP-001 Disambiguation
- **UAT-M0-001** (M0 exit): V2 minimal API + HelloPipeline fixture
  reproduces the build greenly; no `.pipeline.kts` Custom Scripting involved.
- **UAT-COMP-001** (M1 exit): first real `.pipeline.kts` with
  `pipeline/stage/sh` compiled via Custom Scripting, diagnostics empty,
  artifact/cache identity recorded. First M1 exit, not M0.
