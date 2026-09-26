# C1-D residual DSL partition exploration

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-c1-d-dsl-residual-partition`
**Base SHA:** `9e26cc65902fdb0b4e381cc36e4f6ca4acd1762f`
**Observed:** 2026-09-26

## Objective

Determine whether the remaining C1-D debt is still executable, avoid repeating work already present in `main`, and establish the smallest safe next slice for the large DSL unit.

## Observed facts

- `PipelineDsl.kt` currently has 2442 lines.
- `PipelineDslTypes.kt` already exists and is the result of commit `ce2a7abe` (`refactor(scripting): extract pure DSL model types`). It contains `PipelineSpec`, `StageSpec`, `AgentSpec`, `EnvironmentSpec`, `OptionsSpec`, `TimeoutSpec`, `TimeoutAction`, `PostConditionSpec`, and `WhenCondition`.
- The C1-D extraction is therefore **partially implemented**. Repeating the model-type extraction would be duplicate work.
- The remaining file still contains the `StepSpec` sealed hierarchy, top-level `pipeline` builders, all scope classes, `StageScope` step builders, runtime-config access, body capture, post/parallel/script scope builders, `StageBuilder`, and credential conversion.
- No `PipelineDslValidation.kt` or `PipelineDslLowering.kt` exists in the current source tree.
- The current code contains validation and fail-closed admission checks interleaved with DSL builders. Examples include the positive `milestone` ordinal check and rejection of unsupported post conditions.
- The public and cross-module surface is broad. Consumers include `pipeline-application`, `pipeline-scripting-kotlin24`, workflow/file/runtime SDK modules, credential executor, external Step DSL extensions, and architecture fitness tests.
- Existing tests cover DSL lowering, sealed hierarchy, top-level builders, credentials, post fail-closed behavior, registry block lowering, scripting visibility, and architecture source scans.

## Authority discrepancy

The C1 isolation document calls the planned DSL partition “ADR-0078”, but the repository's actual `docs/v2/04-adrs/ADR-0078-resource-ref-identity.md` is an unrelated ResourceRef decision. No accepted DSL-partition ADR was found. The isolation document is valid evidence of the debt and proposed shape, but it is not itself an accepted ADR.

This means the implementation slice must not silently claim that an ADR-0078 DSL contract exists. The next phase must either:

1. produce and accept a correctly numbered DSL-partition decision/specification before changing source structure, or
2. explicitly constrain the change to a mechanically safe extraction covered by an existing accepted contract and document why no new decision is required.

## Proposed residual scope

Do not extract `StageScope` in this slice. Preserve all public names, constructors, sealed subtype names, file-fitness assumptions, and DSL behavior.

The candidate residual partition is:

- `PipelineDsl.kt`: public builders, scopes, and compatibility-facing DSL declarations that must remain source-visible to scripting consumers.
- `PipelineDslValidation.kt`: pure validation/admission functions only, with typed or existing failure behavior preserved. No runtime effects.
- Optional `PipelineDslLowering.kt`: only if a coherent pure lowering seam is identified after the validation boundary. Do not create a façade that merely moves methods without reducing responsibility.

The next specification must decide whether `StepSpec` remains in `PipelineDsl.kt` or moves to a dedicated file. The earlier analysis is inconsistent on this point, so it must be resolved before edits.

## Verification impact

Minimum expected verification for a later implementation slice:

- `:pipeline-scripting-api:compileTestKotlin`
- affected DSL test classes in `pipeline-scripting-api`
- source-level architecture tests that scan `PipelineDsl.kt`
- scripting host visibility tests if declarations move files
- direct consumers only where the moved symbol boundary is exercised

The full repository `check` and external harness are not justified for this exploration-only artifact.

## Decision

C1-D remains an open debt item, but it is **not implementation-ready under the label ADR-0078**. The cycle should advance to specification/design, carrying forward the partial extraction at `ce2a7abe` and the missing-authority finding. No production code was changed by this exploration.

Reference implementation consulted: none applicable. This is an internal source partition, not a Jenkins behavior change.
