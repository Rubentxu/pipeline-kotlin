# FIXTURE DEBT — READY TO APPLY

**Cycle:** `lfc2-fixture-debt`
**Branch:** `prep/lfc2-fixture-debt` (rebased on `origin/main` @ `42ab7e0e`)
**Baseline authority:** `pipeline-b12:/docs/v2/07-uat/evidence/b12-g0/deterministic-baseline-44.txt`
**Scope:** the 18 stale-fixture deterministic failures in the B12 G0 baseline whose
root cause is bare / no-registry legacy coordinator fixtures reaching
`CanonicalLoadNodeDispatcher:47` (`PipelineFailure(INFRASTRUCTURE, "controlDirRoot is
required for load")`). See `LB02_A5_3B_COORDINATOR_CONSTRUCTION_CLASSIFICATION.md`
for the 22-row classification this work continues, and `LB02_A5_45_RECOVERY_AND_UNREACHABLE.md`
for the recovery-bridging corollary.

---

## 1. Authority and source-of-truth

| Field | Value |
| --- | --- |
| Slice | `lfc2-fixture-debt` |
| Classification doc | `docs/v2/07-uat/LB02_A5_3B_COORDINATOR_CONSTRUCTION_CLASSIFICATION.md` |
| Companion seam | `app/support/CoordinatorFixture.kt` (`default`, `negativeNoRegistry`, `noOpCredentialScopePort`) |
| Rule anchor | AGENTS.md *Coordinator test composition (LB-02 / A5)* |
| Public API change | none (test-only refixture) |
| Step / production change | zero |
| Failure-mode rule | `CanonicalLoadNodeDispatcher.kt:47` (transitive symptom; the durable-load path needs `controlDirRoot != null`, the legacy no-registry fixtures did not bind one) |

The 18 stale-fixture failures are NOT a behaviour defect introduced by B11/W2/W3 or
the B12 inline-case-body deletions. They are the cumulative residue of three things:

1. Two independent test-bed constructions: bare `CanonicalDurableRunCoordinator(...)`
   (no `stepRegistry`, no `stepMetadataResolver`) created before the registry migration;
2. The success of `CoordinatorFixture.default(...)` for *some* classes
   (`DurableProtocolInvocationCharacterizationTest`) while sibling classes still use bare
   construction with the same coverage surface;
3. The `core.load` step's hard fail-closed `controlDirRoot != null` invariant that
   *any* unparameterised legacy fixture hits the moment execution crosses the
   `LegacyExecutionBoundary` path even for echo/sh. The path that hits
   `controlDirRoot is required for load` is the
   `LegacyExecutionAdapter → invocationExecutor(null) → CanonicalNodeDispatcher → Load
   branch` traversal where the registry **isn't** consulted. Production core.echo /
   core.sh are themselves fully migrated to the registry family (CERTIFIED +
   LEGACY_REMOVED), so removing the legacy execution seam will not affect the
   production semantics — but it will not retroactively arm the *legacy test
   fixtures* with the registry either.

**These failures do NOT disappear when `core.load` leaves the legacy path.** Even
after W4/G5 of B12 strips the inline `Load` row from `CanonicalNodeDispatcher.kt`,
every legacy-fixture test still: (a) loses the production harness for `core.echo`/
`core.sh` (it goes through the now-empty legacy branch), (b) sees the registry's
metadata authority resolve `core.echo`/`core.sh` correctly (they are now registry
rows), (c) but `coordinator.run(...)` will hit the legacy `LegacyExecutionAdapter`
fallback path inside `FamilyRouter.decide(dispatcher, invocationExecutor=null,
stepRegistry=null)` and dispatch via `CanonicalNodeDispatcher.dispatch` — which is
exactly the path that today fails on Load for the EBTest pair and that has been
the historical seam for the CDRT echo/sh dispatch failures. The debt is in the
*test* surface, not the *production* surface. Removing the legacy Load branch
removes the canonical symptom on the spot, NOT the underlying fixture
incongruence that surfaces on every other Step family when the registry is bound.

---

## 2. Test → intent → current fixture → target fixture (the 18)

file:line evidence is **file / line of the failing test method declaration**
(Git HEAD = `42ab7e0e`). The `ctor @line` column cites the existing classification
doc's row numbering for cross-reference.

| # | Test class @ file | Test method @ line | ctor @line (LB02-A5 doc) | Behavioural intent | Current fixture (bare / partial) | Target fixture (productive) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `CanonicalDurableRunCoordinatorTest` | `journals a supported block child with its body path` @ `259` | `@285` | `core.dir` body containing `core.echo` must be journaled with `body path`. Asserts `OperationStatus.SUCCEEDED` for the nested echo. | bare 8-arg `CanonicalDurableRunCoordinator(CanonicalNodeDispatcher(), journal, cursor, clock, replay policy, eventStore, credentialScopePort=noOp)` — no `controlDirRoot`, no `stepRegistry` | `CoordinatorFixture.default(clock, journal, eventSink)` (registry-aware, sealed `ShOptions.EMPTY`, `StrictFingerprintDivergenceDetector`); the production-like fixture routes `core.echo` through `RegistryExecutionBoundary`. The same call site must add `controlDirRoot = tempDir.resolve("control")` since `core.dir` requires it |
| 2 | `CanonicalDurableRunCoordinatorTest` | `journals and checkpoints a linear canonical echo run` @ `468` | `@414` | Single-echo run is journaled once; the second run SKIPs and no extra journal row is added; total event count == 11 across both runs | bare 7-arg construction; runs `echoPipeline("durable")` twice | `CoordinatorFixture.default(...)`; the existing `@14` event-count assertion stays bit-equivalent. **No assertion weakening.** |
| 3 | `CanonicalDurableRunCoordinatorTest` | `dispatch decodes each StepNode before delegating to the typed dispatcher` @ `520` | `@475` | Two-step pipeline succeeds iff the decoder was called for each Step. Asserts `journal.size == 2` and `RunOutcome.Success` | bare 7-arg construction; 2-step echo pipeline | `CoordinatorFixture.default(...)`; the same `assertEquals(2, journal.listForRun(runId.value).size, ...)` stays bit-equivalent |
| 4 | `CanonicalDurableRunCoordinatorTest` | `dispatch returns SCHEMA for a fresh structurally-valid but typed-invalid payload without executor` @ `618` | `@561` | A `core.echo` payload whose dsl-v1 envelope parses but the `text` field is missing must be rejected as `SCHEMA` without invoking the typed executor. This is `CDE.2-c` "typed decode runs only on Execute" | bare 7-arg construction; `VersionedStepPayload("dsl-v1", "{...no `text`...}")` | `CoordinatorFixture.default(...)`; the typed-invalid payload reaches `decode` through the registry's `CoreEchoCodec` which throws `IllegalArgumentException`, the adapter catches it and folds to `ExecutionPreparation.Rejected(...)`. `FailureKind.SCHEMA` + `OperationStatus.FAILED` stay identical |
| 5 | `CanonicalDurableRunCoordinatorTest` | `run emits StepStarted before dispatch` @ `663` | `@589` | The lifecycle spine emits `StepStarted` BEFORE the executor is dispatched. Order-frozen behaviour. | bare 7-arg construction; single echo | `CoordinatorFixture.default(...)`; the `assertTrue(started > 0 && stepIndex == 0 && stepType == "echo")` stays bit-equivalent |
| 6 | `CanonicalDurableRunCoordinatorTest` | `run emits StepFinished after dispatch` @ `692` | `@618` | The lifecycle spine emits `StepFinished` AFTER the executor is dispatched. Order-frozen behaviour. | bare 7-arg construction; single echo | `CoordinatorFixture.default(...)`; the `stepFinishedEvents.first()` assertions stay bit-equivalent |
| 7 | `CanonicalDurableRunCoordinatorTest` | `No step events for ReplayDecision SKIP` @ `767` | `@692` | SKIP-on-memoized-replay must NOT emit per-step events; ≤1 pair across two runs. Frozen behaviour. | bare 7-arg construction; two consecutive `coordinator.run(echoPipeline("test"), runId)` | `CoordinatorFixture.default(...)`; the `stepStartedEvents.size <= 1` and `stepFinishedEvents.size <= 1` assertions stay bit-equivalent |
| 8 | `CanonicalDurableRunCoordinatorTest` | `ReplayDecision ABORT emits one failed lifecycle without dispatching` @ `806` | `@728` | An injected `EffectReplayPolicy.decide(...) -> ABORT` for any non-fresh entry produces exactly one `StepStarted`, one `StepFailed(INFRASTRUCTURE)`, one `StepFinished`. Order-frozen behaviour. | bare 8-arg construction with custom `EffectReplayPolicy { ... ReplayDecision.ABORT }`; single echo | `CoordinatorFixture.default(clock, journal, eventSink)` + custom `effectReplayPolicy` override (the `default` overload does not bind `effectReplayPolicy` — so an additive overload is needed). The exact event-count assertions and `FailureKind.INFRASTRUCTURE` stay bit-equivalent |
| 9 | `CanonicalDurableRunCoordinatorTest` | `Exactly-once discipline - 3 steps emits 3 StepStarted and 3 StepFinished` @ `839` | `@787` | Three-step pipeline yields exactly 3 `StepStarted` and 3 `StepFinished` ordered by stepIndex. Frozen ordering law. | bare 7-arg construction; 3-step echo pipeline | `CoordinatorFixture.default(...)`; the `assertEquals(listOf(0,1,2), startedStepIndices)` assertions stay bit-equivalent |
| 10 | `CanonicalDurableRunCoordinatorTest` | `withCredentials acquires scope overlays env and always closes` @ `977` | `@912` | A `withCredentials` block successfully acquires a scope, overlays env, dispatches body, closes the scope exactly once. Asserts `RunOutcome.Success` and `closeCount == 1`. | bare 6-arg construction with custom `CredentialScopePort { ... }` capturing close count; no `stepRegistry` | `CoordinatorFixture.default(clock, journal, eventSink)` + override `credentialScopePort` and `controlDirRoot = tempDir.resolve("control")`. Same `closeCount == 1` and `eventStore.eventsFor(...).filterIsInstance<StepStarted>().any { it.stepName == "creds/echo" }` assertions stay bit-equivalent |
| 11 | `ExecutionBoundaryFactoryTest` | `build returns LegacyOnly boundary when registry is null` @ `84` | n/a (factory test, not coordinator ctor) | When `stepRegistry == null`, `ExecutionBoundaryFactory.build` returns the legacy adapter alone; a `PreparedLegacyExecution(Load)` succeeds and a `PreparedRegistryExecution` throws `EngineInvariantViolation` | `ExecutionBoundaryFactory.build(dispatcher, invocationExecutor=null, stepRegistry=null)` directly | **LEGACY CHARACTERIZATION — keep bare construction**. The test's intent is to freeze the legacy-only branch of the factory. A `CoordinatorFixture` swap is **inappropriate**: the test is *about* the factory's structural branching, not about the production coordinator wiring. The fix is environment, not fixture: pass a `controlDirRoot`-bearing `CanonicalRuntimeContext` runtime so the `Load` dispatch succeeds. The `assertEquals(StepOutcome.Success, ...)` becomes `RuntimeContext(controlDirRoot=tempDir.resolve("control"))` and a sentinel `pipeline.kts` file is planted for the load to read |
| 12 | `ExecutionBoundaryFactoryTest` | `build returns SeamedExecutionRouter when registry is present and stepKey is owned` @ `112` | n/a | With `stepRegistry = registryWithEcho()` and `stepKey = CoreEchoStep.KEY`, the factory produces a `SeamedExecutionRouter` that routes BOTH legacy and registry families. Asserts neither family fails. | `ExecutionBoundaryFactory.build(..., stepRegistry = registryWithEcho(), stepKey = CoreEchoStep.KEY)` | **LEGACY CHARACTERIZATION — keep bare construction**. Same rationale as #11: this test freezes the *factory* branching, not coordinator behaviour. Same `controlDirRoot`-bearing `runtime()` helper solves the load symptom; the `assertNotEquals(StepOutcome.Failure, ...)` for both fixtures stays bit-equivalent |
| 13 | `ExecutionBoundaryFactoryTest` | `build with recorder wraps the produced boundary and increments recorder counter on execute` @ `142` | n/a | With `recorder != null`, the produced boundary is a `RecordingBoundary` decorator that invokes the user-supplied recorder and increments its counter on each call. Frozen behaviour. | `ExecutionBoundaryFactory.build(..., stepRegistry = null, recorder = recorder)` + `recorder.calls++` observation | **LEGACY CHARACTERIZATION — keep bare construction**. The test *is* the recorder contract; the only fix is environment (`controlDirRoot` in `runtime()` so load dispatch succeeds) so the recorder actually observes one call. `assertEquals(1, recorder.calls)` stays bit-equivalent |
| 14 | `ExecutionBoundaryFactoryTest` | `build with null recorder returns the produced boundary without wrapping` @ `166` | n/a | The factory with `recorder=null` returns the produced boundary directly (no decorator). Two equivalent constructions must produce functionally equivalent boundaries; a `PreparedRegistryExecution` must throw `EngineInvariantViolation` (proving it's the legacy adapter alone, not a seamed router). | Two `ExecutionBoundaryFactory.build(..., recorder=null)` calls + reference check against `manualInner` | **LEGACY CHARACTERIZATION — keep bare construction**. Same rationale as #13. The `controlDirRoot` env fix is the only way the legacy-load line yields Success without rewriting the test |
| 15 | `DualExecutionSeamCharacterizationTest` | `fresh valid legacy executes exactly once through both seams` @ `145` | n/a | A fresh `core.echo` run invokes the effective executor exactly once through both seams (legacy executor + common boundary). Frozen migration equivalence law. | `DualRecorderCoordinator.build(journal, cursor, store)` — bare 8-arg inner `CanonicalDurableRunCoordinator(CanonicalNodeDispatcher(), journal, cursor, clock, replay, store, credentialScopePort=noOp(dual), controlDirRoot=null, commonExecutionBoundary=recordingBoundary)` | `CoordinatorFixture.default(clock, journal, eventSink, recorder=recordingBoundary)` (or, if `DualRecorderCoordinator`'s dual recording is load-bearing — see #15 call-out — fall back to LEGACY CHARACTERIZATION; see §3). **Assertion preservation**: `assertEquivalent(1)` stays bit-equivalent |
| 16 | `DualExecutionSeamCharacterizationTest` | `replay reuse never reaches either seam` @ `157` | n/a | A journaled SUCCEEDED echo is reused; neither seam is invoked. Frozen migration equivalence law for replay reuse. | bare 8-arg inner coordinator construction + journal seed | **MIGRATE to `CoordinatorFixture.default(..., recorder=recordingBoundary)`**, journal seed independent of fixture. **Assertion preservation**: `assertEquivalent(0)` stays bit-equivalent |
| 17 | `DualExecutionSeamCharacterizationTest` | `running shell recovery never reaches either seam` @ `292` | n/a | A RUNNING-journaled shell with a populated `result.txt` is reconciled on re-entry; no shell is relaunched and neither seam is invoked. | bare 8-arg inner construction + `controlDirRoot = controlRoot` + journal seed + sentinel `result.txt` | **MIGRATE** to `CoordinatorFixture.default(clock, journal, eventSink, recorder=recordingBoundary)` extended with an overload accepting `controlDirRoot`, then `assertEquivalent(0)` and `assertEquals(OperationStatus.SUCCEEDED, journal.get(operationId)?.status)` stay bit-equivalent |
| 18 | `DurableProtocolInvocationCharacterizationTest` | `a1-4 lifecycle spine owns StepStarted and StepFinished around the semantic event` @ `484` | n/a | The lifecycle spine owns `StepStarted`/`StepFinished` ordered around the semantic `EchoOutputCaptured` event. Frozen ordering law. | `CoordinatorFixture.negativeNoRegistry(clock, journal, eventSink)` (already a fixture — not bare) — single echo | **FAIL_CLOSED_NEGATIVE — keep `negativeNoRegistry`** explicitly. The intent is to assert the spine ordering on the legacy boundary to prove the *lifecycle spine* migration is independent of the *registry* migration. No fixture change. (See *§3 Why these are not assumed to disappear* below for the symptom root cause this test *does not address*.) |

### Totals

| Category | Count | Tests |
| --- | --- | --- |
| **ORDINARY_BEHAVIORAL** (must move to productive composition) | 12 | #1 #2 #3 #4 #5 #6 #7 #8 #9 #10 #16 #17 |
| **LEGACY_CHARACTERIZATION** (bare fixture legitimately allowed) | 5 | #11 #12 #13 #14 #15 |
| **FAIL_CLOSED_NEGATIVE** | 1 | #18 |

Call-out on #15 and #17: #15 IS load-bearing on the bare construction because
`DualRecorderCoordinator` holds the dual counter invariant (legacy executor
counter == common boundary counter). Migrating it to `CoordinatorFixture.default`
loses the legacy executor's *direct* counter hook. The seam can be preserved by:

```text
(a) keeping DualRecorderCoordinator.build() bare (LEGACY_CHARACTERIZATION), or
(b) introducing a secondary `CoordinatorFixture.defaultDual(clock, journal,
    eventSink, recorder, legacyRecorder)` overload that wires both legacy and
    common boundaries; this is the *preferred* solution because it removes the
    six divergent bare constructions and preserves the dual-recorder signal
    identically.
```

#16 (`replay reuse never reaches either seam`) AND #17 (`running shell recovery
never reaches either seam`) face the same dual-recorder concern:
they migrate cleanly via the `defaultDual` overload. Two migrations (one per
test), not one; preserve the equivalence law bit-equivalent.

Recount (final, no call-out):

| Category | Count | Tests |
| --- | --- | --- |
| ORDINARY_BEHAVIORAL | 12 | #1 #2 #3 #4 #5 #6 #7 #8 #9 #10 #16 #17 |
| LEGACY_CHARACTERIZATION | 5 | #11 #12 #13 #14 #15 |
| FAIL_CLOSED_NEGATIVE | 1 | #18 |
| TOTAL | 18 | — |

---

## 3. Why these failures are NOT assumed to disappear when `core.load` leaves the legacy path

The deterministic-baseline-44.txt attributes all 18 to
`PipelineFailure(INFRASTRUCTURE, "controlDirRoot is required for load")`
(`CanonicalLoadNodeDispatcher.kt:47`). The wording is misleading: in the bare
coordinator construction, the legacy execution path is reached via
`LegacyExecutionAdapter.adapt(...) → CanonicalInvocationExecutor(null) →
CanonicalNodeDispatcher.dispatch(...)` and that path *itself* requires a
`CanonicalRuntimeContext.controlDirRoot != null` whenever the typed command
resolves to `Load`. The path only manifests for `Load` typed commands. The
echo / sh tests in `CanonicalDurableRunCoordinatorTest` look as if they should
NOT hit the load branch — they don't, but the *same* `controlDirRoot == null`
default leaves every legacy-fixture run with an unusable workspace for any
nested operation (including `core.dir`, `core.withCredentials`, shell
recovery, and the legacy `core.load` branch).

The three reasons the failures do not auto-resolve on B12 G5:

1. **Symptom ≠ root cause.** `core.load` going `LEGACY_REMOVED` deletes the legacy
   `Load` decoder row and the legacy dispatcher `is Load -> loadDispatcher` branch.
   The bare fixtures don't have `core.load` in their pipelines; they have
   `core.echo` / `core.sh`. B12 G5 does not bind a `stepRegistry` to the bare
   fixtures, so they still go through `LegacyExecutionAdapter.adapt(...)` over a
   missing or null registry. For echo/sh, that path calls `LegacyExecutionBoundary.prepare`
   on a step the registry does not own, dispatch through the legacy command decoder,
   and the legacy decoder does not touch `Load` *for echo/sh*. But the bare
   construction *also* fails the `WorkspaceResolver` precondition because
   `controlDirRoot == null`, so any `stagesLoop`/workspace creation yields
   `INFRASTRUCTURE` exactly the way the baseline reports.
2. **Test composition, not production.** The tests themselves are the
   incongruence: they wire a coordinator that AGENTS A5 says must be the
   "production-like registry-aware composition" or "explicit fail-closed negative
   / intentional legacy characterization". Removing the legacy Load branch from
   `CanonicalNodeDispatcher.kt` does not migrate the test call-sites.
3. **Behaviour assertion preservation matters.** Some of the 18 tests assert
   surface events (e.g. #2: `assertEquals(11, eventStore.eventsFor(runId.value).count())`)
   that depend on which executor path runs. Migrating to `CoordinatorFixture.default`
   routes echo through the registry's `RegistryExecutionBoundary` instead of the
   legacy adapter, and the event-coverage identity of the registry is a *stronger*
   proven contract (it is `LB-02 / A4` production-flip certified). So the assertion
   does not weaken; it strengthens the binding without changing the numeric count
   for echo/sh events.

**Behaviour-preservation rules (binding for APPLY):**

- All `assertEquals(...)` and `assertTrue(...)` counts (event counts, journal
  sizes, fingerprint equality) stay EXACT.
- For #4 (typed-invalid SCHEMA), the rejection path goes through the registry
  codec (`IllegalArgumentException` → `ExecutionPreparation.Rejected`) which is
  identical to the legacy decoder's behaviour in observable terms.
- For #18 (lifecycle ordering on `negativeNoRegistry`), the test's `assertTrue(started < semantic && semantic < finished)` is invariant to whether the echo runs on the legacy adapter or the registry; only the fixture NAME matters — it stays `negativeNoRegistry`.

---

## 4. Proposed `CanonicalCompositionFixture` (test-helper SEAM)

**Location:** `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/CanonicalCompositionFixture.kt`
(no collision with `CoordinatorFixture`; the new file is the canonical seal of
the `defaultDual` overload and the `withControlDirRoot` extensions. Net result:
**18 divergent fixtures → 1 authority**.)

**Scope of the seam:**
- Extends `CoordinatorFixture` with the missing constructor variants needed
  by the 18 tests so the ONLY way to build a `CanonicalDurableRunCoordinator` for
  these tests is via the seam (the seam is the single source of truth).

**Inputs (the seam's parameters):**

```text
fun defaultDual(
    clock: Clock,
    journal: OperationJournal,
    eventSink: EventSink,
    recorder: CommonExecutionBoundary,    // also captures into the legacy seam
    legacyRecorder: CanonicalInvocationExecutor,
    controlDirRoot: Path? = null,
    shOptions: ShOptions = ShOptions.EMPTY,
): CanonicalDurableRunCoordinator   // wires BOTH legacy invocationExecutor AND
                                    // commonExecutionBoundary through the seam;
                                    // the dual-equivalence law of
                                    // DualExecutionSeamCharacterizationTest is
                                    // preserved bit-equivalent.

fun defaultWithCredentials(
    clock: Clock,
    journal: OperationJournal,
    eventSink: EventSink,
    credentialScopePort: CredentialScopePort,
    controlDirRoot: Path?,
): CanonicalDurableRunCoordinator   // overrides CredentialScopePort and
                                    // controlDirRoot only; uses
                                    // CoreStepRegistryFactory.registry() and
                                    // StrictFingerprintDivergenceDetector.

fun defaultWithReplayPolicy(
    clock: Clock,
    journal: OperationJournal,
    eventSink: EventSink,
    effectReplayPolicy: EffectReplayPolicy,
): CanonicalDurableRunCoordinator   // overrides effectReplayPolicy only; the
                                    // FIXTURE_DEBT row #8 needs
                                    // decide(...)=ReplayDecision.ABORT.

fun withControlDirRoot(
    base: CanonicalDurableRunCoordinator,
    controlDirRoot: Path,
): CanonicalDurableRunCoordinator  // builds a wrapped coordinator that prepends
                                    // WorkspaceResolver(controlDirRoot) creation
                                    // in stagesLoop; preserves every other seam.
```

**What the seam wires (and what it doesn't):**

Wired:
- `dispatcher = CanonicalNodeDispatcher()` (every production path uses this)
- `cursorStore = InMemoryReplayCursorStore(clock)`
- `effectReplayPolicy = DefaultEffectReplayPolicy()` (overridable)
- `credentialScopePort = noOpCredentialScopePort()` (overridable)
- `controlDirRoot = null` (overridable; required for shell-recovery and withCredentials)
- `shOptions = ShOptions.EMPTY` (overridable)
- `divergenceDetector = StrictFingerprintDivergenceDetector()`
- `stepRegistry = CoreStepRegistryFactory.registry()` (overridable per
  registry-targeted tests)
- `commonExecutionBoundary`, `invocationExecutor` per the overload semantics

NOT wired (because they are LEGACY CHARACTERIZATION scenarios):
- `stepRegistry = null` (that is `negativeNoRegistry`, kept on `CoordinatorFixture`).

**Helper for EBTest environment fix:**

```text
fun runtime(controlDirRoot: Path? = null): CanonicalRuntimeContext =
    CanonicalRuntimeContext(
        opId = OpId("factory", 0, 0),
        runId = "factory",
        stageName = "build",
        stageIndex = 0,
        stepIndex = 0,
        shOptions = ShOptions.EMPTY,
        controlDirRoot = controlDirRoot,
        eventSink = InMemoryEventStore(),
    )
```

The EBTest `#11..#14` tests pass `runtime()` instead of the bare `runtime()` they
already use; the only difference is the added `controlDirRoot` parameter (so
`runtime(tempDir.resolve("control"))` is called when a Load is dispatched). The
`assertThrows(EngineInvariantViolation::class.java)` for `PreparedRegistryExecution`
on `#11` and `#14` stays bit-equivalent (registry payloads still cannot go through
the legacy adapter when registry is null).

**Net collapse:** 18 tests' divergent bare → 4 overloads on `CanonicalCompositionFixture`
+ `negativeNoRegistry` (kept) + the existing factory calls (kept). The test
author's surface area shrinks from 18 distinct bare constructions to **1
authoritative seam** with **6 overloads** (4 new + 2 existing).

---

## 5. APPLY plan and B12 integration window

### 5.1 Sequencing

```text
Step 1 (test-only):  Add `CanonicalCompositionFixture.kt` with 4 new overloads.
                     Compile `:pipeline-application:compileTestKotlin` (L0).
                     No production source touched.

Step 2 (test-only):  Migrate #1, #2, #3, #5, #6, #7, #8, #9 (8 test migrations in
                     `CanonicalDurableRunCoordinatorTest.kt`) to
                     `CoordinatorFixture.default` /
                     `defaultWithReplayPolicy`. Validate L1 per migrated test
                     method; L2 per migrated class.

Step 3 (test-only):  Migrate #4 to `CoordinatorFixture.default`; the typed-invalid
                     payload routes through the registry codec; the SCHEMA
                     assertion stays bit-equivalent. Validate L1 #4.

Step 4 (test-only):  Migrate #10 (`withCredentials acquires scope overlays env
                     and always closes`) to `defaultWithCredentials`; closeCount
                     assertion stays bit-equivalent. Validate L1 #10.

Step 5 (test-only):  Migrate #15, #16, #17 in
                     `DualExecutionSeamCharacterizationTest.kt` to
                     `defaultDual`. Validate L1 #15, #16; L2 full class.

Step 6 (test-only):  Update `ExecutionBoundaryFactoryTest` runtime() helper to
                     accept controlDirRoot and rewire #11..#14 fixtures to
                     use `runtime(tempDir.resolve("control"))`. Validate L1 #11,
                     #12, #13, #14; L2 full class. The factory calls themselves
                     stay bare (LEGACY CHARACTERIZATION).

Step 7 (test-only):  Validate #18 (`a1-4 lifecycle spine owns ...`) already
                     uses `CoordinatorFixture.negativeNoRegistry`; no fixture
                     change. Document the FAIL_CLOSED_NEGATIVE classification
                     in a class-level KDoc comment.

Step 8 (receipt):     Round-2 targeted reruns + G0 rebaseline comparison.
                     Round 2 = :pipeline-application:test (L4-only, no L5
                     round gate yet).
```

### 5.2 No APPLY while B12 edits shared files

B12 W2.4, W3.4 and W4.9 edit the following production files:

- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt` (W2.4 ~L1600-1660, W3.4 ~L1500-1530)
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalNodeDispatcher.kt` (W4.9 G5 LEGACY_REMOVED)
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalCoreStepMetadata.kt` (W4.9 metadata row deletions)

Of the **18 fixture files** (4 test classes):

- **Files B12 W2/W3/W4 also touches: zero.** B12 does not modify the four test
  classes directly. It does modify three production classes listed above; the test
  classes consume them.

**Integration window:** APPLY for `lfc2-fixture-debt` does not begin until B12 W4.9
has landed on `origin/main` (or has been rebased out of `CanonicalNodeDispatcher.kt`
and the canonical metadata row for `Load`). Concretely:

```text
HARD SEQUENCE:
  1. B12 W1..W4 lands (canonical coordinator recompiles with LEGACY_REMOVED for
     core.load and core.retry / core.timeout).
  2. Resolve-on-HEAD: ./gradlew -p v2 :pipeline-application:test
     --tests 'CanonicalDurableRunCoordinatorTest' --tests
     'ExecutionBoundaryFactoryTest' --tests 'DualExecutionSeamCharacterizationTest'
     --tests 'DurableProtocolInvocationCharacterizationTest' (L1-only).
     Capture XML digests under docs/v2/07-uat/evidence/lfc2-fixture-debt/.

  3. THEN APPLY lfc2-fixture-debt. Never intermix commits on the shared
     production files. If commits are required in
     `CanonicalDurableRunCoordinator.kt` (e.g. to expose additional ctor
     overloads the seam needs), follow the chained-PR strategy: stack as
     `cycle/lfc2-fixture-debt` on top of B12 W4.

  4. APPLY lane: only add CanonicalCompositionFixture.kt + edit the four test
     files. Run the migrated tests at L1 (per method) and L2 (full class).
     Capture EXACT XML fingerprints before/after.

  5. Round-2 acceptance: rerun the 18 with --rerun-tasks. Compare failure-name
     set vs the G0 18-element list (deterministic-baseline-44.txt, stale-fixture
     section). Acceptable deltas: the 18 names MUST shrink to (a) zero new
     failures and (b) zero new tests if the migration keeps the test names
     bit-equivalent. If a test name changes, classify the rename in the receipt.

  6. Do NOT widen the 26-red baseline (set must stay byte-identical).
```

### 5.3 MUST NOT (binding for APPLY)

```text
- MUST NOT weaken, skip, ignore, delete, or comment-out any of the 18 tests.
- MUST NOT change production source unless an additive ctor overload on
  CanonicalDurableRunCoordinator or CanonicalNodeDispatcher is required to expose
  effectReplayPolicy / credentialScopePort / controlDirRoot as separately-bindable
  seam parameters; even then, the overload is additive (new ctor signature, no
  removal of any existing call-site binding).
- MUST NOT modify B12 lane files (CanonicalDurableRunCoordinator.kt,
  CanonicalNodeDispatcher.kt, CanonicalCoreStepMetadata.kt) outside the additive
  ctors the seam needs.
- MUST NOT widen the 26-red baseline; if a new red appears, classify + quarantine
  per AGENTS Exceptions (B).
- MUST NOT add step-specific semantics to the seam; the seam is composition-only.
- MUST NOT collapse the LEGACY_CHARACTERIZATION tests (#11..#15) into
  CoordinatorFixture; their intent is to freeze the factory's branching and the
  dual seam's equivalence law on the bare coordinator construction.
- MUST NOT modify DurableProtocolInvocationCharacterizationTest.kt beyond a
  class-level KDoc comment on `a1-4 lifecycle spine owns ...` (FAIL_CLOSED_NEGATIVE
  classification).
- MUST NOT introduce `when(stepKey)` / `when(stepName)` discriminator in the
  fixture (or anywhere); the seam is composition-only.
- MUST NOT change the existing `LB02_A5_3B_COORDINATOR_CONSTRUCTION_CLASSIFICATION.md`
  row numbers; the APPLY commit only appends a row index for the migrated
  constructors.
```

---

## 6. Evidence and ledger

- Baseline authority: `pipeline-b12:/docs/v2/07-uat/evidence/b12-g0/deterministic-baseline-44.txt`
- Classification lineage: `docs/v2/07-uat/LB02_A5_3B_COORDINATOR_CONSTRUCTION_CLASSIFICATION.md`
- Companion seam: `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/CoordinatorFixture.kt`
- Production site of the symptom: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalLoadNodeDispatcher.kt:47`
- Rule anchor: AGENTS.md *Coordinator test composition (LB-02 / A5)*.

Receipt target: `docs/v2/07-uat/FIXTURE_DEBT_W1_RECEIPT.md` (created at APPLY W1 commit).

---

## 7. Open questions

1. **`DualRecorderCoordinator` (#15..#17) — keep, or fold into `defaultDual`?**
   Resolution: keep `DualRecorderCoordinator` as a private test-side recording
   helper inside `DualExecutionSeamCharacterizationTest.kt`; expose `defaultDual`
   on the seam with both `recorder` AND `legacyRecorder` parameters. The seam is
   the *one* place that wires the dual-equivalence law; the test-side recorder
   helper is the *one* place that holds the assertion. This matches how
   `CoordinatorFixture.negativeNoRegistry` coexists with the bare no-registry
   intent.
2. **`CoordinatorFixture.defaultWithReplayPolicy` (#8) — required?**
   Resolution: yes, additive. The seam currently has only one effectReplayPolicy
   (DefaultEffectReplayPolicy). #8 (`ReplayDecision ABORT emits ...`) injects a
   custom policy. Without the overload the test class would still need a bare
   construction.
3. **Is `withControlDirRoot` (Step 5.six) really needed?**
   Resolution: keep it as a non-mandatory convenience (YAML). #1, #10, #15,
   #17 want a `controlDirRoot` binding, and `CoordinatorFixture.default` binds
   `controlDirRoot = null`. The wrapper makes the binding explicit without a
   new ctor signature.
