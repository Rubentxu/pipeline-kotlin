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

6. **DSL shape divergence from DSL_SPEC §5-§8.** The M2-R1 DSL grammar implementation chose builder signatures that deviate from the canonical Jenkins DSL_SPEC.md §5-§8:
   - `parallel { }` is implemented as a nested block, not as a flat list of stages
   - `retry(N) { }` and `timeout(N) { }` are recorded via direct OptionsSpec setters, not via the typed builder functions
   - `step("name") { }` sugar is not provided; only `echo()`/`sh()`/`error()`/`sleep()` step types are recognized
   - `stage("name", id="...")` id decoration is not supported

   These deviations are documented because the M2-R2 Step SDK makes the canonical step types first-class via KSP-generated descriptors. Future cycles (M2-R3+) may align the DSL surface with DSL_SPEC.md §5-§8 if JenkinsSurface metadata requires literal signature compatibility.

## M2-R2 Extension

**Status**: Accepted — M2-R2 (A-lite, Step SDK + KSP)

This section extends ADR-0022 to cover the Step Plugin SDK v2 with KSP descriptor generation and first-class `echo`/`sh`/`error`/`sleep` step execution.

### In Scope (M2-R2)

- Three new Gradle subprojects under `v2/pipeline-step-sdk/`:
  - `api` — `@Step` annotation, `StepContext`, widened `StepDescriptor` (16 fields), enums
  - `processor` — KSP `SymbolProcessor` emitting `GeneratedStepDescriptors.kt`
  - `runtime` — annotated `echo`/`sh`/`error`/`sleep` + `ProcessExecutor` + `ShellResult`
- KSP `2.3.11` + Kotlin `2.4.10` (official recipe per kotlinlang.org/docs/ksp-quickstart.html)
- 2 new additive `DomainEvent` variants:
  - `StepFailed` — emitted when an error step is executed
  - `EchoOutputCaptured` — emitted when echo/sh captures output
- `JsonEventLog` encode/decode for the 2 new variants
- `InMemoryEventStore` when-arms for the 2 new variants
- `pipeline-domain/StepDescriptor` widened from 3 to 16 fields with additive defaults
- 4 new UAT fixtures: `sh-exec.pipeline.kts`, `echo-capture.pipeline.kts`, `error-abort.pipeline.kts`, `sleep-timing.pipeline.kts`
- 4 new UAT tests: `UatStep001ShExecutionTest`, `UatStep002EchoCaptureTest`, `UatStep003ErrorAbortTest`, `UatStep004SleepTimingTest`

### Out of Scope (M2-R3 and beyond)

- Context capability API (M3)
- Jenkins familiarity metadata (M3)
- LSP metadata (M3)
- Durable execution for retry/timeout/parallel/script (M3)
- `:pipeline-steps-system:compiler-plugin` reach (F-ARCH-004)

### Design Decisions (M2-R2)

1. **Three separate Gradle subprojects.** KSP plugin `apply true` only in `:pipeline-step-sdk:runtime`. Clean hexagonal boundaries; F-ARCH-001/002/004 stay PASS unambiguously.

2. **StepDescriptor widening is additive (no schema bump).** Original M0-R3 fields (`id`, `type`, `configRef`) remain at positions 1-3. 13 new fields default to zero/empty. `HelloPipelineFixture` compiles unchanged.

3. **2 new events are additive (no schema bump).** Following the M1-R3 pattern, the `kind` discriminator allows old decoders to skip unknown variants.

4. **`ProcessBuilder` moved to `:pipeline-step-sdk:runtime`.** The F-ARCH-002 scope-shift moves real `sh` execution from the orchestrator path into the new runtime module. `ProcessBuilder(List<String>)` is used directly (no shell parsing).

5. **`timeout()` body is intentional empty placeholder.** `PipelineDsl.kt:286-291` `timeout(seconds: Long) { }` builder function has an empty conditional body. This is intentional: timeout configuration is recorded via `walkPipelineSpec` reading `stage.options.timeout` (typed setter path), not via the builder function (syntactic-sugar path). The empty body is the syntactic bridge — its only purpose is to allow `timeout(N) { ... }` blocks in DSL fixtures without producing spurious events. Event emission is driven by the typed `OptionsSpec.timeout` field, which is populated by direct DSL property setters.

### Empty timeout() body rationale

`PipelineDsl.kt:286-291` `timeout(seconds: Long) { }` builder function has an empty conditional body. This is intentional: timeout configuration is recorded via `walkPipelineSpec` reading `stage.options.timeout` (typed setter path), not via the builder function (syntactic-sugar path). The empty body is the syntactic bridge — its only purpose is to allow `timeout(N) { ... }` blocks in DSL fixtures without producing spurious events. Event emission is driven by the typed `OptionsSpec.timeout` field, which is populated by direct DSL property setters.

If a future cycle wants the timeout to control runtime behavior, that work belongs to M3 BACKLOG E4-09 ("timeout frames") and is OUT OF SCOPE for M2-R2.

## M2-R3 Extension

**Status**: Accepted — M2-R3 (A-lite, final M2 slice)

This section extends ADR-0022 to close the three remaining M2 deliverables: Jenkins Surface metadata (E3-10), LSP metadata (E3-08), and compatibility corpus (E2-06).

### In Scope (M2-R3)

- `@JenkinsSurface` annotation + `CompatibilityLevel{F0,F1,F2,F3}` enum in `:pipeline-step-sdk:api`
- `jenkinsSurface: String` field on `StepDescriptor` populated via KSP-generated `GeneratedStepDescriptors.kt`
- 4 JSON resource files at `META-INF/pipeline/step-metadata/{stepId}.json` (one per step type)
- `LspMetadata` data class + `LspMetadataLoader` runtime API in `:pipeline-step-sdk:api`
- 6 corpus fixtures under `v2/compatibility/`: 01-basic, 02-environment, 03-stages, 04-sh, 05-scripted-if, 06-loop
- `CompatibilityCorpusTest` smoke runner + `CorpusNormalizer` + `CorpusSnapshotDiffer` + `baseline.json`
- `UatCompat001CorpusSmokeRunTest` end-to-end UAT

### Out of Scope (R3 carry-forwards and M3)

- KSP enum/array extraction upgrade (R3 carry-forward, M3+)
- Context capability API (R4 carry-forward, M3)
- DSL shape alignment with `DSL_SPEC.md §5-§8` (R5 carry-forward, M3)
- 9 deferred corpus fixtures: `retry`, `timeout`, `parallel`, `credentials`, `kubernetes-agent`, `plugin-imports`, `compilation-error`, `deprecated-api`, `source-mapping` (M3+)
- Real LSP server implementation (M3+)
- Protobuf migration of step metadata (M4/E5-01)

### Design Decisions (M2-R3)

1. **Single `jenkinsSurface: String` field (triple format).** The proposal suggested 4 new nullable fields (`jenkinsStep`, `jenkinsPlugin`, `jenkinsCompatibility`, `jenkinsSurfaceAnnotations`). The spec overrides this: single `jenkinsSurface: String` with format `"<step>|<plugin>|F<n>"`. Minimal additive footprint.

2. **Hardcoded `name → JenkinsSurfaceMeta` map (R3 workaround).** KSP 2.3.11 cannot reliably extract `CompatibilityLevel` enum values from annotation arguments. The M2-R2 workaround (same pattern for `ExecutionLocation`/`effects`/`replay`) is extended to cover `@JenkinsSurface`. Documented as R3 partial close; KSP upgrade (M3+) will migrate to reflective extraction.

3. **JSON resource at `META-INF/pipeline/step-metadata/{stepId}.json`.** ClassLoader-friendly, no third-party deps (uses `JsonEventLog.jsonString()` escape rules), decoupled from Kotlin source. Protobuf migration (M4/E5-01) is a drop-in replacement at the same path prefix.

4. **Corpus uses M2-R1 DSL shape (not `DSL_SPEC.md §5-§8`).** Per R5 deferral, all 6 fixtures avoid `retry`/`timeout`/`parallel`/`credentials`. The `loop` fixture uses `script {}` with Kotlin stdlib `for`/`while` (F-ARCH-003 containment).

### Carry-Forward Disposition (M2-R3)

| Carry-forward | Disposition |
|---|---|
| **R2** (stale apply-progress) | **CLOSED.** `apply-progress.yaml` regenerated from real JUnit XML at canonical cycle-artifacts path. |
| **R3** (KSP enum/array limitation) | **PARTIAL CLOSE.** Hardcoded map applied; R3 stays "KSP upgrade (M3+)" carry-forward. Documented in this section. |
| **R4** (context capability API) | **NOT IN SCOPE.** Stays M3 BACKLOG per ADR-0003. |
| **R5** (DSL shape divergence) | **NOT IN SCOPE.** Corpus fixtures use M2-R1 DSL shape (documented deviation); SUG-001 stays M3 candidate. |

### Scope: 3 Deliverables

| ID | Deliverable | Implementation |
|---|---|---|
| E3-10 | `@JenkinsSurface` annotation + `CompatibilityLevel` enum + KSP emission | `CompatibilityLevel.kt`, `JenkinsSurface.kt`, `KnownJenkinsSurfaces.kt`, extended `StepDescriptorGenerator`, annotated `StepExecutors.kt` |
| E3-08 | Per-step JSON resource + `LspMetadataLoader` | `LspMetadata.kt`, `LspMetadataLoader.kt`, KSP JSON emission in `finish()` |
| E2-06 | 6 corpus fixtures + corpus test harness | 6 fixtures under `v2/compatibility/`, `CompatibilityCorpusTest`, `CorpusNormalizer`, `CorpusSnapshotDiffer`, `baseline.json` |

## References

- Design: `cycle-artifacts/p-733fb505b5a6bd2d/m2-r1-dsl-grammar/design.md`
- Specification: `cycle-artifacts/p-733fb505b5a6bd2d/m2-r1-dsl-grammar/specification.md`
- F-ARCH-003: `kotlin.script.experimental` containment
- F-ARCH-004: No `:pipeline-steps-system:compiler-plugin` reach
