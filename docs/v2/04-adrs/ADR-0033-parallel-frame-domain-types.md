# ADR-0033: ParallelFrame and BranchSpec as Pure-Domain Types

- **Status:** Accepted for M3-R4.2
- **Date:** 2026-08-24
- **Decision owners:** Pipeline Kotlin maintainers
- **M3-R4.2 Implementation:** T-01 (C1)

## Context

The durable execution model for parallel frames requires first-class domain types that represent parallel branches without coupling to the execution engine. Prior to this ADR, `PipelineSpec` DSL contained parallel semantics but no explicit `ParallelFrame` type existed in the domain layer, forcing engine-level concerns (branch coordination, join policies) into what should be pure DSL definitions.

The design goal is: `ParallelFrame` and `BranchSpec` must be **pure domain types** usable in the `PipelineSpec` DSL without any engine coupling.

## Decision

Introduce the following types in `v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/`:

```kotlin
sealed interface PipelineFrame
data class ParallelFrame(
    val branches: List<BranchSpec>,
    val joinPolicy: JoinPolicy
) : PipelineFrame
data class BranchSpec(
    val name: String,
    val steps: List<StepSpec>
)
enum class JoinPolicy {
    ALL_COMPLETE,  // wait for all branches to finish
    FIRST_SUCCESS,  // succeed when first branch succeeds, cancel others
    ANY_COMPLETE    // succeed when any branch finishes
}
```

### Design constraints

1. **`PipelineFrame` is a new sealed interface** — it does **not** replace `PipelineSpec`. Existing DSL code remains unchanged. `PipelineFrame` coexists as a parallel branch representation.

2. **No engine types in domain** — `ParallelFrame` has no dependency on `OperationContext`, `ReplayCursor`, or any execution artifact. It is pure data.

3. **JoinPolicy is a domain enum** — not a boolean flag. The three policies are explicit, avoiding ambiguity in conditional logic.

4. **`BranchSpec.steps` uses existing `StepSpec`** — the same step specification used by sequential pipelines, ensuring consistent execution semantics.

## Alternatives Considered

1. **Extend `PipelineSpec` with a `parallel` flag** — rejected; mixes sequential and parallel concerns in one type, violating Single Responsibility.

2. **Use a sealed class hierarchy with `SequentialFrame : PipelineFrame` and `ParallelFrame : PipelineFrame`** — rejected for now; sequential frames are already represented by `PipelineSpec`. Introducing a parallel-specific frame interface keeps migration cost low.

3. **BranchSpec as an interface** — rejected; data class is simpler and sufficient. No need for open extension points at this stage.

## Consequences

- DSL authors can define parallel branches using `parallelFrame { ... }` syntax in `PipelineSpec`
- Engine code receives a `ParallelFrame` instance and handles execution via `walkParallelFrame`
- Join barrier semantics are driven by `JoinPolicy` enum (enforced in `advancePastParallelFrame`)
- No V1 modules affected

## Evidence and Provenance

- M3-R4.2 design decision from `design.md` §C1
- ADR-0035 governs join barrier semantics for `advancePastParallelFrame`
