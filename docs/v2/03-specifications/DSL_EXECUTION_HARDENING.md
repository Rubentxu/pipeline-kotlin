# Specification — DSL and Execution Hardening for LPR

Status: PROPOSED
Related: ADR-0070, ADR-0073, ADR-0081, ADR-0083, ADR-0084.

## D1 — Declarative DSL only describes

Public declarative builders construct typed IR and may validate structure. They MUST NOT:

- execute process/filesystem/network effects;
- inspect mutable global runtime state;
- manufacture runtime-return placeholder values;
- depend on OperationJournal/EventStore adapters;
- silently discard unsupported constructs.

## D2 — Runtime-returning APIs live in scripted/runtime context

Operations such as `pwd()` and `isUnix()` that return values used by Kotlin control flow execute through an explicit runtime invocation seam.

```kotlin
script {
    val ws = pwd()
    val unix = isUnix()
    if (unix) sh("./gradlew test")
}
```

The returned value must be the real runtime value or a typed failure. No `<workspace>`, default `true`, or construction-time environment bridge is part of the stable contract.

## D3 — DSL marker and receiver discipline

Introduce one pipeline DSL marker across builder scopes and split the large DSL by ownership. Proposed package/file direction:

```text
dsl/model/       PipelineSpec, StageSpec, StepSpec, StepBodies
dsl/builder/     PipelineScope, StagesScope, StageScope, StepsScope
dsl/core/        core Step façades
dsl/registry/    generic registry/plugin façade
dsl/scripted/    ScriptRuntimeScope + runtime façade
```

File splitting is not a gate by itself; the gate is reduced connascence and receiver ambiguity.

## D4 — Generic body invocation

A body-bearing plugin compiles to generic structure (`None/Single/Named`) and executes by declared BodyExecution policy through BodyInvoker/BranchInvoker. No concrete plugin body subtype or `dispatchFooBlock` is permitted.

## D5 — Body execution policies

Initial LPR engine must interpret at least:

- Sequential;
- Scoped;
- Retrying;
- Parallel.

`RepeatUntil` is added before `waitUntil` is promoted to SUPPORTED. Policy selection is pure/pre-effect and not inferred from StepKey.

## D6 — Coordinator fitness

`CanonicalDurableRunCoordinator` MUST NOT:

- compare concrete Step IDs/names for semantics;
- decode plugin-specific payloads;
- implement retry/timeout/parallel/waitUntil loops directly after their engine migration;
- construct StepDefinitions;
- perform ServiceLoader discovery;
- select plugin implementation by `when`.

Allowed:

- run/stage lifecycle;
- structural ADT traversal;
- delegation;
- continuation decisions expressed by generic engine results.

## D7 — External body plugin proof

Before claiming body extensibility stable, an independently packaged plugin must define a block/named-body capability and execute through the real installed distribution with **zero Step-specific edits** to domain/application/compiler/coordinator/core registry.

## D8 — Unsupported honest failure

`when`, `post`, `load`, `waitUntil` or any other surface not certified for the release must either be absent/experimental with explicit guard or fail before effects. Existing syntax must not make unsupported semantics look successful.

## D9 — Typed outcomes

Control decisions operate on typed `RunOutcome`, `StageOutcome`, `StepOutcome`/failure ADTs. String conversion is a boundary concern for events/CLI compatibility, not control authority.

## D10 — Tests

Required fitness:

- `CoordinatorNoConcreteStepKnowledgeFitness`;
- `DslNoRuntimeEffectsFitness`;
- `NoFakeRuntimeValueFitness`;
- `ExternalBodyPluginFitness`;
- `BodyExecutionPolicyExhaustivenessFitness`;
- `RegistryInvocationBodiesParityTest`;
- compatibility corpus through installed distribution.
