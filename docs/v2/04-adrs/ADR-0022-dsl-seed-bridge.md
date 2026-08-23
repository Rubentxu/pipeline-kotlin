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

3. **Schema evolution is purely additive (no version bump).** The `JsonEventLog` wire
   format is self-describing via the `kind` discriminator. New event variants
   (`StageStarted`, `StageFinished`, `StepStarted`, `StepFinished`) are decoded by
   old decoders as `null` and skipped — no breaking change. The cache-key `version`
   remains `"v1"`; no `EVENT_SCHEMA_VERSION` constant is added. Schema is
   unchanged at `v1`; only event *variants* are added (additive).

4. **DSL lives in `pipeline-scripting-api` (not `pipeline-application`).** The DSL
   module (`pipeline-scripting-api`) is on the application runtime classpath but
   *not* on the Kotlin script host's compile classpath. To make DSL functions
   visible to script compilation, the `pipeline-scripting-api` JAR is explicitly
   added via `ScriptDefinition.classpath` → `updateClasspath(classpathFiles)`
   in `Kotlin24ScriptingHost.compile()`. This avoids `wholeClasspath=true`
   (prohibited by M1 Exit constraints) while still making the DSL reachable.
   See `ScriptDefinition.dslApiJar()` for the discovery mechanism.

5. **No new Gradle module.** All DSL types live in
   `pipeline-scripting-api/src/main/kotlin/com/pipeline/v2/dsl/`.

## Risks & Open Questions

- Whether `sh("…")` side-effects during script evaluation are acceptable in M1-R3
  (they are, because the test fixture uses a harmless `echo` command).
- The M2 grammar will likely replace the Kotlin builder DSL with a proper parser —
  this ADR establishes the event contract that the grammar must preserve.

## M2-R1 Extension

**Status**: Accepted — M2-R1 (A-lite, first M2 slice)

This section extends ADR-0022 to cover the full DSL grammar extension on top of the M1-R3 bridge.

### In Scope (M2-R1)

- `PipelineDsl.kt` — extended with full Jenkins-familiar grammar:
  - `StepSpec.Error` and `StepSpec.Sleep` step types (record-only)
  - `AgentSpec`, `EnvironmentSpec`, `OptionsSpec`, `RetrySpec`, `TimeoutSpec`, `TimeoutAction`
  - `PostConditionSpec`, `WhenCondition`, `ParallelBranchSpec`
  - Builder functions: `agent()`, `environment {}`, `options {}`, `post {}`, `parallel {}`, `branch()`, `retry()`, `timeout()`, `whenCondition()`, `script {}`
- `FailureKind` enum in `pipeline-domain` (`INFRASTRUCTURE`, `NETWORK`, `SCRIPT`, `USER`, `TIMEOUT`, `UNKNOWN`)
- 6 new `DomainEvent` variants (additive, no schema bump):
  - `AgentResolved` — agent label resolved for parallel execution
  - `ParallelBranchStarted` / `ParallelBranchFinished` — parallel branch lifecycle
  - `RetryAttemptStarted` / `RetryAttemptFinished` — retry attempt lifecycle
  - `TimeoutScheduled` — timeout configuration recorded
- `JsonEventLog` encode/decode for the 6 new variants (plus cleaned 8 Elvis dead-code sites)
- `InMemoryEventStore` when-arms for the 6 new variants
- `SqliteEventStore` KDoc records M2-R1 variants
- `PipelineRun.kt` — extended `walkPipelineSpec()` with helper functions for new step types and events
- `grammar-full.pipeline.kts` — full grammar fixture exercising all M2-R1 capabilities
- `parallel.pipeline.kts` — parallel block fixture
- `timeout-retry.pipeline.kts` — timeout + retry fixture
- `UatDsl001JenkinsFamiliarityTest` — full grammar UAT
- `UatDsl003ParallelTest` — parallel branch UAT
- `UatDsl005TimeoutGrammarTest` — timeout + retry UAT

### Out of Scope (M2-R2 and beyond)

- `@Step` annotation (requires KSP)
- KSP generator for step types
- Context capability API
- Jenkins familiarity metadata (M2-R3)
- LSP metadata (M2-R3)
- Real `sh` execution (M4 UAT-STEP-001)
- Durable execution for retry/timeout/parallel/script (M3)
- `:pipeline-steps-system:compiler-plugin` reach (F-ARCH-004)

### Design Decisions (M2-R1)

1. **DSL surface is grammar-only (no durable execution).** The new step types (`error`, `sleep`) and constructs (`parallel`, `retry`, `timeout`) record their intent in the event log but do not execute durably. Durable execution is deferred to M3.

2. **6 new events are additive (no schema bump).** Following the M1-R3 pattern, the `kind` discriminator allows old decoders to skip unknown variants. `SqliteEventStore` KDoc is updated to document the new variants.

3. **`FailureKind` lives in `pipeline-domain`.** This domain enum is imported by the DSL via the `pipeline-scripting-api` classpath, keeping domain concepts separate from the DSL layer.

4. **`parallel {}` emits ParallelBranchStarted/Finished events.** The `BranchScope` collects steps that are flattened into the stage's step list, and parallel branch events are emitted around them.

5. **`retry()` and `timeout()` configure steps, not durable execution.** These DSL constructs set metadata on the `OptionsSpec` that is recorded in events, but the actual retry/timeout logic is not implemented in this slice.

## References

- Design: `cycle-artifacts/p-733fb505b5a6bd2d/m2-r1-dsl-grammar/design.md`
- Specification: `cycle-artifacts/p-733fb505b5a6bd2d/m2-r1-dsl-grammar/specification.md`
- F-ARCH-003: `kotlin.script.experimental` containment
- F-ARCH-004: No `:pipeline-steps-system:compiler-plugin` reach
