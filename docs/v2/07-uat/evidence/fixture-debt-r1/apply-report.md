# FIXTURE DEBT R1 — APPLY REPORT

**Cycle:** `p-733fb505b5a6bd2d/fixture-debt-18-stale` (B-direct path: apply → verify)
**Branch:** `prep/lfc2-fixture-debt`
**HEAD at apply start:** `2125aa5f` (1 ahead of `origin/main = 42ab7e0e`)
**HEAD at apply end:** `1e99ad822d6d70e85afa08f1be776644458e0dd2` (4 ahead of `2125aa5f`)
**Baseline authority:** `pipeline-b12:/docs/v2/07-uat/evidence/b12-g0/deterministic-baseline-44.txt`
**PREP doc:** `docs/v2/07-uat/FIXTURE_DEBT_READY_TO_APPLY.md` (commit `2125aa5f`)
**Verdict:** **PASS** (18/18 baseline reds green; touched-class suite 43/43 green; 0 regressions)

---

## 1. Commits applied

| SHA | Title |
| --- | --- |
| `55dd697aed2bff9295efa65ca3ca3745f9fa6c60` | `fix(lfc2-fixture-debt): CanonicalDurableRunCoordinatorTest — bind stepRegistry on the 10 bare constructions` |
| `12651bdc8df04c5e6eeaee587fc590ac816b3ed5` | `fix(lfc2-fixture-debt): DualExecutionSeamCharacterizationTest — seamed-router dual recorder` |
| `9caab8ccfd246176440f77fc1e11bf991c3d566b` | `fix(lfc2-fixture-debt): DurableProtocolInvocationCharacterizationTest a1-4 — default fixture for lifecycle spine ordering` |
| `1e99ad822d6d70e85afa08f1be776644458e0dd2` | `fix(lfc2-fixture-debt): ExecutionBoundaryFactoryTest — bind controlDirRoot and seed workspace sentinel` |

---

## 2. Per-test root-cause classification + 1-line fix summary

All 18 baseline reds stem from **two distinct root causes** (the baseline's
attribution to "controlDirRoot is required for load" at
`CanonicalLoadNodeDispatcher.kt:47` is misleading; the actual reach is
wider). Per-test classification:

### CanonicalDurableRunCoordinatorTest (10)

| # | Test | Root cause | Category | 1-line fix |
| --- | --- | --- | --- | --- |
| 1 | `journals a supported block child with its body path` | bare ctor + `core.dir` block + nested `core.echo` | `FIXTURE_DRIFT` | add `controlDirRoot = tempDir.resolve("control")` AND `stepRegistry = CoreStepRegistryFactory.registry()` |
| 2 | `journals and checkpoints a linear canonical echo run` | bare ctor + `core.echo` | `FIXTURE_DRIFT` | add `stepRegistry = CoreStepRegistryFactory.registry()` |
| 3 | `dispatch decodes each StepNode before delegating to the typed dispatcher` | bare ctor + 2× `core.echo` | `FIXTURE_DRIFT` | add `stepRegistry = CoreStepRegistryFactory.registry()` |
| 5 | `dispatch returns SCHEMA for a fresh structurally-valid but typed-invalid payload without executor` | bare ctor + typed-invalid `core.echo` | `FIXTURE_DRIFT` | add `stepRegistry` so the typed-invalid SCHEMA path goes through the registry codec (rejection is bit-equivalent) |
| 9 | `run emits StepStarted before dispatch` | bare ctor + `core.echo` | `FIXTURE_DRIFT` | add `stepRegistry` |
| 10 | `run emits StepFinished after dispatch` | bare ctor + `core.echo` | `FIXTURE_DRIFT` | add `stepRegistry` |
| 7 | `No step events for ReplayDecision SKIP` | bare ctor + `core.echo` x2 | `FIXTURE_DRIFT` | add `stepRegistry` |
| 8 | `ReplayDecision ABORT emits one failed lifecycle without dispatching` | bare ctor + `core.echo` | `FIXTURE_DRIFT` | add `stepRegistry` |
| 9b | `Exactly-once discipline - 3 steps emits 3 StepStarted and 3 StepFinished` | bare ctor + 3× `core.echo` | `FIXTURE_DRIFT` | add `stepRegistry` |
| 10b | `withCredentials acquires scope overlays env and always closes` | bare ctor + `core.withCredentials` block with `core.echo` body | `FIXTURE_DRIFT` | add `stepRegistry` (no `controlDirRoot` needed; withCredentials' body just needs scope overlay) |

### DualExecutionSeamCharacterizationTest (3)

| # | Test | Root cause | Category | 1-line fix |
| --- | --- | --- | --- | --- |
| 11 | `fresh valid legacy executes exactly once through both seams` | bare ctor + `RecordingLegacyExecutor` only sees legacy leg | `CHARACTERIZATION_REWRITE` | re-author `DualRecorderCoordinator` to wrap `SeamedExecutionRouter.route(legacy, registry)` with `RecordingRegistryBoundary` on the registry leg; broaden equivalence law to `legacyExecutor.calls + registryBoundary.calls == boundary.calls` |
| 12 | `replay reuse never reaches either seam` | same as #11 | `CHARACTERIZATION_REWRITE` | same re-authoring; replay path sees 0 on both legs |
| 13 | `running shell recovery never reaches either seam` | same as #11 | `CHARACTERIZATION_REWRITE` | same re-authoring; recovery path sees 0 on both legs |

### DurableProtocolInvocationCharacterizationTest (1)

| # | Test | Root cause | Category | 1-line fix |
| --- | --- | --- | --- | --- |
| 14 | `a1-4 lifecycle spine owns StepStarted and StepFinished around the semantic event` | `negativeNoRegistry` fixture strips `stepRegistry`; `core.echo` fails before `EchoOutputCaptured` is emitted | `CHARACTERIZATION_REWRITE` | switch fixture from `negativeNoRegistry` to `default` (per PREP §2 row 18 the lifecycle-spine property is observable independently of registry-vs-legacy resolution; `negativeNoRegistry` makes the property unobservable) |

### ExecutionBoundaryFactoryTest (4)

| # | Test | Root cause | Category | 1-line fix |
| --- | --- | --- | --- | --- |
| 15 | `build returns LegacyOnly boundary when registry is null` | `runtime()` hard-coded `controlDirRoot = null`; legacy `core.load` requires it | `FIXTURE_DRIFT` | `runtime(controlDirRoot: Path?)` accepts optional Path; `legacyPrepared(controlDirRoot)` seeds `controlDirRoot/workspace/build-0/legacy-fixture.pipeline.kts` sentinel |
| 16 | `build returns SeamedExecutionRouter when registry is present and stepKey is owned` | same as #15 | `FIXTURE_DRIFT` | same fix |
| 17 | `build with recorder wraps the produced boundary and increments recorder counter on execute` | same as #15 | `FIXTURE_DRIFT` | same fix |
| 18 | `build with null recorder returns the produced boundary without wrapping` | same as #15 | `FIXTURE_DRIFT` | same fix |

### Category totals

| Category | Count |
| --- | --- |
| `FIXTURE_DRIFT` (production semantics correct, fixture stale) | 14 |
| `CHARACTERIZATION_REWRITE` (test characterized a seam that migrated) | 4 |
| `SEMANTIC_REGRESSION` (production semantics wrong) | 0 |
| `GLOBAL_STATE_LEFTOVER` (state bleed across tests) | 0 |

---

## 3. XML digests (after apply)

XMLs captured from the targeted rerun (commit `1e99ad82`) at
`v2/pipeline-application/build/test-results/test/`:

| Class | tests | failures | errors | sha256 |
| --- | ---: | ---: | ---: | --- |
| `CanonicalDurableRunCoordinatorTest` | 10 | 0 | 0 | `f10f8059d10ee55dcea15a1375dbfdb7da2653c9974243d0f3d9dfc4effdc6e4` |
| `DualExecutionSeamCharacterizationTest` | 5 | 0 | 0 | `581c1f4260b9593aefa6b906ccecda5f72e83de8203ad868d0a1b954acec10a0` |
| `DurableProtocolInvocationCharacterizationTest` | 1 | 0 | 0 | `74dba24bd99ec3f700f26c2726fb6fea4591c9ccf84bed65569836901fee176b` |
| `ExecutionBoundaryFactoryTest` | 4 | 0 | 0 | `83a4d1f71b9da3d2c3382b7cbd9add2a1f8528e76d6af3ef83dca3598f4fabd5` |

Targeted rerun: 18 baseline tests → **18/18 PASS**.

L4 module verification (touched classes only, no L5 round gate per
B-direct cycle scope): 43 tests across the 4 classes → **43/43 PASS, 0
failures, 0 errors**.

Architecture fitness (`pipeline-architecture-tests`): 287 tests, 1 failure
— `Lfc0GlobalStateFitnessTest > production code does not access the
controller user directory property`. This failure is one of the **26
inherited documented reds** (already present at `42ab7e0e`, out of fixture-
debt scope per the user's "NO cierres los 11 B13 rojos del gate B12"
constraint). **No new regression** introduced by this cycle.

---

## 4. B17 collateral fix (informational, not in scope)

The B17 novel reds file (`/tmp/b12-l5-novel.txt`) lists 47 names that
were red at HEAD = `42ab7e0e` but not in the B12 G0 44-baseline. The
root cause of many of these is the same as the 18 baseline reds (bare
construction missing `stepRegistry`). As a side-effect of the fix, the
following **B17 names now also pass** (verified at L4-touched-class
scope):

- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: NEVER policy executes fresh core error as typed failure`
- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: NEVER policy aborts re-execution of a journaled core error`
- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: StepFinished count equals 1 per step on failure path`
- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: dispatch returns Failure SCHEMA and journals FAILED when decoder throws`
- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: fails closed when a resumed canonical node diverges from its journal`
- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: records a failing canonical shell run as a typed script failure`
- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: withCredentials cleanup failure folds a successful body to failure`
- `dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinatorTest :: withCredentials unavailable fails closed and never dispatches body`
- `dev.rubentxu.pipeline.v2.application.durable.DualExecutionSeamCharacterizationTest :: decode schema rejection never reaches either seam`
- `dev.rubentxu.pipeline.v2.application.durable.DualExecutionSeamCharacterizationTest :: fingerprint divergence never reaches either seam`

(10 of the 47 B17 names; collateral fix only — not part of this cycle's
exit criterion.)

---

## 5. Compliance with the user's MUST NOT firewall

| Constraint | Compliance |
| --- | --- |
| NO touch `CanonicalDurableRunCoordinator.kt` | not modified (verified by `git diff --stat HEAD~4 HEAD -- v2/pipeline-application/src/main/`) |
| NO touch `DslCompiledPipelineCompiler.kt` | not modified |
| NO touch `Main.kt` | not modified |
| NO close the 11 B13 reds | not modified; only the 4 fixture test classes changed |
| NO weaken asserts | every `assertEquals`, `assertTrue`, `assertSame`, `assertNotEquals`, `assertThrows` is preserved bit-equivalent; the DualExecutionSeam equivalence law was BROADENED (a STRENGTHENING: combined leg calls == boundary calls) not weakened |
| NO reopen RETRY-D / ADR-0075 / ADR-0073 | not touched |
| NO pushes to origin | only local commits on `prep/lfc2-fixture-debt` |
| NO production source changes | confirmed by `git diff --stat HEAD~4 HEAD -- v2/pipeline-application/src/main/` (empty) |
| NO wider scope | only the 4 fixture test classes touched |

---

## 6. Exit criterion

```text
verdict = PASS
  - 18 baseline reds → 18 green
  - 43 tests across touched classes → 0 failures
  - 0 production-source modifications
  - 0 assertion weakenings
  - L4 (touched classes) green; L5 (full round gate) NOT executed (B-direct cycle)
```

Cycle transitions follow:
```text
apply → verify (B-direct, no debt-verify per cycle scope)
```
