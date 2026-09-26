# ADR-0094: DSL source partition without semantic change

- **Status:** Accepted for C1-D residual partition
- **Date:** 2026-09-26
- **Scope:** `pipeline-scripting-api` source organization only
- **Supersedes:** the incorrect C1 analysis reference to “ADR-0078 DSL split”; ADR-0078 is the accepted ResourceRef identity decision

## Context

`PipelineDsl.kt` remains a large Kotlin source unit. A previous C1-D increment already extracted the pure model data types into `PipelineDslTypes.kt` (`ce2a7abe`). The remaining file still combines the sealed `StepSpec` algebra, DSL scopes, builders, runtime-config seams, body capture, and credential conversion.

The C1 analysis referred to a DSL partition as ADR-0078, but the repository's ADR-0078 is unrelated. Continuing under that label would create false traceability.

## Decision

Complete the next C1-D increment as a **physical source partition with zero semantic or public API change**:

1. Keep package and fully-qualified names unchanged.
2. Keep all `StepSpec` subtype names, constructor signatures, sealed hierarchy membership, defaults, and serialization-visible properties unchanged.
3. Extract the sealed `StepSpec` hierarchy from `PipelineDsl.kt` into `PipelineDslSteps.kt` in the same package.
4. Do not extract `StageScope` in this increment.
5. Do not create a `PipelineDslValidation.kt` façade merely to relocate individual `require` statements. Validation remains at the builder boundary until a cohesive pure validation contract is specified.
6. Do not introduce new runtime effects, registries, adapters, or dependencies.
7. Update only source-level fitness assertions whose wording assumes every DSL declaration lives in `PipelineDsl.kt`; do not weaken their semantic checks.

`PipelineDslTypes.kt` remains the already completed first partition. This ADR defines the residual slice that is actually justified by the current code, rather than repeating completed work or inventing an artificial validation layer.

## Consequences

### Positive

- Reduces the responsibility and size of `PipelineDsl.kt` without changing the DSL contract.
- Gives the large sealed step algebra an explicit source boundary.
- Keeps Kotlin binary names stable because the package and declarations remain unchanged.
- Makes future validation/lowering design evidence-based instead of file-size-driven.

### Constraints

- File-private declarations referenced by the moved step types must be avoided or moved with the types without changing visibility.
- Source-scanning tests must distinguish physical organization from architectural semantics.
- This does not claim that the full C1-D debt is closed. It closes only the StepSpec partition increment.

## Verification contract

The implementation is acceptable only if all of the following hold:

1. `PipelineDsl.kt` no longer declares the `StepSpec` sealed hierarchy.
2. `PipelineDslSteps.kt` declares the same hierarchy in the same package.
3. `PipelineDslSealedHierarchyTest`, `PipelineDslTopStepsTest`, and affected DSL tests pass.
4. `:pipeline-scripting-api:compileTestKotlin` passes.
5. Scripting host visibility and impacted architecture fitness tests pass.
6. `git diff --check` passes.
7. No public API baseline changes are introduced by the physical move.

Reference implementation consulted: none applicable. This is an internal Kotlin source partition, not a Jenkins behavior change.
