# LB-02 Contract Draft: `core.sh` as Registry-Routed Step

**Status**: contract draft, 2026-09-09 at HEAD `d7d9606f`. Bound by Path A
(`docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md`) until an ADR overrules.
**Purpose**: pin the contract surface of `core.sh` so every subsequent slice
(G1..G8) has a single source of truth and the G7 `StepContractSuite` can be
authored against this shape before any code is written.

## 1. Authority and dependencies

- **AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE**, the 9-step golden path.
- **`docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md`**, which fixes the seam shape.
- **ADR-0070..0074** are the upstream architectural authority; this draft is a
  working contract, not an ADR. If G1 surfaces a divergence, escalate.

## 2. Typed Input / Output

```kotlin
data class CoreShellInput(
    val command: ShellCommand,    // canonical typed shell input (already closed)
    val shOptions: ShOptions,     // per-call shell options (workspaceRoot, captureStdout, timeoutMs, env, sandbox)
    val opId: OpId,               // durable operation id for journal cross-reference
    val stageIndex: Int,          // for event sequencing and WorkspaceResolver
    val stepIndex: Int,           // for event sequencing and EchoOutputCaptured
)
```

Notes:

- `ShellCommand` is the existing closed ADT (`{script, encoding?, label?, returnMode}`).
  Per AGENTS.md §3 ("the engine MUST NOT require codecs to round-trip only
  specific fields"), the Input codec encodes the **whole** input — not a
  field-probe.
- `ShOptions` and `OpId` are runtime-shaped, not transport-shaped. They are
  **declared as required capabilities** in the contract (§4), NOT encoded into
  the durable payload. The `inputCodec` therefore encodes a closed
  `dsl-v1`-shaped envelope (see §5).
- `CoreShellInput.command.script` is the only durable-side string. Env
  values that look like secrets must not leak there (same rule as OperationInput).

```kotlin
data class CoreShellOutput(
    val result: ShellInvocationResult,  // {UnitValue | Stdout | Status | Failed | Interrupted}
    val capturedStdout: String,         // mirrors stdout returned by invokeShell (for EchoOutputCaptured emission)
    val durationMs: Long,
)
```

The output is captured by the registry boundary (Path A): the handler returns
`O`; `RegistryExecutionBoundary` passes it through `outputCodec.encode(O)`; the
journal records `OperationOutput(result = <encoded>)`; replay re-reads it
through `outputCodec.decode(...)`.

The combined codec shape mirrors the existing scripted path
(`JournaledScriptedOperationRuntime.shell.toWire()` → `JsonObject.toShellResult()`)
so the wire format is identical whether the Step was executed via the legacy
canonical command or the new registry handler. This makes G2 corpus migration
mechanically equivalent.

## 3. Capabilities

Per AGENTS.md §MUST NOT, the handler accepts only declared capabilities; no
`CanonicalRuntimeContext`, no coordinator, no service locator.

```text
SHELL_PROCESS       : Process launch + wait + cancel via existing DurableShellExecutor.
EVENT_SINK          : The handler emits via ctx.capabilities.get(EVENT_SINK_CAPABILITY)
                      the same EchoOutputCaptured event Echo emits today (observability
                      parity between echo and sh).
SHELL_OPTIONS       : The handler reaches ShOptions and OpId under controlled, typed
                      access — see the new SHELL_OPERATIONS capability below.
```

The existing capability surface already has `EVENT_SINK_CAPABILITY`
(`StepCapability("eventSink")` in `pipeline-application/Capabilities.kt`). For
the **process execution** capability we introduce one new capability, mirroring
the EventSink pattern:

```kotlin
val SHELL_PROCESS_CAPABILITY: StepCapability = StepCapability("shellProcess")
val SHELL_OPERATIONS_CAPABILITY: StepCapability = StepCapability("shellOperations")
```

The handler:

```kotlin
object CoreShellStep {
    val KEY = PluginStepId("core.sh")
    val definition: StepDefinition<CoreShellInput, CoreShellOutput> = object : StepDefinition<...> {
        override val handler = StepHandler { input, ctx ->
            val ops: ShellOperations = ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)
            // CRT-rejected: no CanonicalRuntimeContext handed here.
            ops.invoke(input) // -> CoreShellOutput. Adapts to invokeShell.
        }
    }
}
```

Where `ShellOperations` is a typed seam produced by the canonical runtime:

```kotlin
interface ShellOperations {
    suspend fun invoke(input: CoreShellInput): CoreShellOutput
}
```

The single production `ShellOperations` adapter is built in
`CanonicalRuntimeCapabilityAccess` and proxies to `ShExecution.invokeShell`,
exactly the way `CanonicalShellNodeDispatcher` already does. **The handler
contains no process execution logic** (your LB-02 invariant).

Capability admission already fails closed before the handler runs
(`RegistryExecutionPreparation.prepare`), and is re-checked at the boundary
(`RegistryExecutionBoundary.execute`). SHELL_PROCESS is the typed witness that
the runtime has a real durable shell substrate; production coordination
installs the real adapter; tests inject in-memory fakes.

## 4. `StepContract`

```kotlin
val definition = StepContract<CoreShellInput, CoreShellOutput>(
    key = CoreShellStep.KEY,
    descriptor = StepDescriptor(
        stepId = "core.sh",
        name = "sh",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,             // process-bearing Step
        effects = listOf(Effect.SideEffect, Effect.Destructive),  // matches current CanonicalCoreStepMetadata
        replayPolicy = ReplayPolicy.RERUN,                      // current metadata records this as OnCancelKill (see §5)
        recoveryPolicy = RecoveryPolicy.ExternalSubprocess,      // process recovery preserved
    ),
    inputCodec = shellInputCodec,         // see §5
    outputCodec = shellOutputCodec,       // see §5
    requiredCapabilities = setOf(
        SHELL_PROCESS_CAPABILITY,
        SHELL_OPERATIONS_CAPABILITY,
        EVENT_SINK_CAPABILITY,
    ),
)
```

Notes:

- `ReplayPolicy` for sh is the open question Path A defers to G7 (see
  decision doc). The current legacy metadata records `ReplayPolicy.OnCancelKill`
  for sh in `CanonicalCoreStepMetadata`. Honour that on G1 and surface it as a
  G7 property test.
- `RecoveryPolicy.ExternalSubprocess` stays on the contract. The engine reads
  `metadata.recoveryPolicy`, not the Step key — already true today per ADR
  territory.

## 5. Input/Output codec envelopes

```kotlin
object ShellInputCodec : StepCodec<CoreShellInput> {
    override fun encode(value: CoreShellInput): EncodedStepValue = EncodedStepValue(
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", JsonPrimitive("shell"))
            put("script", JsonPrimitive(value.command.script))
            value.command.encoding?.let { put("encoding", JsonPrimitive(it)) }
            value.command.label?.let { put("label", JsonPrimitive(it)) }
            put("returnMode", JsonPrimitive(value.command.returnMode.name))
        }),
    )

    override fun decode(encoded: EncodedStepValue): CoreShellInput {
        val obj = Json.parseToJsonElement(encoded.value).jsonObject
        require(obj["kind"]?.jsonPrimitive?.content == "shell") {
            "shell payload kind must be 'shell'"
        }
        // Capability-resolved inputs (shOptions, opId, stageIndex, stepIndex) are NOT encoded;
        // the boundary looks them up from the runtime context before invoking the handler.
        // We only restore the durable subset. The handler reading missing per-call args
        // surfaces a typed failure (engine invariant).
        return CoreShellInput(
            command = ShellCommand(
                script = obj.getValue("script").jsonPrimitive.content,
                encoding = obj["encoding"]?.jsonPrimitive?.content,
                label = obj["label"]?.jsonPrimitive?.content,
                returnMode = ShellReturnMode.valueOf(
                    obj.getValue("returnMode").jsonPrimitive.content,
                ),
            ),
            shOptions = ShOptions.EMPTY,                // filled by the boundary from runtime
            opId = OpId.parse(""),                       // filled by the boundary
            stageIndex = 0,                              // filled by the boundary
            stepIndex = 0,                               // filled by the boundary
        )
    }
}

object ShellOutputCodec : StepCodec<CoreShellOutput> {
    // The wire shape reuses the existing scripted-runtime JsonObjectBuilder shape
    // (see JournaledScriptedOperationRuntime.toWire(kind, fields)).
    override fun encode(value: CoreShellOutput): EncodedStepValue = EncodedStepValue(
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                // Mirror ScriptedRuntime.ShellInvocationResult.toWire() per variant.
                value.result.toWireInto(this)         // existing helper, lifted here
                put("capturedStdout", JsonPrimitive(value.capturedStdout))
                put("durationMs", JsonPrimitive(value.durationMs))
            },
        ),
    )
    override fun decode(encoded: EncodedStepValue): CoreShellOutput { /* ... */ }
}
```

This keeps the wire format compatible with the legacy canonical command path.
G2 corpus migration is therefore a rewrite of the test from `LegacyCore` to
`Registry` while preserving the same fingerprint.

## 6. Registry registration

```kotlin
// /v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt
fun registry(): InMemoryStepRegistry = InMemoryStepRegistry().apply {
    CoreEchoStep.registerInto(this)
    CoreShellStep.registerInto(this)   // G3
}
```

G3 is exactly this single line. Legacy decode/dispatch for `core.sh` remains
in place through G3 (REGISTRY_PRIMARY), then removed at G4.

## 7. Handler shape (one-liner contract, not implementation)

```kotlin
val handler: StepHandler<CoreShellInput, CoreShellOutput> = StepHandler { input, ctx ->
    val ops: ShellOperations = ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)
    ops.invoke(input)
}
```

The handler contains **zero** process-launch logic, **zero** env injection,
**zero** timeout wiring. All of that is `ShExecution.invokeShell` (existing,
certified) reached via `ShellOperations.invoke`. The LB-02 invariant
("ShStepHandler adapts to existing certified infrastructure") is satisfied at
the type level: the only implementation the handler depends on is the typed
`ShellOperations` capability seam.

## 8. StepContractSuite coverage (`G7`)

The contract suite mirrors `EchoStepContractSuiteTest` row-for-row. The
sh-specific rows add:

- `core.sh in StepRegistry` (registration row).
- `core.sh production factory contains` (registry row).
- fresh launch under sandbox → SUCCEEDED + `EchoOutputCaptured`.
- non-zero exit (e.g. `sh("exit 7")`) → `StepOutcome.Failure(kind=SCRIPT)` +
  recorded `OperationOutput.result` carries the failure payload.
- cancellation (kills the running process) → `OperationStatus.ABORTED` (NOT
  FAILED), distinct from failure; recorded output is `Interrupted`.
- timeout (`shOptions.timeoutMs=…`) → recorded output is
  `Failed(kind=TIMEOUT, exitCode=null)` after the bound elapsed.
- replay: a SUCCEEDED op re-runs NO, the encoded output is read back, the typed
  `CoreShellOutput` is reproduced.
- divergence: changing the script text yields a typed divergence surface.
- missing capability: SHELL_OPERATIONS not present → `Rejected` admission.
- real DSL scenario: `pipeline { stages { stage("build") { sh("echo registry-shell | tee /tmp/out") } } }`
  produces the canonical journal row + the captured stdout in
  `OperationOutput.result`.

## 9. Open questions deferred to G1 / G7

Same as Path A decision doc, restated against `core.sh` shape:

1. Exact `ReplayPolicy` value for sh after the burn-down (current legacy is
   `OnCancelKill`; G7 must justify whether to keep it or change to `RERUN`).
2. Whether `OperationOutput` already covers everything or whether a sibling
   field is needed (e.g. typed output for non-shell Steps).
3. Whether the typed-output channel is a typed property of the journal or
   a typed capability of the boundary. Current draft: typed property of the
   journal (`OperationOutput.result`), decoded by `outputCodec`. Boundary-only
   would force every Step to subscribe to its own journal store, which is
   wasteful and breaks cross-Step stdout. **Decision: journal-typed.**

## 10. References

- `/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt:135`
  — the certified engine `invokeShell` we adapt to.
- `/v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/durable/OperationOutput.kt`
  — already-existing output slot (no schema migration needed).
- `/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/JournaledScriptedOperationRuntime.kt:158`
  — existing `ShellInvocationResult.toWire()` we lift for `outputCodec`.
- `/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalShellNodeDispatcher.kt`
  — the legacy adapter we replace step-by-step.
- `/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Capabilities.kt`
  — capability declaration surface.
- `docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md` — Path A decision (parent).
