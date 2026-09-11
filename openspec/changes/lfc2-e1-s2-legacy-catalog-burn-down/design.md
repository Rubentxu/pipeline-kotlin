# S2-A1 — core.error — Technical Design

## Step identity & key

- `PluginStepId("core.error")` — same identifier as legacy; no user-visible breaking change.

## StepContract<ErrorInput, Nothing>

```kotlin
data class ErrorInput(
    val message: String,
    val failureKind: FailureKind,
)

// Codec: encodes the WHOLE input payload (dsl-v1 envelope).
// Must be byte-identical to the legacy decoder output to preserve
// durable fingerprint/journal identity.
val inputCodec = object : StepCodec<ErrorInput> {
    override fun encode(value: ErrorInput): EncodedStepValue =
        EncodedStepValue(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("error"))
            put("message", JsonPrimitive(value.message))
            put("failureKind", JsonPrimitive(value.failureKind.name))
        }))
    override fun decode(encoded: EncodedStepValue): ErrorInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        require(obj["kind"]?.jsonPrimitive?.content == "error")
        val msg = obj.getValue("message").jsonPrimitive.content
        val name = obj.getValue("failureKind").jsonPrimitive.content
        val kind = FailureKind.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown failure kind '$name'")
        return ErrorInput(msg, kind)
    }
}

// Output codec: typed outcome is the absence of a value (Nothing).
// Adapter value `Unit` is encoded as an empty dsl-v1 envelope, matching how
// the legacy path returns no payload for a Failure outcome.
val outputCodec = object : StepCodec<Unit> {
    override fun encode(value: Unit): EncodedStepValue =
        EncodedStepValue(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("error"))
        }))
    override fun decode(encoded: EncodedStepValue): Unit = Unit
}

val descriptor = StepDescriptor(
    stepId = "core.error",
    name = "error",
    configRef = "",
    executionLocation = ExecutionLocation.CONTROLLER,
    effects = listOf(Effect.ABORTS_PIPELINE),
    replayPolicy = ReplayPolicy.NEVER,
)

val definition: StepDefinition<ErrorInput, Unit> = object : StepDefinition<ErrorInput, Unit> {
    override val contract = StepContract(
        key = KEY,
        descriptor = descriptor,
        inputCodec = inputCodec,
        outputCodec = outputCodec,
        requiredCapabilities = emptySet(),  // No capability — handler returns typed failure.
    )
    override val handler = StepHandler { input, _ ->
        // The typed failure is the outcome; the boundary folds it into
        // StepOutcome.Failure(PipelineFailure(input.failureKind, input.message))
        // and into StepFailed events. No event sink reach, no global context.
        throw StepHandlerTypedFailure(
            StepOutcome.Failure(PipelineFailure(input.failureKind, input.message)),
        )
    }
}
```

### Why `throw StepHandlerTypedFailure`

The handler's signature returns `O` (typed output value). When the typed outcome is a
**failure**, there is no value to return. Two clean options:

(a) Handler returns `Nothing` via `kotlin.Nothing` — but that propagates an `IllegalStateException`
    and forces a generic exception → typed outcome bridge.
(b) Introduce a typed `StepHandlerTypedFailure` exception that the boundary catches and folds
    into `StepOutcome.Failure(PipelineFailure(...))` deterministically.

Option (b) preserves the typed-failure taxonomy contract (`failureKind`, `message`) end-to-end
and matches the `core.sh` pattern of "typed outcome from a side-effecting handler". It is the
canonical pattern in the registry.

`StepHandlerTypedFailure` is a new exception type, scoped to the SDK runtime:

```kotlin
class StepHandlerTypedFailure(
    val outcome: StepOutcome,
) : RuntimeException("step handler produced a typed outcome (not a value): $outcome")
```

The boundary catches it in the SAME place it would otherwise fold a handler result; no
generic-exception-as-control-flow. This is the same shape as `CoreShellStep`'s use of
`ShellInvocationResult` — the typed outcome leaves through `CommonExecutionBoundary`, never
through Kotlin's exception machinery.

### Capabilities

`requiredCapabilities = emptySet()`. Rationale: the handler does not reach a coordinator,
journal, event sink, or process executor. It returns a typed outcome. The boundary is the
single authority for `StepStarted`/`StepFailed`/`StepFinished`/`RunFinished` event projection.

This mirrors how `CoreEchoStep`'s effect-event emission is the responsibility of the boundary
(or the supplied `EventSink` capability), not the handler reaching out directly.

## Replay policy

`ReplayPolicy.NEVER` — preserved, not copied.

- Fresh run (no durable record): handler runs once; emits `StepFailed(USER, message)`;
  pipeline aborts.
- Replay (durable record exists): boundary aborts before handler invocation; emits
  `Replay aborted` diagnostic — exactly what `UatStep003ErrorAbortTest` forbids for
  fresh runs.

This policy is documented in the `StepDescriptor` and resolved generically by
`EffectReplayPolicy.decide` (no per-Step branch in the coordinator).

## Architecture / wiring

```text
DSL error("boom", failureKind = "USER")
  → StepSpec.Error(message, failureKind)
  → DslCompiledPipelineCompiler: lowers to a StepNode whose pluginStepId = "core.error"
  → CommonExecutionBoundary (registry path)
  → StepRegistry.resolve("core.error") → CoreErrorStep.definition
  → EffectReplayPolicy.decide: fresh → execute; replay → abort
  → StepCodec<ErrorInput>.decode → ErrorInput
  → StepHandler (typed failure)
  → CommonExecutionBoundary folds typed outcome:
      StepStarted → (handler) → StepFailed + StepFinished
  → RunOutcome.Failure(failure)
  → PipelineOutcome.Failure(...)
  → CLI exit code 1
```

No privileged core path. No `when(stepName)` branch. No `canonical` decoder reads.

## Test matrix

### ErrorStepContractSuiteTest

| Row | Test | Notes |
|---|---|---|
| 1 | identity | KEY == PluginStepId("core.error") |
| 2 | contract completeness | descriptor+codecs+capabilities all set |
| 3 | codec input | encode/decode round-trip preserves message+failureKind |
| 4 | codec output | encode/decode round-trip Unit |
| 5 | canonical envelope | encoded payload == dsl-v1 `{kind:"error",message,failureKind}` (byte-identical to legacy) |
| 6 | registry resolution | registry.resolve("core.error") returns CoreErrorStep.definition |
| 7 | capability admission | empty requiredCapabilities → trivially admitted |
| 8 | typed failure outcome | handler produces `StepOutcome.Failure(PipelineFailure(USER, "boom"))` |
| 9 | typed failure preservation | failureKind + message carried untouched |
| 10 | fresh durable | first execution runs handler once; `StepFailed` emitted exactly once |
| 11 | replay | second execution with same fingerprint → no handler invocation, ReplayDecision.ABORT |
| 12 | divergence | typed outcome reaches boundary unchanged |
| 13 | observability | StepStarted/StepFailed/StepFinished/RunFinished in expected order |
| 14 | architecture fitness | canonical path used; no legacy reachable |
| 15 | real DSL | `15-error.pipeline.kts` → exit 1, 1×StepFailed(USER,"test error message") |

### S3ErrorLegacyRemovedFitnessTest (G4)

Structural assertions against code (not text):

1. `CoreStepRegistryFactory.registry()` contains a definition for `"core.error"`.
2. `"core.error" !in CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS`.
3. `CanonicalCoreStepMetadata.table` does not have a `"core.error"` key.
4. No file named `CanonicalErrorNodeDispatcher.kt` exists in the main source tree.
5. `StructuralFamilyResolver.classify("core.error", registry) == Registry`.

### Refixtured tests (preserve observable contract)

- `CanonicalErrorNodeDispatcherTest` → replaced by `ErrorHandlerContractTest`
  (or refixtured to drive the registry path).
- `UatStep003ErrorAbortTest` → unchanged in assertions; the binary path it exercises
  is now the registry path (CLI + `.pipeline.kts`).
- `ErrorHandlingTest` → unchanged assertions; flow path is registry.
- `CliCompileErrorExitsOneTest` → unchanged; covers compile-time error, separate from runtime.
- `UatLocal012ErrorHandlingTest` → unchanged.
- `UatComp002ErrorSourceMappedTest` → unchanged.

## Counter delta

```text
LEGACY_PLUGIN_IDS  : 12 → 11
metadata rows       : 12 → 11
dispatcher classes  : 12 → 11
```

All three sets remain aligned at 11.

## Forbidden

- No conversion of failure to generic `RuntimeException`/`IllegalStateException` as the
  Step's authority. The typed failure taxonomy is the contract.
- No new `when(stepKey)` branch in any coordinator/dispatcher/classifier.
- No privileged core execution path.
- No modification of `core.echo`/`core.sh`/EVT-4.
- No fallback legacy executable path kept "for safety".
