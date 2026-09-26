# C1-D residual DSL partition specification

**Cycle:** `p-733fb505b5a6bd2d/rp-053r-c1-d-dsl-residual-partition`
**Base:** `9e26cc65902fdb0b4e381cc36e4f6ca4acd1762f`
**Authority:** ADR-0094

## Intent

Reduce the remaining responsibility of `PipelineDsl.kt` without changing the public DSL, the sealed step algebra, compiler behavior, scripting visibility, or runtime semantics.

## In scope

- Move the complete `StepSpec` sealed hierarchy into `PipelineDslSteps.kt`.
- Preserve the package `dev.rubentxu.pipeline.v2.dsl`.
- Preserve every fully-qualified subtype name, constructor, default, property, annotation, deprecation marker, and nested type.
- Preserve the existing `PipelineDslTypes.kt` extraction. Do not copy or recreate its declarations.
- Update only source-level tests whose path or file-local assumptions become false after the move.
- Add a focused source-organization assertion if the existing tests do not prove the new boundary.

## Out of scope

- No `StageScope` extraction.
- No change to validation behavior, error messages, fail-closed behavior, runtime configuration, or lowering.
- No rename of `StepSpec` or any subtype.
- No new public API, dependency, adapter, registry path, or generated code.
- No broad cleanup of the 2442-line DSL file.
- No claim that all C1-D debt is closed after this increment.

## Requirements and scenarios

### R1: Stable sealed hierarchy

The moved `StepSpec` interface and all nested subtypes SHALL remain in the same package with the same Kotlin names and signatures.

**Scenario:** Existing code importing `dev.rubentxu.pipeline.v2.dsl.StepSpec` and its nested subtypes compiles without source changes.

### R2: Physical partition

`PipelineDsl.kt` SHALL no longer declare the `StepSpec` sealed hierarchy. `PipelineDslSteps.kt` SHALL declare it exactly once.

**Scenario:** A source scan finds one `sealed interface StepSpec` declaration and it is located in `PipelineDslSteps.kt`.

### R3: DSL builder behavior unchanged

Existing builders SHALL construct the same `StepSpec` values for the same inputs.

**Scenario:** Existing DSL tests for echo, shell, registry steps, credentials, blocks, and top-level builders pass without assertion weakening.

### R4: Scripting visibility unchanged

The scripting host SHALL continue exposing `PipelineSpec`, `StepSpec`, and `StageScope` under their existing fully-qualified names.

**Scenario:** Kotlin script compilation and visibility tests pass with the moved source declaration.

### R5: No accidental API drift

The physical move SHALL not alter the public binary/API surface.

**Scenario:** The affected API compatibility check reports no new or removed public declaration attributable to the move.

### R6: Source-fitness integrity

Architecture tests that inspect DSL source SHALL continue to assert semantic rules. They may be updated only to follow the new file location, never to remove the rule.

**Scenario:** affected architecture fitness tests pass and still reject construction-time I/O, fake runtime values, and forbidden legacy authority.

## Exit criteria

- ADR-0094 and this specification are present and indexed.
- The implementation changes only the physical location of `StepSpec` declarations plus necessary source-test path updates.
- Focused compile and DSL tests pass.
- Relevant architecture/scripting visibility tests pass.
- `git diff --check` passes.
- A receipt records exact commands, exit codes, output/XML digests, and the fact that full repository `check` and external harness were not run.

## Reference research

Reference implementation consulted: none applicable. This is an internal Kotlin source partition and does not alter Jenkins-compatible step behavior.
