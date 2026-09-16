# Cycle wu-g5-restore — explore.md

**Cycle:** wu-g5-restore
**Branch:** cycle/wu-g5-restore (worktree `../pipeline-wu-g5-restore`)
**Base:** ca28959f (origin/main, post lfc2-fixture-debt merge)
**Date:** 2026-09-16
**Author:** orchestrator (deepseek route hung at startup queued; explore done inline by orchestrator per READ-ONLY contract)
**Type:** A-lite (C1 default — slice bounded that extends W1d with a new ADT case `BodyExecutionPolicy.RepeatUntil`)

---

## 1. Problem-taxonomy

This is **NOT** a Step burn-down (LFC-2E1 / S2-legacy-catalog lane). `core.waitUntil`
is a **structural orchestration construct** that owns a body and must follow the
retry/timeout/parallel pattern (ADR-0073, B11 family). It belongs to the
**WU-G5-RESTORE / WU-G5B lane** (B14 waitUntil body re-entry), which is a
W1d-extension: a new case `BodyExecutionPolicy.RepeatUntil` in the existing
closed ADT, plus a new `StepSpec.WaitUntilBlock` algebraic variant.

Adjacent precedents already certified by the matrix:
- B11 / W3b companion fix (commit `cf541f40`): added `WithEnv` and `Timestamps`
  projections in `blockStepNode` — same compile-exhaustiveness pattern.
- B11 / BodyInvoker: every block-owning construct re-enters the coordinator
  through `BodyInvoker.invoke` (ADR-0073); no second dispatch collection.
- W1d: `StepBody.None` / `StepBody.Declared(invocation, execution, introduces,
  catchesInterruptions)` is the canonical body declaration on descriptors.
- B10 W1d: `BodyExecutionOwner { CANONICAL_ENGINE, LEGACY_LINEAR }` and the
  closed `BodyExecutionProjection` ADT — the seam where new orchestrations
  are added.

## 2. State today (machine-derived citations)

### DSL `waitUntil { body }` (terminal data class today)

`v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:1677-1695`

```kotlin
fun waitUntil(
    initialRecurrencePeriod: Long = 1L,
    quiet: Boolean = false,
    condition: () -> Boolean,
) {
    val result = condition()                                  // <-- body evaluated EAGERLY here
    steps.add(StepSpec.WaitUntil(
        initialRecurrencePeriod = initialRecurrencePeriod,
        quiet = quiet,
    ))                                                       // <-- body NOT captured in the StepSpec
    if (!result) {
        throw RuntimeException("waitUntil condition evaluated to false")
    }
}
```

`v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt:645-654`

```kotlin
data class WaitUntil(
    val initialRecurrencePeriod: Long = 1L,
    val quiet: Boolean = false,
) : StepSpec {
    override val name: String get() = "waitUntil"
    override val type: String get() = "waitUntil"
}
```

Two defects confirmed:

1. The `condition` lambda is **eager-evaluated** (`val result = condition()`) and
   the result discarded if true — a runtime-returning consumer in DSL
   construction, forbidden by AGENTS.md §10 ("a DSL builder MUST NOT perform
   runtime effects, inspect global mutable state, execute processes, ... or
   manufacture placeholder runtime-return values").
2. The body is **not captured** in the `StepSpec`. The DSL adds a terminal
   `WaitUntil` record with `initialRecurrencePeriod` + `quiet` only — there
   is no `body: List<StepSpec>` field. This is the structural defect: a
   body-owning construct with no body.

### Compiler projection (today, broken)

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt:140-229`

```text
fun stepNode(step: StepSpec, ...): List<StepNode> = when (step) {
    is StepSpec.RegistryStepSpec -> ...                 // generic registry path
    is StepSpec.WriteFile -> ...
    is StepSpec.CatchError -> rewriteWorkflowControl(...)
    is StepSpec.WarnError -> rewriteWorkflowControl(...)
    is StepSpec.TimeoutBlock -> blockStepNode(...)       // body preserved
    is StepSpec.RetryBlock -> blockStepNode(...)         // body preserved
    is StepSpec.Dir -> blockStepNode(...)                // body preserved
    is StepSpec.WithCredentialsBlock -> blockStepNode(...)
    is StepSpec.Timestamps -> blockStepNode(...)         // body preserved (B11 W3b)
    is StepSpec.WithEnv -> blockStepNode(...)            // body preserved (B11 W3b)
    is StepSpec.Unstable -> rewriteUnstable(...)
    else -> OpaqueStepNode("core.${step.name}", ...)    // <-- WaitUntil falls here
}
```

The `else` branch is the catch-all. `StepSpec.WaitUntil` has no `WaitUntilBlock`
case, no `blockStepNode` call, no `BlockStepNode` — it lowers to
`OpaqueStepNode(pluginStepId = "core.waitUntil", payload = encodePayload(step))`.
There is no `WaitUntilBlock` in the codebase (verified by grep on
`v2/pipeline-domain` and `v2/pipeline-scripting-api` — zero matches).

### Legacy dispatch (still the production reachability)

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt:65`

```text
is CanonicalCoreStepCommand.WaitUntil -> waitUntilDispatcher.dispatchStub(command, context.waitUntilContext())
```

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:153`

```text
"core.waitUntil",   // ← LEGACY_PLUGIN_IDS contains this; canonical route is bypassed
```

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWaitUntilNodeDispatcher.kt` — the legacy dispatcher that emits `WaitUntilPolled`/`WaitUntilCompleted` via the dispatchStub; this is the path the `WaitUntilEventHarnessTest` and `CompatibilityCorpusTest.fixture22WaitUntil` exercise today.

### Canonical path (G5a-implemented, unreachable from DSL)

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` — `BlockShellScope.RepeatUntil` + `dispatchRepeatUntilBody` + `WaitUntilReconciler` + `FileBasedWaitUntilControlJournal` were added in commit `92971881` (G0–G5). Unit-tested in isolation (`WaitUntilReconcilerTest`, `WaitUntilControlJournalContractSuite`). But the public DSL never reaches them: there is no `WaitUntilBlock` case in the compiler.

### Receipt of invalidation (already on trunk, commit `a31b2fa4`)

`docs/v2/07-uat/B14_WAITUNTIL_G5A_INVALIDATION_RECEIPT.md` (128 lines) is already committed on `cycle/wu-g5-restore` base. It records:

```text
canonical_path_implemented        = true   (reconciler, journal, dispatch branch all exist and are unit-tested)
canonical_path_user_reachable     = false  (DSL produces terminal StepSpec.WaitUntil, NOT a WaitUntilBlock with body)
previous_g5_evidence              = INVALIDATED
blocking_gap                      = DSL_TO_REPEAT_UNTIL_PROJECTION
state                             = IMPLEMENTED_UNCERTIFIED
legacy_removed                    = false
```

This is the receipt of record. WU-G5-RESTORE does not rewrite it.

### Inventory record (already on trunk)

`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`

```text
core.waitUntil | CORE candidate | legacy | Y (L1629) | N | Y (CanonicalWaitUntilNodeDispatcher) | Y | Y/N | N | — | — | — | IMPLEMENTED_UNCERTIFIED
```

Update target (after WU-G5-RESTORE closes): `Path = "structural"` (or `legacy-until-removed`), `StepDefinition = N/A (structural, not a Step)`, `Canonical = Y (BlockStepNode + BodyExecutionPolicy.RepeatUntil + dispatchRepeatUntilBody)`, `Legacy = Y (still present, not removed yet — WU-G5B scope)`.

## 3. Gap summary (one line per defect)

| Defect | Today | Required |
|---|---|---|
| DSL body capture | `condition` lambda evaluated eagerly; result discarded | Lambda captured as `List<StepSpec>` body in the produced StepSpec; no eager evaluation |
| DSL data class | `StepSpec.WaitUntil(initialRecurrencePeriod, quiet)` — terminal | `StepSpec.WaitUntilBlock(initialRecurrencePeriod, body)` — body-bearing algebraic variant |
| ADT | `BodyExecutionPolicy` cases: `Sequential / Scoped / Retrying / Parallel` (4 cases) | Add `RepeatUntil(val policy: RepeatUntilPolicy)` as 5th case |
| Compiler projection | `else -> OpaqueStepNode("core.waitUntil", ...)` | Explicit `is StepSpec.WaitUntilBlock -> blockStepNode(...)` case with body propagation and `BlockShellScope.RepeatUntil` payload |
| Coordinator path | (Implemented but unreachable) `BlockShellScope.RepeatUntil` + `dispatchRepeatUntilBody` | (Already present at G5a) — must be wired through `BlockStepNode.body` and `BodyInvoker.invoke` |
| Reconciler | (Implemented but unreachable) `WaitUntilReconciler` | (Already present at G5a) — pure, already unit-tested |
| Fitness | None for waitUntil canonical projection | Three new fitness: `Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest` (RED→GREEN by WU-G5R.3), `Lfc2WaitUntilCanonicalReentryFitnessTest` (GREEN by WU-G5R.4), `Lfc2WaitUntilNoLegacyRoutingFitnessTest` (RED during RESTORE, GREEN only after WU-G5B) |
| E2E example | None (`examples/22-wait-until.pipeline.kts` does not exist) | New example: `examples/22-wait-until.pipeline.kts` with `waitUntil(period) { sh("test -f marker") }`; installed CLI proves `dispatchRepeatUntilBody` is reached |

## 4. Surfaces to touch (bounded A-lite)

| Module | File | Type of change |
|---|---|---|
| pipeline-scripting-api | `PipelineDsl.kt` (L645 WaitUntil data class → WaitUntilBlock; L1677 waitUntil fun → capture body) | Edit |
| pipeline-domain | `domain/step/BodyExecutionPolicy.kt` (add `RepeatUntil` case + `RepeatUntilPolicy`) | Edit (new ADT case) |
| pipeline-domain | `StepBody.kt` / `StepDescriptorRegistry.kt` | No change (existing machinery covers the new case) |
| pipeline-domain | New `WaitUntilReconciler` (pure) | Already present at G5a (`WaitUntilReconcilerTest`); no new code, no new tests in RESTORE |
| pipeline-application | `application/DslCompiledPipelineCompiler.kt` (add `is StepSpec.WaitUntilBlock -> blockStepNode(...)`) | Edit |
| pipeline-application | `application/durable/CanonicalDurableRunCoordinator.kt` (`BlockShellScope.RepeatUntil` + `dispatchRepeatUntilBody`) | Already present at G5a; RESTORE only verifies reachability |
| pipeline-application | `application/durable/BodyExecutionProjection` (add `Until` variant if not present) | Edit if missing (B11 pattern) |
| pipeline-application | `examples/22-wait-until.pipeline.kts` (NEW) | New file |
| pipeline-application tests | `Lfc2WaitUntilDslCanonicalProjectionTest` (RED characterization, fails for expected reason today) | New |
| pipeline-architecture-tests | `Lfc2WaitUntilDslDoesNotLowerToOpaqueStepFitnessTest`, `Lfc2WaitUntilCanonicalReentryFitnessTest`, `Lfc2WaitUntilNoLegacyRoutingFitnessTest` | New |
| Legacy dispatcher | `application/durable/CanonicalWaitUntilNodeDispatcher.kt` | **NOT touched during RESTORE** (WU-G5B scope) |

## 5. Pre-existing reds (do not widen)

- `CanonicalDurableRunCoordinatorTest` = 26 / 11 (stable across W1a..W1d; W1d repair: 26/10)
- `CompatibilityCorpusTest` = 20 / 2 (corpus accounting defect; asserts 19, corpus holds 20)
- `Lfc0GlobalStateFitnessTest` = 1 red (KDoc false positive; byte-identical across slices)
- `PipelineDslSealedHierarchyTest`, `CoreLegacyStepMetadataResolverTest`, `RegistryStepMetadataResolverTest`, `ScriptTextEscaperTest`, `WithCredentialsCompileIntegrationTest`, `UatLocal005/007/008/009`, fixture14 credentials, A4 classifier
- All proven pre-existing by base-vs-head worktree method on prior slices (W1a..W1d receipts).

## 6. Risky invariants / laws to preserve

- **Step constitution** (AGENTS.md §STEP CONSTITUTION): the engine exhaustively matches a closed structural ADT; `StepKey → StepDefinition → StepHandler` resolves via an open registry. `core.waitUntil` is NOT in the registry as a Step. It is a structural construct. Do not add `core.waitUntil` to `CoreStepRegistryFactory`.
- **Closed execution structure, open Step registry**: `waitUntil` does not enter via `when (stepKey)`. It enters via the body machinery (ADR-0073, BodyInvoker re-entry).
- **DSL describes; interpreters execute** (AGENTS.md §10): `waitUntil { body }` MUST NOT execute the body during construction; the body must be captured as data.
- **ADT-first modelling** (AGENTS.md §8): `BodyExecutionPolicy.RepeatUntil` is a closed case, not a `Boolean` flag.
- **Decide purely, then interpret** (AGENTS.md §7): `WaitUntilReconciler` must be pure; the coordinator interprets.
- **B11 W3b exhaustiveness fitness** (`Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`): every body-bearing StepSpec variant must compile to a non-empty `BlockStepNode.body`. Adding `StepSpec.WaitUntilBlock` without wiring it will fail this test.
- **B10 W1a concrete body routing debt**: pinned ledger = 0; `HISTORICAL_CEILING = 18` immutable. WU-G5R must not introduce new `dispatch*Block` literals or new concrete-step switches.
- **W1d inventory**: `StepDescriptor.body: StepBody`; `BodyExecution.owner`/`policy` and `StepBody.Declared.invocation`/`execution` are required parameters. Adding `RepeatUntil` to `BodyExecutionPolicy` is consistent with this shape (it adds a new policy case; the owner remains `CANONICAL_ENGINE` for the structural blocks).
- **Pinned ledger** for `WaitUntilReconciler` reconciliation: deterministic, single-writer journal (analogous to RETRY-D row for retry / PAR-D row for parallel).

## 7. Recommendation

**A-lite.** This is bounded:

1. Edit one DSL data class (1 file).
2. Edit one DSL function body capture (1 file, same).
3. Add one case to a closed ADT (1 file).
4. Add one case to the compiler projection switch (1 file).
5. Add one body-bearing variant to the StepSpec algebra (already integrated by the case above).
6. Three new fitness tests (1 module).
7. One E2E example (1 file).
8. ZERO legacy source changes during RESTORE.
9. ZERO production semantic changes in the canonical reconciliation/journal code (it already exists from G5a).

The slice is structurally analogous to B11 / W3b (which added `WithEnv` + `Timestamps` projections with the same pattern). W3b was a 2-line compiler fix + 1 fitness test + 1 merge; this slice is larger (DSL change + ADT case + body-capture semantics) but bounded to the `core.waitUntil` construct only.

Routing: A-lite. Phases: explore (this doc) → propose → spec‖design (parallel) → tasks → apply → verify → debt-verify → release → archive.

## 8. Deliverables (already committed or to produce)

| Artefact | Status | Path |
|---|---|---|
| Invalidated G5a receipt | ✅ committed on trunk (a31b2fa4) | `docs/v2/07-uat/B14_WAITUNTIL_G5A_INVALIDATION_RECEIPT.md` |
| Inventory entry | ✅ committed on trunk (5efac6c0, ca28959f) | `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` |
| explore.md (this file) | 🆕 to commit | `openspec/changes/wu-g5-restore/explore.md` |
| proposal.md | TODO (propose phase) | `openspec/changes/wu-g5-restore/proposal.md` |
| spec.md | TODO (spec phase) | `openspec/changes/wu-g5-restore/spec.md` |
| design.md | TODO (design phase) | `openspec/changes/wu-g5-restore/design.md` |
| tasks.md | TODO (tasks phase) | `openspec/changes/wu-g5-restore/tasks.md` |
| Fitness tests | TODO (apply phase) | `v2/pipeline-architecture-tests/.../Lfc2WaitUntil*FitnessTest.kt` |
| E2E example | TODO (apply phase) | `v2/compatibility/22-wait-until.pipeline.kts` |
| Receipt | TODO (verify / release) | `docs/v2/07-uat/WU_G5_RESTORE_CLOSURE_RECEIPT.md` |

## 9. open_questions

1. Should `StepSpec.WaitUntilBlock` *replace* the existing terminal `StepSpec.WaitUntil` (rename) or coexist as a new variant? **Recommendation: rename** — there is no production path that depends on the terminal form (it always lowered to `OpaqueStepNode` and was unreachable from the canonical path; the existing `WaitUntil` data class is not referenced anywhere except the DSL function and the catch-all `else` branch). Renaming prevents accidental reintroduction.

2. Should `BodyExecutionPolicy.RepeatUntil` carry the predicate directly (`(StepContext) -> StepResult`) or a typed `RepeatUntilPolicy(initialRecurrencePeriod, deadline?, exitCondition)` referenced as data? **Recommendation: `RepeatUntilPolicy` as data** — predicates must be typed values in the ADT, not lambdas (matches the `RetryPolicy`/`ParallelPolicy` precedent). The predicate is supplied by the body via typed events (e.g. body emits `RepeatPredicateEvaluated(result: Boolean)`); the reconciler folds it. Confirm during design.

3. Should `BlockShellScope.RepeatUntil` and `dispatchRepeatUntilBody` be added in this slice or are they already present from G5a? **Already present from G5a** (verified by `git log 92971881 -1 --stat` and the receipt a31b2fa4). RESTORE does not add them; it only verifies reachability. If during apply we discover the G5a wiring is partial, escalate to design phase before adding new code.

4. What about the eager-evaluation defect in `waitUntil(condition: () -> Boolean)`? The DSL fun currently calls `condition()` immediately. After RESTORE, the DSL fun must capture the lambda as `List<StepSpec>` body, not invoke it. The KDoc and the test `UatLocal011WorkflowControlTest` already expect the canonical path; this is consistent with the invalidation's "blocking_gap = DSL_TO_REPEAT_UNTIL_PROJECTION".

5. Should `Lfc2WaitUntilNoLegacyRoutingFitnessTest` be RED during RESTORE (passing only after WU-G5B)? **Yes.** This is intentional: the fitness is the gate that proves the legacy dispatcher has been physically removed. RED is the correct state while the legacy path is still reachable.

## 10. confidence

**high.** The slice is bounded, the precedent (B11 W3b) is certified, and the receipt of invalidation already documents the exact gap. The only new piece of work is renaming `WaitUntil` → `WaitUntilBlock` in the DSL data class and adding a case to two `when` switches (compiler + `BodyExecutionProjection`).