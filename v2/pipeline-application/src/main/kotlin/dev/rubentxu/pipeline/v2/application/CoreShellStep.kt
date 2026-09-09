package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
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
import kotlinx.serialization.json.booleanOrNull
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
            // A4 flip: accept BOTH canonical kind spellings — the production DSL compiler
            // (DslCompiledPipelineCompiler.shellPayload) emits `"sh"`, while the codec's
            // own self-encoded form uses `"shell"`. Both name the same Step family; rejecting
            // either would break the registry path for real `.pipeline.kts` files. Anything
            // else is rejected fail-closed.
            val kind = obj["kind"]?.jsonPrimitive?.content
            require(kind == "shell" || kind == "sh") {
                "shell payload kind must be 'shell' or 'sh'"
            }
            // A4 flip: the production DSL compiler emits `"command"`, while the codec's
            // own self-encoded form uses `"script"`. Both name the same data; accept either
            // so the codec's self-round-trip AND real `.pipeline.kts` files both decode.
            val script = obj["command"]?.jsonPrimitive?.content
                ?: obj["script"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Shell payload must include 'command' or 'script'")
            // A4 flip: the DSL compiler emits `"returnStdout"` (Jenkins boolean) and
            // `"isScriptBlock"` (canonical flag). The codec's own self-encoded form uses
            // `"returnMode"` (closed enum). Both shapes are canonical for the same Step;
            // we accept either so registry routing works for real `.pipeline.kts` files.
            val returnMode = obj["returnMode"]?.jsonPrimitive?.content?.let { ShellReturnMode.valueOf(it) }
                ?: run {
                    val returnStdout = obj["returnStdout"]?.jsonPrimitive?.booleanOrNull == true
                    val returnStatus = obj["returnStatus"]?.jsonPrimitive?.booleanOrNull == true
                    when {
                        returnStdout && returnStatus ->
                            throw IllegalArgumentException(
                                "Shell payload cannot enable both returnStdout and returnStatus",
                            )
                        returnStdout -> ShellReturnMode.STDOUT
                        returnStatus -> ShellReturnMode.STATUS
                        else -> ShellReturnMode.NONE
                    }
                }
            return CoreShellInput(
                command = ShellCommand(
                    script = script,
                    encoding = obj["encoding"]?.jsonPrimitive?.content,
                    label = obj["label"]?.jsonPrimitive?.content,
                    returnMode = returnMode,
                ),
            )
        }
    }

    /**
     * G7 output codec: deterministic, lossless for contractual fields, malformed-fail.
     *
     * The encoded JSON shape (see [decode] for the symmetric reader):
     *  - `kind` (mandatory): discriminant matching the existing scripted-runtime names
     *    (`UNIT` / `STDOUT` / `STATUS` / `FAILED` / `INTERRUPTED`).
     *  - `outcome` (mandatory): canonical projection of [dev.rubentxu.pipeline.v2.domain.StepOutcome]
     *    — `SUCCESS` / `UNSTABLE` / `FAILURE`. The discriminant is cross-checked against
     *    the variant payload on decode; mismatches surface as typed decode failure
     *    (no silent coercion).
     *  - Per-variant payload fields (all mandatory iff the variant requires them; missing
     *    or wrong-typed fields fail closed on decode):
     *      - `STDOUT` -> `value: String`
     *      - `STATUS` -> `exitCode: Int`
     *      - `FAILED` -> `failureKind: String` (FailureKind.name), `failureMessage: String`,
     *        optional `failureCauseClass: String` (Throwable class name ONLY; the throwable
     *        itself is NEVER transported — `FailureRecord`'s documented design rule),
     *        optional `durableFailure: { code, kind, message, origin, retryable,
     *        operationId, workerId?, taskId?, details?, schemaVersion }`,
     *        optional `exitCode: Int`.
     *      - `INTERRUPTED` -> `interruptionKind: String` (InterruptionKind.name),
     *        `interruptionMessage: String`, `operationId: String`,
     *        optional `causedBy: String`, optional `deadlineEpochMillis: Long`,
     *        optional `details: Map<String,String>`.
     *      - `UNIT` -> no payload fields.
     *
     * `outcome = FAILURE` MUST appear iff the variant payload carries a `failureKind`
     * (or for `INTERRUPTED`, an `interruptionKind` whose classifier maps to TIMEOUT).
     * The decode rule below enforces this.
     *
     * Determinism: encode uses `buildJsonObject` with explicit `put` ordering; the
     * optional fields appear in fixed positions and are omitted when not present.
     * No Map iteration, no timestamps, no identity — same instance produces byte-identical
     * bytes across calls.
     *
     * Failure modes (decode): [StepCodec.decode] throws a subclass of [RuntimeException]
     * with a typed reason; the boundary never silently coerces malformed payloads.
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
                        put("failureMessage", JsonPrimitive(r.failure.message))
                        r.failure.cause?.let { cause ->
                            put("failureCauseClass", JsonPrimitive(cause::class.qualifiedName ?: cause::class.simpleName))
                        }
                        r.durableFailure?.let { fr ->
                            put("durableFailure", buildJsonObject {
                                put("code", JsonPrimitive(fr.code))
                                put("kind", JsonPrimitive(fr.kind.name))
                                put("message", JsonPrimitive(fr.message))
                                put("origin", JsonPrimitive(fr.origin.name))
                                put("retryable", JsonPrimitive(fr.retryable))
                                put("operationId", JsonPrimitive(fr.operationId))
                                fr.workerId?.let { put("workerId", JsonPrimitive(it)) }
                                fr.taskId?.let { put("taskId", JsonPrimitive(it)) }
                                // Encode details in canonical key order: sorted
                                // by key to remove Map-iteration dependence.
                                if (fr.details.isNotEmpty()) {
                                    put("details", buildJsonObject {
                                        fr.details.toSortedMap().forEach { (k, v) ->
                                            put(k, JsonPrimitive(v))
                                        }
                                    })
                                }
                                put("schemaVersion", JsonPrimitive(fr.schemaVersion))
                            })
                        }
                        r.exitCode?.let { put("exitCode", JsonPrimitive(it)) }
                    }
                    is ShellInvocationResult.Interrupted -> {
                        put("interruptionKind", JsonPrimitive(r.interruption.kind.name))
                        put("interruptionMessage", JsonPrimitive(r.interruption.message))
                        put("operationId", JsonPrimitive(r.interruption.operationId))
                        r.interruption.causedBy?.let { put("causedBy", JsonPrimitive(it)) }
                        r.interruption.deadlineEpochMillis?.let { put("deadlineEpochMillis", JsonPrimitive(it)) }
                        if (r.interruption.details.isNotEmpty()) {
                            put("details", buildJsonObject {
                                r.interruption.details.toSortedMap().forEach { (k, v) ->
                                    put(k, JsonPrimitive(v))
                                }
                            })
                        }
                    }
                }
            }
            return EncodedStepValue(Json.encodeToString(JsonObject.serializer(), obj))
        }

        override fun decode(encoded: EncodedStepValue): CoreShellOutput {
            val obj = try {
                Json.parseToJsonElement(encoded.value).jsonObject
            } catch (e: Exception) {
                throw CoreShellCodecException(
                    "core.sh output envelope is not a JSON object: ${e.message ?: "parse failed"}",
                )
            }

            val kindStr = obj["kind"]?.asStringOrNull()
                ?: throw CoreShellCodecException("core.sh output missing mandatory 'kind' field")
            val outcomeStr = obj["outcome"]?.asStringOrNull()
                ?: throw CoreShellCodecException("core.sh output missing mandatory 'outcome' field")

            val result: ShellInvocationResult = when (kindStr) {
                "UNIT" -> ShellInvocationResult.UnitValue
                "STDOUT" -> {
                    val v = obj["value"]?.asStringOrNull()
                        ?: throw CoreShellCodecException("STDOUT variant missing mandatory 'value' field")
                    ShellInvocationResult.Stdout(v)
                }
                "STATUS" -> {
                    val ec = obj["exitCode"]?.asIntOrNull()
                        ?: throw CoreShellCodecException("STATUS variant missing or non-integer 'exitCode' field")
                    ShellInvocationResult.Status(ec)
                }
                "FAILED" -> {
                    val fkStr = obj["failureKind"]?.asStringOrNull()
                        ?: throw CoreShellCodecException("FAILED variant missing mandatory 'failureKind' field")
                    val fmStr = obj["failureMessage"]?.asStringOrNull()
                        ?: throw CoreShellCodecException("FAILED variant missing mandatory 'failureMessage' field")
                    val failureKind = try {
                        FailureKind.valueOf(fkStr)
                    } catch (e: IllegalArgumentException) {
                        throw CoreShellCodecException(
                            "FAILED variant 'failureKind' is not a valid FailureKind: '$fkStr'",
                        )
                    }
                    // We do NOT reconstitute Throwables across the durable wire:
                    // `failureCauseClass` is the documented diagnostic-only field
                    // and `failure.cause` is always null after round-trip. This
                    // matches `FailureRecord`'s design (a Throwable may be retained
                    // by an in-process exception for diagnostics, but it is
                    // deliberately not part of the persisted contract).
                    val durableFailure: FailureRecord? = obj["durableFailure"]?.let { frEl ->
                        val frObj = frEl as? JsonObject
                            ?: throw CoreShellCodecException("'durableFailure' must be a JSON object")
                        try {
                            FailureRecord(
                                code = frObj.stringOrThrow("code"),
                                kind = FailureKind.valueOf(frObj.stringOrThrow("kind")),
                                message = frObj.stringOrThrow("message"),
                                origin = dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin.valueOf(
                                    frObj.stringOrThrow("origin"),
                                ),
                                retryable = frObj.boolOrThrow("retryable"),
                                operationId = frObj.stringOrThrow("operationId"),
                                workerId = frObj["workerId"]?.asStringOrNull(),
                                taskId = frObj["taskId"]?.asStringOrNull(),
                                details = frObj["details"]?.asStringMapOrNull() ?: emptyMap(),
                                schemaVersion = frObj.intOrThrow("schemaVersion"),
                            )
                        } catch (e: CoreShellCodecException) {
                            throw e
                        } catch (e: Exception) {
                            throw CoreShellCodecException(
                                "malformed durableFailure: ${e.message ?: "decode failed"}",
                            )
                        }
                    }
                    val ec = obj["exitCode"]?.asIntOrNull()
                    ShellInvocationResult.Failed(
                        failure = PipelineFailure(kind = failureKind, message = fmStr),
                        durableFailure = durableFailure,
                        exitCode = ec,
                    )
                    // `failure.cause` is null after round-trip by design (see
                    // FailureRecord's documented "no throwable transport" rule).
                    // `failureCauseClass` is preserved as a separate diagnostic
                    // field for tooling that needs the class name.
                }
                "INTERRUPTED" -> {
                    val ikStr = obj["interruptionKind"]?.asStringOrNull()
                        ?: throw CoreShellCodecException("INTERRUPTED variant missing mandatory 'interruptionKind' field")
                    val imStr = obj["interruptionMessage"]?.asStringOrNull()
                        ?: throw CoreShellCodecException("INTERRUPTED variant missing mandatory 'interruptionMessage' field")
                    val opId = obj["operationId"]?.asStringOrNull()
                        ?: throw CoreShellCodecException("INTERRUPTED variant missing mandatory 'operationId' field")
                    val ik = try {
                        dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind.valueOf(ikStr)
                    } catch (e: IllegalArgumentException) {
                        throw CoreShellCodecException(
                            "INTERRUPTED variant 'interruptionKind' is not a valid InterruptionKind: '$ikStr'",
                        )
                    }
                    val causedBy = obj["causedBy"]?.asStringOrNull()
                    val deadline = obj["deadlineEpochMillis"]?.asLongOrNull()
                    val details = obj["details"]?.asStringMapOrNull() ?: emptyMap()
                    ShellInvocationResult.Interrupted(
                        interruption = dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord(
                            kind = ik,
                            message = imStr,
                            operationId = opId,
                            causedBy = causedBy,
                            deadlineEpochMillis = deadline,
                            details = details,
                        ),
                    )
                }
                else -> throw CoreShellCodecException("unknown core.sh output 'kind': '$kindStr'")
            }

            // Validate the outcome discriminant first; defer constructing the
            // typed StepOutcome until the cross-check passes so we never invoke
            // `pipelineFailureFor` on a non-failure variant (which would throw
            // IllegalStateException instead of a typed CoreShellCodecException).
            val outcomeKind: String = outcomeStr
            when (outcomeKind) {
                "SUCCESS", "UNSTABLE", "FAILURE" -> Unit
                else -> throw CoreShellCodecException("unknown core.sh output 'outcome': '$outcomeKind'")
            }

            // Cross-check discriminant vs variant BEFORE constructing the typed outcome.
            // This keeps malformed payloads on the typed-decode-failure path.
            crossCheckOutcomeDiscriminant(result, outcomeKind)?.let { reason ->
                throw CoreShellCodecException(reason)
            }

            val outcome: StepOutcome = when (outcomeKind) {
                "SUCCESS" -> StepOutcome.Success
                "UNSTABLE" -> StepOutcome.Unstable
                "FAILURE" -> StepOutcome.Failure(pipelineFailureFor(result))
                else -> error("unreachable: crossCheckOutcomeDiscriminant passed for $outcomeKind")
            }

            return CoreShellOutput(result = result, outcome = outcome)
        }
    }

    /**
     * Build the canonical [PipelineFailure] for the `outcome = FAILURE` projection.
     *
     * Used by [decode] when the encoded `outcome` says `FAILURE` — the failure MUST
     * carry the same kind/message carried by the variant payload.
     */
    private fun pipelineFailureFor(result: ShellInvocationResult): PipelineFailure =
        when (result) {
            is ShellInvocationResult.Failed -> result.failure
            is ShellInvocationResult.Interrupted -> PipelineFailure(
                kind = FailureKind.TIMEOUT,
                message = result.interruption.message,
            )
            is ShellInvocationResult.UnitValue,
            is ShellInvocationResult.Stdout,
            is ShellInvocationResult.Status,
            -> error("decode inconsistency: outcome=FAILURE with non-failure variant $result")
        }

    /**
     * Cross-check rule (pre-construction): if the variant says `FAILED` or `INTERRUPTED`,
     * the outcome discriminant MUST be `FAILURE`; if the variant says `UNIT` / `STDOUT` /
     * `STATUS`, the outcome discriminant MUST be `SUCCESS` (or `UNSTABLE`, which is
     * currently never produced for `core.sh` but is accepted if the producer chose it).
     *
     * Operating on the discriminant string avoids invoking `pipelineFailureFor` on a
     * non-failure variant, which would throw `IllegalStateException` instead of the
     * typed `CoreShellCodecException`.
     */
    private fun crossCheckOutcomeDiscriminant(
        result: ShellInvocationResult,
        outcomeKind: String,
    ): String? = when {
        result is ShellInvocationResult.Failed && outcomeKind != "FAILURE" ->
            "outcome/variant mismatch: kind=FAILED but outcome=$outcomeKind"
        result is ShellInvocationResult.Interrupted && outcomeKind != "FAILURE" ->
            "outcome/variant mismatch: kind=INTERRUPTED but outcome=$outcomeKind"
        result is ShellInvocationResult.UnitValue && outcomeKind == "FAILURE" ->
            "outcome/variant mismatch: kind=UNIT but outcome=FAILURE"
        result is ShellInvocationResult.Stdout && outcomeKind == "FAILURE" ->
            "outcome/variant mismatch: kind=STDOUT but outcome=FAILURE"
        result is ShellInvocationResult.Status && outcomeKind == "FAILURE" ->
            "outcome/variant mismatch: kind=STATUS but outcome=FAILURE"
        else -> null
    }

    private fun discriminant(o: StepOutcome): String = when (o) {
        is StepOutcome.Success -> "SUCCESS"
        is StepOutcome.Unstable -> "UNSTABLE"
        is StepOutcome.Failure -> "FAILURE"
    }

    private fun kindDiscriminant(r: ShellInvocationResult): String = when (r) {
        ShellInvocationResult.UnitValue -> "UNIT"
        is ShellInvocationResult.Stdout -> "STDOUT"
        is ShellInvocationResult.Status -> "STATUS"
        is ShellInvocationResult.Failed -> "FAILED"
        is ShellInvocationResult.Interrupted -> "INTERRUPTED"
    }

    private fun outcomeDiscriminant(o: StepOutcome): String = discriminant(o)

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
