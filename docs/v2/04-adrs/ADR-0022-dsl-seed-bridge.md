# ADR-0022: DSL Seed Bridge — M1-R3

## Status

Accepted — M1-R3 (A-lite bridge cycle)

## Context

M1-R3 introduces a minimal DSL seed (`pipeline { stages { stage("name") { echo("…") / sh("…") } } }`)
as the first step toward the M2 grammar. This ADR captures the IN/OUT scope boundary
between the M1 substrate and the M2 grammar work.

## In Scope (M1-R3)

- `PipelineDsl.kt` — pure Kotlin DSL builder functions (`pipeline {}`, `stages {}`, `stage("name") {}`, `echo()`, `sh()`)
- `PipelineSpec` / `StageSpec` / `StepSpec` data types
- 4 new `DomainEvent` variants: `StageStarted`, `StageFinished`, `StepStarted`, `StepFinished`
- `JsonEventLog` encode/decode for the 4 new variants
- `InMemoryEventStore` when-arms for the 4 new variants
- `PipelineRun.walkPipelineSpec()` — walks a `PipelineSpec` and emits stage/step events
- `multi-step.pipeline.kts` fixture
- `UatEvt002MultiStepReplayTest`
- `hello.pipeline.kts` fixture updated to use DSL syntax

## Out of Scope (deferred to M2)

- `@Step` annotation (requires KSP)
- KSP generator for step types
- `error` / `sleep` step types
- `post` / `environment` / `agent` directives
- Context parameters / Jenkins familiarity / LSP metadata
- `:pipeline-steps-system:compiler-plugin` reach (F-ARCH-004)
- Real `sh` execution (record-only `sh` in M1-R3)
- DSL expression grammar (M2 grammar layer)

## Key Design Decisions

1. **DSL is a plain Kotlin builder, not a script模板.** `pipeline {}` returns a `PipelineSpec`
   data class; no `kotlin.script.experimental.*` APIs are used in the DSL itself.

2. **Events are record-only.** `StepStarted` / `StepFinished` are emitted but `sh("…")`
   does not spawn a subprocess. The `StepSpec.Shell` records the command for future
   execution by the agent layer.

3. **Schema evolution.** `JsonEventLog` schema bumped to `v1.1` to distinguish
   stage/step events from the v1.0 run/compilation events.

4. **No new Gradle module.** All DSL types live in
   `pipeline-application/src/main/kotlin/com/pipeline/v2/application/dsl/`.

## Risks & Open Questions

- Whether `sh("…")` side-effects during script evaluation are acceptable in M1-R3
  (they are, because the test fixture uses a harmless `echo` command).
- The M2 grammar will likely replace the Kotlin builder DSL with a proper parser —
  this ADR establishes the event contract that the grammar must preserve.

## References

- Design: `cycle-artifacts/m1-r3-dsl-seed/design.md`
- Specification: `cycle-artifacts/m1-r3-dsl-seed/specification.md`
- F-ARCH-003: `kotlin.script.experimental` containment
- F-ARCH-004: No `:pipeline-steps-system:compiler-plugin` reach
