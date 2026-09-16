# Proposal: wu-g5-restore — make the canonical RepeatUntil path reachable from the public `waitUntil` DSL

## Intent

Make `BlockShellScope.RepeatUntil → dispatchRepeatUntilBody → WaitUntilReconciler` reachable from the real public DSL. `core.waitUntil` is structural orchestration, NOT a registry Step (AGENTS.md §STEP CONSTITUTION, ADR-0073). G5a canonical-implementation work is RETAINED; the G5 closure claim is INVALIDATED (commit `a31b2fa4` → `docs/v2/07-uat/B14_WAITUNTIL_G5A_INVALIDATION_RECEIPT.md`). Positive evidence our gates catch fake-green.

## Scope

**In**: `PipelineDsl.kt` `WaitUntil` → `WaitUntilBlock(initialRecurrencePeriod, body: List<StepSpec>)`; `waitUntil` fun CAPTURES lambda as body list (no eager eval). `BodyExecutionPolicy` new closed case `RepeatUntil(policy: RepeatUntilPolicy)` (5th after `Sequential/Scoped/Retrying/Parallel`). `DslCompiledPipelineCompiler.kt` explicit `is StepSpec.WaitUntilBlock -> blockStepNode(...)` (kills `else -> OpaqueStepNode("core.waitUntil", ...)` for this key). `BodyExecutionProjection.Until` variant (B11 W3b pattern). New `examples/22-wait-until.pipeline.kts`. 3 fitness + 1 RED characterization. Receipt `WU_G5_RESTORE_CLOSURE_RECEIPT.md`.

**Out**: `CanonicalWaitUntilNodeDispatcher.kt` (WU-G5B / `LEGACY_REMOVED`). `CoreStepRegistryFactory` (waitUntil is NOT a registry Step). `LEGACY_PLUGIN_IDS`, `CanonicalCoreStepDecoder` rows, `CanonicalCoreStepMetadata` rows (WU-G5B). `WaitUntilReconciler` / `FileBasedWaitUntilControlJournal` / `dispatchRepeatUntilBody` body — RETAIN from G5a. Pre-existing reds (`CanonicalDurableRunCoordinatorTest 26/11`, `CompatibilityCorpusTest 20/2`, `Lfc0GlobalStateFitnessTest 1`, UAT 005/007/008/009, fixture14) — DO NOT widen.

## Capabilities

> Contract with `sddk-spec`. Reuse vault names.

### New Capabilities

- `core.waitUntil-canonical-reentry` (B14 lane): public `waitUntil { body }` DSL lowers to `BlockStepNode(BodyExecutionPolicy.RepeatUntil)`; canonical dispatch reaches `dispatchRepeatUntilBody`; reconciler emits `WaitUntilPolled`/`WaitUntilCompleted`.

### Modified Capabilities

- `BodyExecutionPolicy`: new closed case `RepeatUntil(val policy: RepeatUntilPolicy)` (B10 W1d, B11 W3b).
- `StepSpec` algebra: `WaitUntilBlock(initialRecurrencePeriod, body: List<StepSpec>)` (replaces terminal `WaitUntil`; see `explore.md` open question #1).
- `dsl-compiled-pipeline-compiler`: explicit case for `WaitUntilBlock`; `else`/`OpaqueStepNode` no longer fires for `core.waitUntil`.

## Approach

Four atomic micro-slices, incremental verification per AGENTS.md V2 TESTING RULES.

| Slice | Purpose | Evidence |
|---|---|---|
| WU-G5R.1 | RED characterization: `Lfc2WaitUntilDslCanonicalProjectionTest` — expected `BlockStepNode(BodyExecutionPolicy.RepeatUntil)`, actual `OpaqueStepNode("core.waitUntil")` | Fails today for the EXPECTED reason |
| WU-G5R.2 | DSL: rename `WaitUntil` → `WaitUntilBlock`; capture lambda as `List<StepSpec>` | `:pipeline-scripting-api:test` |
| WU-G5R.3 | Compiler: explicit `WaitUntilBlock` case + `BodyExecutionProjection.Until` | `Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest` GREEN; `NoLegacyRouting` RED (intentional) |
| WU-G5R.4 | E2E: `examples/22-wait-until.pipeline.kts`; installed CLI; Event Harness | `Lfc2WaitUntilCanonicalReentryFitnessTest` GREEN |

Reconciler/journal/dispatch body RETAINED from G5a; RESTORE only verifies reachability. NO production semantic changes in canonical reconciliation code.

## Quality Intent

- **Production surfaces**: `PipelineDsl.kt`, `BodyExecutionPolicy.kt`, `DslCompiledPipelineCompiler.kt`, `BodyExecutionProjection.kt`, `22-wait-until.pipeline.kts`.
- **Changed public APIs**: `waitUntil` signature (terminal → body-bearing), `StepSpec.WaitUntil` → `WaitUntilBlock`, new `BodyExecutionPolicy.RepeatUntil`.
- **Readiness dimensions**: behavioral (fitness + characterization), architectural (W3b exhaustiveness, W1a pinned = 0), observable (`WaitUntilPolled`/`Completed` timeline).
- **Required real boundaries**: installed-CLI dispatch (real `.pipeline.kts`), pure reconciler unit tests (already green).

## Architecture Impact

- **Level**: boundary. `BlockStepNode` body propagation crosses DSL → compiler → coordinator; B11 W3b becomes the enforcement point for the new variant; B10 W1a pinned = 0 (no new `dispatch*Block` literal).
- **Intent**: A-lite W1d-extension — one ADT case + one StepSpec variant + one compiler case. Analogous to B11 W3b (`WithEnv` + `Timestamps` projections).

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `v2/pipeline-scripting-api/.../PipelineDsl.kt` | Modified | `WaitUntil` → `WaitUntilBlock`; lambda capture |
| `v2/pipeline-domain/.../BodyExecutionPolicy.kt` | Modified | `RepeatUntil(policy)` + `RepeatUntilPolicy` |
| `v2/pipeline-application/.../DslCompiledPipelineCompiler.kt` | Modified | Explicit `WaitUntilBlock` case → `blockStepNode` |
| `v2/pipeline-application/.../BodyExecutionProjection.kt` | Modified | `Until` variant (B11 pattern) |
| `v2/compatibility/22-wait-until.pipeline.kts` | New | `waitUntil(period) { sh("test -f marker") }` |
| `v2/pipeline-application/test/.../Lfc2WaitUntilDslCanonicalProjectionTest.kt` | New | RED characterization |
| `v2/pipeline-architecture-tests/.../Lfc2WaitUntil*FitnessTest.kt` | New | 3 fitness tests |
| `docs/v2/07-uat/WU_G5_RESTORE_CLOSURE_RECEIPT.md` | New | Closure receipt |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| `WaitUntilBlock` forgets body propagation → W3b exhaustiveness fails | Med | Add fitness BEFORE compiler change; verify RED → GREEN on the slice |
| `NoLegacyRouting` accidentally GREEN before legacy removed | High (intentional) | Fitness explicitly RED during RESTORE; doc state in closure receipt |
| Eager-evaluation regression slips back into DSL fun | Med | `waitUntil` captures `body: () -> List<StepSpec>`, never calls `body()` at construction |
| Reconciler/journal invariants regress from a "polish" PR | Med | Slice is read-only on those files; design reaffirms; debt-verify catches it |
| `core.waitUntil` re-enters `CoreStepRegistryFactory` | Low | Pre-flight grep + fitness; AGENTS.md §STEP CONSTITUTION forbids |
| Pre-existing reds widen | Med | L4/L5 gate once per apply/verify round; baseline locked by W1a..W1d receipts |

## Rollback Plan

`git revert <wu-g5-restore-merge-commit>` restores `StepSpec.WaitUntil` (terminal) and the `else -> OpaqueStepNode` fallback. NO schema migration; NO durable state change (no journal/control row added during RESTORE — they exist from G5a). One revert reaches last-known-green (`7845b3e7`). `NoLegacyRouting` reverts to its pre-slice intentionally-RED state.

## Dependencies

- ADR-0073 (BodyInvoker re-entry — block steps re-enter via `BodyInvoker.invoke`).
- B10 W1d closed ADT (`BodyExecutionPolicy` is closed; `RepeatUntil` is a case, not a flag).
- B11 W3b exhaustiveness fitness (`Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`).
- AGENTS.md §STEP CONSTITUTION (structural constructs are NOT registry Steps).
- AGENTS.md §10 (DSL describes; interpreters execute — no eager body eval).
- Invalidation receipt (commit `a31b2fa4`) — referenced, not rewritten.
- Inventory (`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`) — `core.waitUntil` updated on closure: `Path = structural`, `StepDefinition = N/A`, `Canonical = Y`, `Legacy = Y (until WU-G5B)`.

## Success Criteria

- [ ] `Lfc2WaitUntilDslCanonicalProjectionTest` fails RED today; GREEN after WU-G5R.3.
- [ ] `Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest` GREEN after WU-G5R.3.
- [ ] `Lfc2WaitUntilCanonicalReentryFitnessTest` GREEN after WU-G5R.4.
- [ ] `Lfc2WaitUntilNoLegacyRoutingFitnessTest` RED during RESTORE (intentional; flips GREEN only after WU-G5B).
- [ ] `examples/22-wait-until.pipeline.kts` exits 0 via installed CLI; event harness records `WaitUntilPolled`/`Completed` from canonical path (not legacy stub).
- [ ] `STEP_INVENTORY_LFC2E0.md` `core.waitUntil` row updated: `IMPLEMENTED_UNCERTIFIED`, `canonical_path_user_reachable = true`, `previous_g5_evidence = INVALIDATED`.
- [ ] `WU_G5_RESTORE_CLOSURE_RECEIPT.md` committed; references invalidation receipt by SHA `a31b2fa4`.
- [ ] Pre-existing reds unchanged from W1d baseline (no widen).
- [ ] NOT declare `CERTIFIED` until public DSL reaches canonical path with legacy physically removed (WU-G5B — separate cycle: delete dispatcher, remove `core.waitUntil` from `LEGACY_PLUGIN_IDS` / decoder / metadata, demand residual `1/1/1 {core.load}`, re-run example, flip orchestrator to `CERTIFIED`).