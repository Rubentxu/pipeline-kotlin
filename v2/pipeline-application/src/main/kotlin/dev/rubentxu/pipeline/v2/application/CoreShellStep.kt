package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.application.durable.toStepOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `core.sh` registered as an open [StepDefinition] (LB-02 / G1 registry seam proof).
 *
 * Follows the same generic mechanism an external plugin uses. The contract
 * shape mirrors [CoreEchoStep]: typed input codec, typed output codec,
 * required capabilities, replay policy on the contract, and the handler
 * returns a typed [CoreShellOutput].
 *
 * ## G1 — registry seam proof
 *
 * The codec round-trip, key uniqueness, and required-capabilities contract
 * hold without depending on `ShExecution.invokeShell` or the legacy canonical-core
 * decode/dispatch path. The codec encodes well-formed JSON objects, fulfilling
 * durable-spine eligibility CDE.3-e1/e2.
 *
 * ## G3 — REGISTRY_PRIMARY (this slice, A4.2)
 *
 * Real sh execution is reached through the declared
 * `SHELL_OPERATIONS_CAPABILITY`. The handler:
 *
 *  1. Asks the [StepHandlerContext.capabilities] for `ShellOperations`.
 *     This is the ONLY runtime side a handler holds for shell execution.
 *  2. Invokes the typed seam once per call and returns a typed
 *     [CoreShellOutput]. It MUST NOT re-implement process launching.
 *
 * The [ShellOperations] implementation is wired by the runtime bridge
 * (canonical seam binds it to `ShExecution.invokeShell` via
 * [ShOperationsAdapter]). Capability admission is fail-closed at prepare-time:
 * `RegistryExecutionPreparation.prepare()` rejects with the missing capability
 * before the handler ever runs.
 *
 * ## A4.2 invariants preserved
 *
 * - Closed execution structure, open Step registry: the canonical
 *   coordinator and dispatcher are unchanged in this slice; legacy decode /
 *   dispatch / metadata row for `core.sh` are intact.
 * - No `Any` as a durable contract: typed I/O crosses as [EncodedStepValue].
 * - Required capability == used capability: handler asks only for
 *   `SHELL_OPERATIONS_CAPABILITY` and never reaches another capability.
 * - Process-engine authority lives in [ShExecution] (single emitter of
 *   `EchoOutputCaptured`). The adapter delegates; the handler MUST NOT
 *   emit.
 * - Recovery stays at the descriptor (A4.1.3 declares `recoveryPolicy`); this
 *   slice does NOT touch the recovery substrate.
 *
 * @see docs/v2/00-context/LB02_CORE_SH_CONTRACT_DRAFT.md
 * @see docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md
 * @see docs/v2/07-uat/LB02_G3_A4_1_DESCRIPTOR_RECOVERY.md
 * @see docs/v2/07-uat/LB02_G3_A4_2_SHELL_OPERATIONS_CAPABILITY.md
 */
object CoreShellStep {

    val KEY: PluginStepId = PluginStepId("core.sh")

    /**
     * G1 input codec: encodes the durable subset (`{kind, script, encoding?,
     * label?, returnMode}`) as a well-formed JSON object. Per-call runtime
     * args are filled from capability-resolved seams at execute-time; they
     * MUST NOT be encoded into the durable payload.
     */
    private val inputCodec = object : StepCodec<CoreShellInput> {
        override fun encode(value: CoreShellInput): EncodedStepValue {
            val obj: JsonObject = buildJsonObject {
                put("kind", JsonPrimitive("shell"))
                put("script", JsonPrimitive(value.command.script))
                value.command.encoding?.let { put("encoding", JsonPrimitive(it)) }
                value.command.label?.let { put("label", JsonPrimitive(it)) }
                put("returnMode", JsonPrimitive(value.command.returnMode.name))
            }
            return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
        }

        override fun decode(encoded: EncodedStepValue): CoreShellInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "shell") {
                "shell payload kind must be 'shell'"
            }
            return CoreShellInput(
                command = ShellCommand(
                    script = obj.getValue("script").jsonPrimitive.content,
                    encoding = obj["encoding"]?.jsonPrimitive?.content,
                    label = obj["label"]?.jsonPrimitive?.content,
                    returnMode = ShellReturnMode.valueOf(
                        obj.getValue("returnMode").jsonPrimitive.content,
                    ),
                ),
            )
        }
    }

    /**
     * G3-A4.3 output codec: encodes the closed [ShellInvocationResult] ADT plus its
     * canonical [dev.rubentxu.pipeline.v2.domain.StepOutcome] projection.
     *
     * The encoded JSON shape (deterministic, lossless for contractual fields):
     *  - `kind`: discriminant matching the existing scripted-runtime names
     *    (`UNIT` / `STDOUT` / `STATUS` / `FAILED` / `INTERRUPTED`).
     *  - `outcome`: `"SUCCESS"` or `"FAILURE"` — the canonical projection. Any
     *    downstream re-classification that disagrees with the value here is a
     *    classifier bug and MUST fail loudly (no silent reconciliation).
     *  - payload fields per discriminant (`value`, `exitCode`, `failureKind`,
     *    `message`, `interruptionKind`, `operationId`).
     *
     * The `capturedStdout` / `durationMs` fields from the A4.2 carrier are GONE: the
     * former is derivable from `result.value` when `kind == "STDOUT"`, and the latter
     * is the substrate's concern (`OperationOutput.durationMs`).
     *
     * `decode` remains a TODO until G7 lands (replay path). Until then, malformed
     * payloads MUST surface as `TODO("G7...")` failures on the replay path, NOT be
     * silently coerced.
     */
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

        override fun decode(encoded: EncodedStepValue): CoreShellOutput {
            // G3-A4.3: deferred to G7. Until then the boundary does NOT decode.
            // Replay path is the only consumer; it MUST land before
            // `core.sh = REGISTRY_PRIMARY` is flipped.
            TODO("G7: typed output decode for core.sh lands at the replay slice.")
        }
    }

    private fun kindDiscriminant(r: ShellInvocationResult): String = when (r) {
        ShellInvocationResult.UnitValue -> "UNIT"
        is ShellInvocationResult.Stdout -> "STDOUT"
        is ShellInvocationResult.Status -> "STATUS"
        is ShellInvocationResult.Failed -> "FAILED"
        is ShellInvocationResult.Interrupted -> "INTERRUPTED"
    }

    private fun outcomeDiscriminant(o: StepOutcome): String = when (o) {
        is StepOutcome.Success -> "SUCCESS"
        is StepOutcome.Unstable -> "UNSTABLE"
        is StepOutcome.Failure -> "FAILURE"
    }

    private val descriptor = StepDescriptor(
        stepId = "core.sh",
        name = "sh",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.EXECUTES_SUBPROCESS),
        replayPolicy = ReplayPolicy.RERUN,
        // LB-02 / G3-A4.1.3: recovery is a declared Step property. Mirrors the
        // CanonicalCoreStepMetadata["core.sh"] row so the registry-resolved
        // metadata is byte-equivalent to the legacy one before A4.8 flips routing.
        recoveryPolicy = RecoveryPolicy.ExternalSubprocess,
    )

    /**
     * G3-A4.2 capability-routed handler: delegates to the typed
     * [ShellOperations] seam reached through [SHELL_OPERATIONS_CAPABILITY].
     *
     * The handler:
     * - asks the runtime capability access for the seam (fail-closed admission
     *   is the engine's responsibility — `RegistryExecutionPreparation` rejects
     *   before this point if the runtime cannot supply the capability),
     * - invokes the seam exactly once with the typed `command` and execution
     *   identity (`runId`, `stepIndex`),
     * - projects the typed `ShellInvocationResult` into a [CoreShellOutput] that
     *   ALSO carries the canonical [dev.rubentxu.pipeline.v2.domain.StepOutcome]
     *   computed by the single authority
     *   [dev.rubentxu.pipeline.v2.application.durable.toStepOutcome] (LB-02 / G3-A4.3).
     *   No string parsing; no event emission; the [ShExecution] substrate is
     *   the single authority for `EchoOutputCaptured`.
     *
     * It MUST NOT:
     * - reach `CanonicalRuntimeContext`,
     * - reach the journal,
     * - call `eventSink` directly,
     * - reach the durable-shell substrate (that is the adapter's authority),
     * - read or write filesystem state.
     * - re-implement the outcome classifier (it MUST call
     *   `toStepOutcome()` so legacy and registry paths share one authority).
     */
    private val capabilityRoutedHandler: StepHandler<CoreShellInput, CoreShellOutput> =
        StepHandler { input, ctx ->
            val ops: ShellOperations = ctx.capabilities.get(SHELL_OPERATIONS_CAPABILITY)
            val result: ShellInvocationResult = ops.invoke(
                command = input.command,
                runId = ctx.runId,
                stepIndex = ctx.stepIndex,
            )
            // A4.3 — outcome is the SINGLE classifier output (NOT re-derived downstream).
            // `CommonExecutionBoundary` reads `outcome` from the typed carrier via
            // `produced as? TypedStepOutput`; the boundary stays Step-agnostic.
            CoreShellOutput(
                result = result,
                outcome = result.toStepOutcome(),
            )
        }

    val definition: StepDefinition<CoreShellInput, CoreShellOutput> = object : StepDefinition<CoreShellInput, CoreShellOutput> {
        override val contract: StepContract<CoreShellInput, CoreShellOutput> = StepContract(
            key = KEY,
            descriptor = descriptor,
            inputCodec = inputCodec,
            outputCodec = outputCodec,
            // LB-02 / G3-A4.2: capability declaration. The handler reaches
            // shell execution ONLY through this token; admission is fail-closed
            // at prepare-time when the runtime bridge does not provide it.
            requiredCapabilities = setOf(SHELL_OPERATIONS_CAPABILITY),
        )

        override val handler: StepHandler<CoreShellInput, CoreShellOutput> = capabilityRoutedHandler
    }

    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
