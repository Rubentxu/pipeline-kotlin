# WU-LPR-301 — Legacy Execution Burn-Down Receipt

**Status**: ✅ `LEGACY_PLUGIN_IDS = ∅` (closed-set burn-down complete)
**Sequence**: G0..G8 (Step Constitution burn-down sequence; partial — registry-driven
              Steps were already CERTIFIED in S3/S4/S6, so the G7/G8 rows are
              inherited from those cycles)
**Worktree HEAD at close**: `8dba10f6` on `main` (locally committed, not pushed in this
                              slice — push checkpoint after WU-LPR-302 / WU-LPR-401 if
                              the checkpoint STOP is lifted)

## What this receipt proves

The **last two legacy keys** (`core.waitUntil`, `core.load`) are physically removed from
the legacy execution spine. Every surviving core plugin routes through the
`StepRegistry` family; the membership table `LEGACY_PLUGIN_IDS` converges to the empty
set; the `CanonicalCoreStepMetadata` table mirrors it. There is no production path
that executes a Step via the legacy decoder/dispatcher/metadata authority — that
authority is **closed** (the sealed `StructuralStepFamily.LegacyCore` discriminator
remains in the closed ADT for binary compatibility, but no row of the membership table
maps to it).

`core.waitUntil` is **REGISTRY_PRIMARY** via `CoreWaitUntilStep.registerInto`; the
polling cadence is declared structurally through
`BodyExecutionPolicy.Retrying(waitUntil = WaitUntilShape())` (no special dispatcher
collection, no `dispatchRetryBlock`/`dispatchWaitUntilBlock` branch).

`core.load` is **UNSUPPORTED in `local-core-v1`** (DEFERRED): the DSL `load(path)` is
admitted at construction (declarative IR) and rejected at runtime by the registry
admission gate (`EngineInvariantViolation`). The key is **not** in `LEGACY_PLUGIN_IDS`
and **not** in the registry — both authorities fail-closed.

## Burn-down counters (closed set)

```text
Certified Steps (registry-routed): 17        (CoreEchoStep, CoreShellStep, CoreSleepStep,
                                                 CoreWriteFileStep, CoreErrorStep,
                                                 CorePwdStep, CorePwdTmpStep,
                                                 CoreIsUnixStep, CoreDeleteDirStep,
                                                 CoreCleanWsStep, CoreArchiveArtifactsStep,
                                                 CoreEmitEventStep, CoreMilestoneStep,
                                                 CoreWaitUntilStep, …)
Legacy executable Steps:            0
Registry-primary Steps:            17
LEGACY_PLUGIN_IDS.size():           0
CanonicalCoreStepMetadata rows:     0
CanonicalCoreStepCommand subtypes: 13        (closed IR; Load + WaitUntil removed)
Legacy decoder branches:            0        (Load + WaitUntil decoder branches removed)
Legacy dispatcher files:            0        (CanonicalLoadNodeDispatcher.kt +
                                                 CanonicalWaitUntilNodeDispatcher.kt deleted)
```

## Gate receipt chain

| # | Slice | Commit | Test class | tests | failures | errors |
|---|-------|--------|------------|-------|----------|--------|
| 1 | WU-LPR-301 commit [1] `feat(domain)` BodyExecutionPolicy.Retrying(waitUntil=...) | `2bd2c37a` | `:pipeline-domain:test` | full | 0 | 0 |
| 2 | WU-LPR-301 commits [2]+[3]+[4] `refactor(application)` retire the last two legacy keys | `d8c37118` | `:pipeline-application:test` `:pipeline-domain:test` `:pipeline-step-sdk:runtime:test` | full | 0 | 0 |
| 3 | WU-LPR-301 commit [5] `test(application)` counter pins converge to 0/0/0 + metadata table empty | `022d38a8` | same + `--rerun-tasks` | 53 suites + 99 + 99 | 0 | 0 |
| 4 | WU-LPR-301 commit [6] `test(application)` characterization of closed burn-down | `8dba10f6` | `Lpr301BurnDownCharacterizationTest` | 5 | 0 | 0 |

## Coverage matrix (post-WU-LPR-301 / G5)

The certification evidence lives in the existing characterization suites, augmented by
the new `Lpr301BurnDownCharacterizationTest` (5 tests, structural burn-down shape):

| # | Coverage | Suite | Result |
|---|----------|-------|--------|
| 1 | burn-down counter — `LEGACY_PLUGIN_IDS` is the empty set | `Lpr301BurnDownCharacterizationTest` | ✅ |
| 2 | burn-down counter — `CanonicalCoreStepMetadata` has no rows for `core.load`/`core.waitUntil` | `Lpr301BurnDownCharacterizationTest` | ✅ |
| 3 | core.waitUntil registry-routed — composite resolver returns descriptor metadata | `Lpr301BurnDownCharacterizationTest` | ✅ |
| 4 | core.waitUntil registry-routed — `CoreWaitUntilStep.registerInto` carries the key | `WaitUntilStepContractSuiteTest` (18 rows, CERTIFIED S2-A8/G3) + `Lfc2WaitUntilCanonicalReentryFitnessTest` (4 rows) + `CoreWaitUntilStepUnitTest` | ✅ |
| 5 | core.load unregistered — composite resolver throws `EngineInvariantViolation` | `RegistryStepMetadataResolverTest > a formerly legacy core key absent from the registry fails closed` + `Lpr301BurnDownCharacterizationTest` | ✅ |
| 6 | core.load unregistered — DSL `load(path)` admitted at construction; canonical compiler lowers to `OpaqueStepNode(pluginStepId='core.load')` which fails the registry admission gate at runtime | `Lpr301BurnDownCharacterizationTest` | ✅ |
| 7 | architectural fitness — L4 sweep characterization pins the closed shape | `Lpr101L4SweepCharacterizationTest` (7/7 GREEN post-F-LE-1a / F-LE-2) | ✅ |
| 8 | architectural fitness — execution boundary factory preserves the closed ADT | `ExecutionBoundaryFactoryTest` (4/4 GREEN post-LegacyOnly-retarget) | ✅ |
| 9 | counter pins — every registry-primary fitness test asserts the empty `LEGACY_PLUGIN_IDS` set | `CoreArchiveArtifactsStepContractSuiteTest` + `CoreCleanWsStepContractSuiteTest` + `EmitEventStepContractSuiteTest` + `CoreEmitEventRegistryPrimaryFitnessTest` + `CoreErrorRegistryPrimaryFitnessTest` + `CoreIsUnixRegistryPrimaryFitnessTest` + `CorePwdRegistryPrimaryFitnessTest` + `CoreSleepRegistryPrimaryFitnessTest` + `CoreWriteFileRegistryPrimaryFitnessTest` + `A4_REGISTRY_PRIMARY_Core_Sh_Proof_Test` | ✅ |

## Files deleted (LEGACY_REMOVED, G5)

| Path | Reason |
|------|--------|
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalLoadNodeDispatcher.kt` | Last legacy core dispatcher for `core.load`. No longer reachable; no CoreLoadStep exists. |
| `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWaitUntilNodeDispatcher.kt` | Last legacy core dispatcher for `core.waitUntil`. Replaced by `CoreWaitUntilStep.registerInto(CoreStepRegistryFactory)` + `BodyExecutionPolicy.Retrying(waitUntil = ...)`. |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalWaitUntilNodeDispatcherTest.kt` | Dispatcher-specific characterization of a file that no longer exists. |

## Production wire-up

The production spine is wired through the registry-routed `core.waitUntil` path:

```kotlin
// StepDescriptorRegistry.standard() declares the waitUntil sub-shape:
StepDescriptor(
    stepId = "core.waitUntil",
    name = "waitUntil",
    configRef = "core/waitUntil",
    executionLocation = ExecutionLocation.CONTROLLER,
    effects = listOf(Effect.READ_ONLY, Effect.MEMOIZED),
    replayPolicy = ReplayPolicy.RERUN,
    recoveryPolicy = RecoveryPolicy.None,
    bodyExecutionPolicy = BodyExecutionPolicy.Retrying(
        maxAttempts = Int.MAX_VALUE,
        waitUntil = WaitUntilShape(initialRecurrencePeriodMs = 1000L, quiet = false),
    ),
)
```

The coordinator dispatches the polling loop through `BodyInvoker.invoke(...)` with
the structural `BodyExecutionPolicy.Retrying(waitUntil = ...)` payload — the same
machinery that powers `retry` blocks, no per-Step `dispatchWaitUntilBlock` collection.
The retry-vs-waitUntil split is a property of the **policy**, not of the Step key
(`StepDescriptor.bodyExecutionPolicy`); the engine reads the policy, it does not read
the Step key (per ADR-0070..0074).

## What is NOT in scope (and why)

```text
- A `BranchInvoker` was NOT created (confirmed: BodyRefs.branchBody()/namedBody()
  already cover the branch shape; creating BranchInvoker would be an unforced
  abstraction).
- `core.load` was NOT re-registered as a `CoreLoadStep`; it is intentionally
  UNSUPPORTED in `local-core-v1` (DEFERRED; see WU-LPR-000 dispositions). The DSL
  `load(path)` is admitted at construction and rejected at runtime. Adding a
  CoreLoadStep would re-open the implementation surface for a deferred feature.
- The closed `StructuralStepFamily.LegacyCore` discriminator was NOT removed from
  the ADT; it remains in the sealed type for binary compatibility. With an empty
  membership table, the discriminant is unreachable on every production wiring,
  but the closed type preserves the structural invariant that the resolver's
  output is closed.
- The `CanonicalCoreStepCommand` sealed IR was NOT removed; Load + WaitUntil
  subtypes were deleted, leaving 13 concrete subtypes (all in registry-primary
  routing). The sealed IR stays as the structural envelope the decoder consumes.
```

## Verification evidence

- `v2/pipeline-application:test` — full rerun, 55 suites, 0 failures, 0 errors (after
  `--rerun-tasks`, post-commit [5] and [6]).
- `v2/pipeline-domain:test` — 99 suites, 0 failures, 0 errors.
- `v2/pipeline-step-sdk:runtime:test` — all suites, 0 failures, 0 errors.
- Architecture fitness (`Lpr101L4SweepCharacterizationTest` 7/7, F-LE-1a + F-LE-2 fixes
  applied) — green.

## Next slice

Auto-continue to **WU-LPR-302** (body/control consolidation: realize
`waitUntil → RepeatUntil policy → BodyInvoker`, inventory `BodyInvoker /
CanonicalBodyInvokerAdapter / BodyRef(s) / BodyExecutionPolicy / retry/timeout/parallel
/waitUntil machinery / durable control rows / coordinator body dispatch`). Do NOT
create `BranchInvoker`; do NOT widen the scope.
