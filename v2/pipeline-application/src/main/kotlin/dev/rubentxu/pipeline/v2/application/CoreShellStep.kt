package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
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
 * ## G1 — registry seam proof (this slice)
 *
 * The handler body is a deterministic stub returning
 * [ShellInvocationResult.UnitValue]. It proves the typed I/O shape, codec
 * round-trip, key uniqueness, and required-capabilities contract all hold
 * without depending on `ShExecution.invokeShell` or the legacy canonical-core
 * decode/dispatch path. The codec encodes well-formed JSON objects, fulfilling
 * durable-spine eligibility CDE.3-e1/e2.
 *
 * ## G3 — REGISTRY_PRIMARY (next slice)
 *
 * Real sh execution lands in [CoreShellStep.handler] at G3 by introducing
 * a new `SHELL_OPERATIONS` capability (mirrors how [CoreEchoStep.handler]
 * reaches `EVENT_SINK` from the canonical runtime context). The handler
 * MUST NOT re-implement process launching; it adapts to a typed
 * `ShellOperations` seam which proxies to `ShExecution.invokeShell`.
 *
 * ## Invariants preserved
 *
 * - Closed execution structure, open Step registry: the canonical
 *   coordinator and dispatcher are unchanged in this slice; legacy decode /
 *   dispatch / metadata row for `core.sh` are intact.
 * - No `Any` as a durable contract: typed I/O crosses as [EncodedStepValue].
 * - Required capability == used capability: the G1 stub declares no
 *   required capabilities because the G1 body does not reach any capability.
 *
 * ## Open contract gap (deferred to G3)
 *
 * `StepContract` does not currently carry a `recoveryPolicy` field; the
 * canonical command path declares it on `StepMetadata` (CDE.2-b4). For sh
 * the recovery policy is `ExternalSubprocess`. G3 lifts this into the
 * registry contract. Until then, only `effects` and `replayPolicy` are
 * available on the descriptor.
 *
 * @see docs/v2/00-context/LB02_CORE_SH_CONTRACT_DRAFT.md
 * @see docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md
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
     * G1 output codec: encodes `{kind, capturedStdout, durationMs, ...}` where
     * `kind` matches the existing scripted-runtime discriminant names
     * (UNIT / STDOUT / STATUS / FAILED / INTERRUPTED). G2 corpus migration
     * reuses this shape; G7 will exercise the decode (replay path).
     */
    private val outputCodec = object : StepCodec<CoreShellOutput> {
        override fun encode(value: CoreShellOutput): EncodedStepValue {
            val obj: JsonObject = buildJsonObject {
                put("kind", JsonPrimitive(kindDiscriminant(value.result)))
                put("capturedStdout", JsonPrimitive(value.capturedStdout))
                put("durationMs", JsonPrimitive(value.durationMs))
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
            // G1: deferred to G7; the typed-output boundary seam lands at G3 first.
            TODO("G1: typed output decode lands at G7; producer lands at G3.")
        }
    }

    private fun kindDiscriminant(r: ShellInvocationResult): String = when (r) {
        ShellInvocationResult.UnitValue -> "UNIT"
        is ShellInvocationResult.Stdout -> "STDOUT"
        is ShellInvocationResult.Status -> "STATUS"
        is ShellInvocationResult.Failed -> "FAILED"
        is ShellInvocationResult.Interrupted -> "INTERRUPTED"
    }

    private val descriptor = StepDescriptor(
        stepId = "core.sh",
        name = "sh",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.EXECUTES_SUBPROCESS),
        replayPolicy = ReplayPolicy.RERUN,
    )

    /**
     * G1 (registry seam proof) handler: deterministic
     * [ShellInvocationResult.UnitValue] with a 0ms duration. NO process launch,
     * NO shell invocation. Real launch lands at G3 with the `SHELL_OPERATIONS`
     * capability adapter.
     */
    private val g1StubHandler = StepHandler { _: CoreShellInput, _: StepHandlerContext ->
        CoreShellOutput(
            result = ShellInvocationResult.UnitValue,
            capturedStdout = "",
            durationMs = 0L,
        )
    }

    val definition: StepDefinition<CoreShellInput, CoreShellOutput> = object : StepDefinition<CoreShellInput, CoreShellOutput> {
        override val contract: StepContract<CoreShellInput, CoreShellOutput> = StepContract(
            key = KEY,
            descriptor = descriptor,
            inputCodec = inputCodec,
            outputCodec = outputCodec,
            requiredCapabilities = emptySet(),
        )

        override val handler: StepHandler<CoreShellInput, CoreShellOutput> = g1StubHandler
    }

    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}
