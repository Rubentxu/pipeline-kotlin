# LB-02 / G3-A4.3 — Typed `ShellOutput` Carrier Integration

## Goal

Prove end-to-end that the `core.sh` handler, when routed through the registry,
returns a typed `CoreShellOutput(result, outcome)` carrier. The boundary
projects `outcome` into `CommonExecutionResult.outcome` via a generic
`TypedStepOutput` marker — without branching on `core.sh` itself — and the
output codec encodes the ADT plus the canonical outcome projection losslessly
into the durable wire form.

This closes the A3 gap where `RegistryExecutionBoundary.coexecute` hardcoded
`outcome = StepOutcome.Success`. A `core.sh` invocation that returns a
`Failed(...)` shell result MUST now surface as `outcome = StepOutcome.Failure`,
not silently become `Success`.

## What landed in this slice

### 1. Public top-level classifier (single authority)

`application/durable/ShellStepOutcomeClassifier.kt`:

```kotlin
fun ShellInvocationResult.toStepOutcome(): StepOutcome = when (this) {
    ShellInvocationResult.UnitValue,
    is ShellInvocationResult.Stdout,
    is ShellInvocationResult.Status,
    -> StepOutcome.Success

    is ShellInvocationResult.Failed -> StepOutcome.Failure(failure)
    is ShellInvocationResult.Interrupted -> StepOutcome.Failure(
        PipelineFailure(FailureKind.TIMEOUT, interruption.message),
    )
}
```

This is the SAME table that `ShExecution.runShellCommandTyped` was using
internally. The legacy path now delegates to this public function; future
routing has ONE authority and the legacy + registry paths share the same
mapping.

The `Interrupted → Failure(TIMEOUT)` mapping is the legacy convention. The
typed `InterruptionRecord` (including the original `InterruptionKind`) is
preserved on the typed `CoreShellOutput.result.interruption` for any consumer
that needs to recover it.

### 2. `TypedStepOutput` marker interface

`domain/durable/TypedStepOutput.kt`:

```kotlin
interface TypedStepOutput {
    val outcome: StepOutcome
}
```

The pre-decode durable substrate `OperationOutput` stays untouched: it represents
ONLY what the journal needs to persist (`result`, `durationMs`, `finishedAt`).
Whether the Step ran successfully or failed is an orthogonal concern that the
typed handler computes from its domain-specific ADT.

`CommonExecutionBoundary` uses `produced as? TypedStepOutput` to project
`outcome` without needing to know the concrete Step type. Coordinator stays
Step-agnostic.

### 3. `CoreShellOutput` carrier

`application/CoreShellOutput.kt`:

```kotlin
data class CoreShellOutput(
    val result: ShellInvocationResult,
    override val outcome: StepOutcome,
) : TypedStepOutput
```

A4.3 carrier drops the A4.2-era `capturedStdout` / `durationMs` fields:

- `capturedStdout` is derivable from `result.value` (the `Stdout` case).
- `durationMs` is the substrate's concern (`OperationOutput.durationMs`).

### 4. `core.sh` handler update

`application/CoreShellStep.kt` handler:

```kotlin
private val capabilityRoutedHandler: StepHandler<CoreShellInput, CoreShellOutput> =
    StepHandler { input, ctx ->
        val ops: ShellOperations = ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)
        val result: ShellInvocationResult = ops.invoke(
            command = input.command,
            runId = ctx.runId,
            stepIndex = ctx.stepIndex,
        )
        CoreShellOutput(
            result = result,
            outcome = result.toStepOutcome(),  // single classifier authority
        )
    }
```

The handler does NOT reach `EventSink` / `EchoOutputCaptured` / `runBlocking`
/ `GlobalScope`. `ShExecution.invokeShell` remains the single observability
authority.

### 5. Output codec (deterministic + lossless + outcome projection)

```kotlin
private val outputCodec = object : StepCodec<CoreShellOutput> {
    override fun encode(value: CoreShellOutput): EncodedStepValue {
        val obj: JsonObject = buildJsonObject {
            put("kind", JsonPrimitive(kindDiscriminant(value.result)))
            put("outcome", JsonPrimitive(outcomeDiscriminant(value.outcome)))
            when (val r = value.result) {
                ShellInvocationResult.UnitValue -> Unit
                is ShellInvocationResult.Stdout -> put("value", JsonPrimitive(r.value))
                is ShellInvocationResult.Status -> put("exitCode", JsonPrimitive(r.exitCode))
                is ShellInvocationResult.Failed -> {
                    put("failureKind", JsonPrimitive(r.failure.kind.name))
                    put("message", JsonPrimitive(r.failure.message))
                    r.exitCode?.let { put("exitCode", JsonPrimitive(it)) }
                }
                is ShellInvocationResult.Interrupted -> {
                    put("interruptionKind", JsonPrimitive(r.interruption.kind.name))
                    put("message", JsonPrimitive(r.interruption.message))
                    put("operationId", JsonPrimitive(r.interruption.operationId))
                }
            }
        }
        return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
    }
    // decode lands at G7 (replay path).
    ...
}
```

Two discriminants (`kind` for the ADT shape, `outcome` for the canonical
projection). Any downstream re-classification that disagrees with the encoded
`outcome` is a classifier bug and MUST fail loudly.

### 6. `RegistryExecutionBoundary` projection

```kotlin
// A4.3: project the canonical `StepOutcome` from the typed output
// when the carrier implements `TypedStepOutput`. The boundary stays
// Step-agnostic — it NEVER branches on `core.sh` or any other concrete
// StepKey; the typed carrier is the only authority for the outcome
// projection.
val outcome: StepOutcome =
    (produced as? TypedStepOutput)?.outcome ?: StepOutcome.Success
CommonExecutionResult(
    outcome = outcome,
    encodedOutput = encoded,
)
```

Steps whose handler returns `Unit` or a non-typed payload default to `Success`
(legacy convention preserved by `core.echo`'s `EventSink`-shaped output). The
boundary is Step-agnostic — the only authority for the outcome projection is
the typed carrier.

## Tests

`A4_3TypedShellOutputIntegrationTest` — 20 tests:

- 5 classifier mapping tests (Unit/Stdout/Status/Failed/Interrupted).
- 3 handler tests (Stdout -> Success, Failed -> Failure(SCRIPT), Interrupted -> Failure(TIMEOUT)).
- 7 codec tests (each variant encodes losslessly + deterministically + no A4.2 field regression).
- 4 boundary tests (typed SUCCESS, typed FAILURE, non-typed defaults to SUCCESS, handler throws -> ENGINE).
- 1 handler discipline test (no `EventSink` / `EchoOutputCaptured` / `runBlocking` / `GlobalScope`).

## Invariants preserved

- Single observability authority: `ShExecution.invokeShell` emits
  `EchoOutputCaptured`; handler never emits.
- Single outcome classifier authority: `ShellStepOutcomeClassifier.toStepOutcome()`.
- Coordinator contains zero `core.sh` knowledge (boundary projects via the typed
  carrier, not via `StepKey`).
- Handler is `suspend`, no `runBlocking` / `GlobalScope` / detached bridges.
- Suspend law preserved: `StepHandler.execute` + `StepInvoker.invoke` are
  `suspend` from A4.2; handler invokes `ShellOperations.invoke` directly.
- `core.sh` is NOT yet `REGISTRY_PRIMARY`; this slice proves the registry seam
  works in isolation against the boundary and the classifier. Legacy path
  remains in code; the flip is gated on a later slice once legacy + registry
  parity is frozen.

## Pre-existing failures (unchanged)

36 UAT-subprocess tests in `compat` / pipeline-application remain unchanged.
This slice did NOT introduce new failures.

## Why a marker interface, not a `produced is CoreShellOutput` branch

A `produced is CoreShellOutput` check in the boundary would couple the
coordinator to `core.sh`. The boundary stays Step-agnostic by using
`(produced as? TypedStepOutput)?.outcome`. The marker interface makes the
projection rule generic and reusable for any future Step that wants to carry
a canonical outcome on its typed output (e.g. `core.git`, `core.docker`).

## Evidence (fresh XML canaries, slice landed 6f576a26)

```
A4_3TypedShellOutputIntegrationTest          20 tests / 0 failures / 0 errors  SHA 15cd9256944f922b
A4_2ShellOperationsCapabilityTest            14 tests / 0 failures / 0 errors  SHA e2cd0de270867ba7
A4_1DescriptorRecoveryCharacterizationTest    7 tests / 0 failures / 0 errors  SHA 6c32bdce54538bf6
CoreShellStepTest                            11 tests / 0 failures / 0 errors  SHA f6b016563af45921
RegistryExecutionBoundaryTest                 6 tests / 0 failures / 0 errors  SHA de5d251ee8cf7912
GenericRegistryExecutionCarrierTest           6 tests / 0 failures / 0 errors  SHA e407a2f2c2a77b4e
ExecutionBoundaryFactoryTest                  4 tests / 0 failures / 0 errors  SHA 9c82acb8304317fc
FamilyRouterTest                              4 tests / 0 failures / 0 errors  SHA 51bf510ffeddc504
StepDescriptorBodyMetadataTest (domain)       5 tests / 0 failures / 0 errors
TOTAL: 77 tests / 0 failures / 0 errors
```

## A3 gap closed: hardcoded `outcome = Success`

`RegistryExecutionBoundary.coexecute` previously hardcoded
`outcome = StepOutcome.Success` regardless of the handler's actual outcome.
A `core.sh` invocation returning `ShellInvocationResult.Failed(...)` would
silently surface as `Success` in the journal, breaking replay parity and
losing the typed `PipelineFailure` carried on the ADT.

A4.3 replaces the hardcode with:

```kotlin
val outcome: StepOutcome =
    (produced as? TypedStepOutput)?.outcome ?: StepOutcome.Success
```

The handler's typed `CoreShellOutput.outcome` is the new authority (computed
by `ShellStepOutcomeClassifier.toStepOutcome()`); the boundary projects it
without knowing which Step it is. Steps that don't implement `TypedStepOutput`
keep the legacy default of `Success`, preserving `core.echo`'s shape.

## Test inventory (A4_3TypedShellOutputIntegrationTest, 20 tests)

- **Classifier authority (5)**: Unit / Stdout / Status / Failed / Interrupted
  each map to the same outcome as the legacy `ShExecution` table.
- **Handler outcome projection (3)**: each variant of `ShellInvocationResult`
  produces a `CoreShellOutput` with the matching `outcome` field.
- **Output codec lossless + deterministic (7)**: each variant encodes its
  discriminant + payload + outcome losslessly; encode is deterministic;
  A4.2 fields (`capturedStdout`, `durationMs`) are NOT re-introduced.
- **Boundary projection rule (3)**: typed SUCCESS/Failure projects;
  non-typed defaults to SUCCESS; thrown handler produces
  `Failure(ENGINE)` with `encodedOutput = null`.
- **Handler discipline (1)**: source-level check that `CoreShellStep` does
  not reference `EventSink`, `EchoOutputCaptured`, `runBlocking`, or
  `GlobalScope` (comments stripped before assertion).

## Open path

- G7 lands the codec `decode` for `core.sh` (replay path) and the A4.3
  carrier round-trips through the journal.
- A4.8 flips `core.sh = REGISTRY_PRIMARY` once legacy + registry parity is
  frozen and the full UAT corpus passes against the registry path.

