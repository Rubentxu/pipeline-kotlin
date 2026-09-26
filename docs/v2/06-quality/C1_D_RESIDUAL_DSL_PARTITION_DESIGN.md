# C1-D residual DSL partition design

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-c1-d-dsl-residual-partition`
**Specification:** `docs/v2/06-quality/C1_D_RESIDUAL_DSL_PARTITION_SPEC.md`
**Authority:** `docs/v2/04-adrs/ADR-0094-dsl-source-partition-without-semantic-change.md`

## Design choice

Perform one mechanical source move: relocate the complete `StepSpec` sealed hierarchy from `PipelineDsl.kt` to `PipelineDslSteps.kt`, preserving the package and declarations. Keep all builder/scoping code in `PipelineDsl.kt` for this increment.

This is deliberately narrower than the original C1 analysis table. The existing code does not expose a cohesive validation service. Creating one only to satisfy a file-count target would add indirection without a stable seam.

## File boundaries

### `PipelineDslSteps.kt`

Contains:

- package declaration and imports required by `StepSpec` declarations;
- `sealed interface StepSpec`;
- every nested `StepSpec` subtype, including deprecated compatibility subtypes and credential binding types;
- no builder receiver, mutable builder list, runtime configuration, process access, registry lookup, or compiler call.

### `PipelineDsl.kt`

Retains:

- top-level `pipeline` builders;
- DSL marker annotations;
- `PipelineScope`, `StagesScope`, `StageScope`, and all nested scope holders;
- builder methods that construct `StepSpec` values;
- runtime configuration access and placeholder policy already covered by existing tests;
- `StageBuilder` and credential conversion extension.

## Mechanical implementation method

1. Copy the exact `StepSpec` declaration block into `PipelineDslSteps.kt` with no semantic edits.
2. Remove exactly that block from `PipelineDsl.kt`.
3. Keep imports in the file that uses them. Do not introduce wildcard imports or new dependencies.
4. Compile before changing tests.
5. Search for source scans that hard-code `PipelineDsl.kt` as the location of `StepSpec`; update only path assertions, preserving their semantic assertions.
6. Run the focused DSL and architecture tests from the specification.
7. Run the affected API compatibility check and verify no public surface change.

## Risk controls

- Same package means imports at consumers remain unchanged.
- Kotlin nested type names remain `StepSpec.<Subtype>`.
- No `private` declaration from the parent file is referenced by the moved types. If compilation proves otherwise, move the smallest required declaration with the type and document it rather than widening visibility.
- No formatter or broad refactor is included.
- No test assertion is weakened to accommodate the move.

## Test plan

L0:

- `cd v2 && timeout 600 ./gradlew :pipeline-scripting-api:compileTestKotlin --console=plain`

L1/L2 affected tests:

- `PipelineDslSealedHierarchyTest`
- `PipelineDslTopStepsTest`
- `PipelineDslPwdLoweringTest`
- `PipelineDslWithCredentialsTest`
- `PostDslFailClosedTest`
- `RegistryBlockDslLoweringTest`
- `CheckoutDslTest`
- `ScriptedSourceLocationTest`

Architecture and visibility closure:

- `Lpr101L4SweepCharacterizationTest`
- `FArchL7JenkinsVerbatimStepTest`
- `WULpr402RuntimeHonestDslFitnessTest`
- scripting host visibility tests that mention `PipelineSpec`, `StepSpec`, or `StageScope`

The full repository check and external harness remain outside this bounded source-only slice.

## Release/receipt impact

This design is a source-only refactor. It does not require a new binary candidate unless implementation changes reveal API or behavior drift. If the focused verification is green and the working tree contains only this cycle's files, integrate the atomic implementation and receipt commits into `main` according to the active release policy.

Reference implementation consulted: none applicable. No Jenkins behavior is changed.
