package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.step.DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.WaitUntilShape

import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilAbortCause
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilCompletion
import dev.rubentxu.pipeline.v2.domain.durable.WaitUntilCompletionWireOutcomes
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed input payload of `core.waitUntil`.
 * The wire payload for `waitUntil(initialRecurrencePeriod = X)` is:
 * `{"kind":"waitUntil","initialRecurrencePeriod":X}`
 * The codec preserves that envelope structure. A payload written by an older build may also
 * carry a `"quiet"` key; decoding ignores it, which is safe because nothing ever read it.
*/
data class WaitUntilInput(
    val initialRecurrencePeriod: Long = DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS,
) {
    init {
        require(initialRecurrencePeriod >= 0) {
            "core.waitUntil requires initialRecurrencePeriod >= 0"
        }
    }
}

/**
 * Typed output for `core.waitUntil`.
 *
 * There are two shapes, and only two: a wait that ENDED, and a route that REFUSED to produce a
 * verdict. The refusal is NOT a third terminal of the wait — [WaitUntilCompletion] keeps exactly
 * the three the domain has — it is the absence of a verdict, reported with the product's own
 * "capability not supported" vocabulary so that a consumer reads one refusal the same way
 * wherever it comes from.
 *
 * ## Why the terminal is a [WaitUntilCompletion] and not a `String`
 * This used to carry `resultOutcome: String`, and [TypedStepOutput.outcome] was reconstructed by
 * matching one literal — `if (resultOutcome == "completed") Success else Failure(TIMEOUT)`. A
 * `String` cannot fail closed: a typo, a terminal a future version adds, or a corrupted journal
 * all take the `else` branch and produce a run that ended in a deadline it never reached,
 * reported with a message about a wait it never exceeded. Nothing in the type said "these three
 * are all of them". The closed ADT [WaitUntilCompletion] already existed in `pipeline-domain` with
 * exactly the three terminals the domain has, and `WaitUntilEngine` already projected all five of
 * its emission sites from it. This value was the last string authority, and it now derives from
 * the same ADT as the durable engine: there is no field a caller could set to a token inconsistent
 * with the terminal it claims to report.
 */
sealed interface WaitUntilOutput : TypedStepOutput {

    /**
     * The wait ended on one of the three frozen terminals.
     *
     * The wire token is a projection, and it does not move: [resultOutcome] remains available to
     * scripting because the encoded `outcome` field is a **published, frozen** surface (it freezes
     * event schemas against these three tokens, and an older journal has to keep decoding). It is
     * derived from [completion] on every read, so it cannot drift from the terminal — and the codec
     * is the only place that parses it back, where an unrecognised value is refused instead of
     * interpreted. Direction of authority: ADT → token, never token → decision.
     *
     * @see WaitUntilCompletion for the three terminals and what each one owns.
     */
    data class Completed(
        val completion: WaitUntilCompletion,
        val totalAttempts: Int,
        val totalDurationMs: Long,
    ) : WaitUntilOutput {

        /**
         * The historical wire token, derived rather than stored.
         * Kept as a property because it is published scripting surface; it is not a field because
         * a stored token is a second source of truth that could disagree with [completion].
        */
        val resultOutcome: String get() = completion.wireOutcome

        override val outcome: StepOutcome get() = completion.toStepOutcome()
    }

    /**
     * The route could not reach the predicate, so it produced no verdict.
     *
     * [reason] is the product's own "capability not supported" reason — the same type and the
     * same wording the registry admission writes, see [MissingCapabilityReason] — and the
     * verdict it carries is a [StepOutcome.Failure] over a [FailureKind.SCHEMA] failure: a step
     * that cannot run ends the run with a diagnosis, not with a silent success.
     *
     * @param reason the named refusal, in the product's vocabulary.
     * @param missingCapabilities the absent capabilities, as their keys.
     */
    data class Refused(
        val reason: String,
        val missingCapabilities: List<String>,
    ) : WaitUntilOutput {

        override val outcome: StepOutcome get() =
            StepOutcome.Failure(PipelineFailure(FailureKind.SCHEMA, reason))
    }
}

/**
 * Registry definition for `core.waitUntil`.
 * `core.waitUntil` is a registry Step: the registry is the single production
 * authority for its contract and its metadata.
 * The descriptor declares
 * `BodyExecutionPolicy.Retrying(waitUntil = WaitUntilShape())` with
 * `BodyExecutionOwner.CANONICAL_ENGINE`, so [StepDispatchEngine] takes the
 * CANONICAL_ENGINE short-circuit: the engine runs the block and the handler
 * below is NOT invoked.
 * The handler exists because [StepDefinition] requires one, and it is NOT a second polling
 * loop. It REFUSES. A handler can only re-run a body the engine has already bound to it, and
 * the engine binds a continuation only for a [BodyExecutionOwner.HANDLER_CONTINUATION] owner;
 * this owner is CANONICAL_ENGINE, so the predicate — the block's BODY, captured as DATA when the
 * program was compiled — is unreachable here. A route that cannot reach its predicate has no
 * business claiming that the predicate held, so it refuses with the product's own "capability
 * not supported" vocabulary: see [MissingCapabilityReason].
 * Capability design:
 * - [EVENT_SINK_CAPABILITY] is the contract's declared capability. The refusal does not name
 *   it: the engine does supply it, and the durable observation events are published by the
 *   engine on the canonical path, not by the handler.
 * - The capability the refusal names is [BODY_CONTINUATION_CAPABILITY]. It is deliberately NOT
 *   part of the contract: a CANONICAL_ENGINE owner has no continuation to bind, so declaring it
 *   would be incoherent, and the plugin manifest must declare exactly the contract's
 *   capabilities.
 */
object CoreWaitUntilStep {

    val KEY: PluginStepId = PluginStepId("core.waitUntil")

    private const val DEADLINE_MS = Long.MAX_VALUE

    /** The wire kind of a wait that ENDED. Frozen: an older journal carries it. */
    private const val COMPLETED_OUTPUT_KIND = "waitUntil"

    /** The wire kind of a route that REFUSED. Its own kind: the refusal is not a wait terminal. */
    private const val REFUSED_OUTPUT_KIND = "waitUntilRefused"

    private val inputCodec = object : StepCodec<WaitUntilInput> {
        override fun encode(value: WaitUntilInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("waitUntil"))
                        put("initialRecurrencePeriod", JsonPrimitive(value.initialRecurrencePeriod))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): WaitUntilInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "waitUntil") {
                "core.waitUntil payload kind must be 'waitUntil'"
            }
            val initialRecurrencePeriod = obj["initialRecurrencePeriod"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS
            return WaitUntilInput(initialRecurrencePeriod)
        }
    }

    private val outputCodec = object : StepCodec<WaitUntilOutput> {
        override fun encode(value: WaitUntilOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    when (value) {
                        is WaitUntilOutput.Completed -> {
                            requireTerminalFitsTheWire(value.completion)
                            buildJsonObject {
                                put("kind", JsonPrimitive(COMPLETED_OUTPUT_KIND))
                                put("outcome", JsonPrimitive(value.resultOutcome))
                                put("totalAttempts", JsonPrimitive(value.totalAttempts))
                                put("totalDurationMs", JsonPrimitive(value.totalDurationMs))
                            }
                        }
                        is WaitUntilOutput.Refused -> buildJsonObject {
                            put("kind", JsonPrimitive(REFUSED_OUTPUT_KIND))
                            put("reason", JsonPrimitive(value.reason))
                            put(
                                "missingCapabilities",
                                JsonArray(value.missingCapabilities.map { JsonPrimitive(it) }),
                            )
                        }
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): WaitUntilOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            return when (obj["kind"]?.jsonPrimitive?.content) {
                COMPLETED_OUTPUT_KIND -> WaitUntilOutput.Completed(
                    completion = completionFromWireOutcome(obj.getValue("outcome").jsonPrimitive.content),
                    totalAttempts = obj.getValue("totalAttempts").jsonPrimitive.content.toInt(),
                    totalDurationMs = obj.getValue("totalDurationMs").jsonPrimitive.content.toLong(),
                )
                REFUSED_OUTPUT_KIND -> WaitUntilOutput.Refused(
                    reason = obj.getValue("reason").jsonPrimitive.content,
                    missingCapabilities = obj.getValue("missingCapabilities").jsonArray
                        .map { it.jsonPrimitive.content },
                )
                else -> throw IllegalArgumentException(
                    "core.waitUntil output payload kind must be '$COMPLETED_OUTPUT_KIND' " +
                        "or '$REFUSED_OUTPUT_KIND'",
                )
            }
        }
    }

    /**
     * Parses a historical wire token back into the closed ADT, failing closed on anything else.
     * This is the one place where a token becomes a terminal again, and it is deliberately the
     * *only* place: the token is a projection of [WaitUntilCompletion], and a reader of it that
     * cannot name the terminal it corresponds to has no authority to produce one. Coercing an
     * unknown value into a deadline (the old `else` branch) reported a timeout the run never
     * reached; refusing it reports a corrupt or newer journal, which is diagnosable and true.
     *
     * Only the terminal whose parameters do NOT travel the wire can be rebuilt here. The frozen
     * shape projects the terminal to its token, so `attempt`, `ceilingMs` and the abort cause are
     * simply not in the payload: rebuilding those terminals would mean inventing their
     * parameters, and an invented terminal produces an invented diagnostic — a deadline that
     * claims it was reached at poll 0 with a 0 ms ceiling. Refusing is the true reading: the
     * durable event record keeps the real terminal.
     */
    private fun completionFromWireOutcome(wireOutcome: String): WaitUntilCompletion =
        when (wireOutcome) {
            WaitUntilCompletion.Satisfied.wireOutcome -> WaitUntilCompletion.Satisfied
            WaitUntilCompletion.DeadlineExceeded(attempt = 0, ceilingMs = 0).wireOutcome ->
                throw IllegalArgumentException(
                    "core.waitUntil output outcome '$wireOutcome' travels neither the attempt nor " +
                        "the ceiling, so its terminal cannot be rebuilt without inventing them; an " +
                        "invented terminal reports a false diagnostic. The durable event record " +
                        "keeps the real one.",
                )
            WaitUntilCompletion.Aborted(WaitUntilAbortCause.DurableRowAlreadyAborted).wireOutcome ->
                throw IllegalArgumentException(
                    "core.waitUntil output outcome '$wireOutcome' travels no abort cause, so its " +
                        "terminal cannot be rebuilt without inventing one; an invented terminal " +
                        "reports a false diagnostic. The durable event record keeps the real one.",
                )
            else -> throw IllegalArgumentException(
                "core.waitUntil output outcome '$wireOutcome' is not one of the frozen terminals " +
                    "${WaitUntilCompletionWireOutcomes.sorted()}. Refusing it rather than reading it " +
                    "as a deadline: an unknown value is a corrupt or newer journal, not a timeout " +
                    "the run reached.",
            )
        }

    /**
     * The wire can only carry the terminals it can carry without loss.
     *
     * The codec refuses in BOTH directions on purpose: encoding a parameterised terminal would
     * write a payload this same codec could not read back, and a payload nobody can read is a
     * journal nobody can replay.
     */
    private fun requireTerminalFitsTheWire(completion: WaitUntilCompletion) {
        require(completion is WaitUntilCompletion.Satisfied) {
            "core.waitUntil output cannot carry the parameters of terminal " +
                "'${completion.wireOutcome}': the frozen wire shape projects the terminal to its " +
                "token, so encoding it would lose them and decoding it back would have to invent " +
                "them. The durable event record keeps the real terminal."
        }
    }

    // WaitUntil declares its execution shape structurally via the
    // waitUntil sub-shape of BodyExecutionPolicy.Retrying. The coordinator dispatches
    // the polling loop by reading the sub-shape, with no concrete-StepKey branch.
    private val descriptor = StepDescriptor(
        stepId = "core.waitUntil",
        name = "waitUntil",
        configRef = "",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = listOf(Effect.READ_ONLY),
        replayPolicy = ReplayPolicy.MEMOIZED,
        body = StepBody.Declared(
            invocation = BodyInvocationPolicy.ZERO_OR_MORE,
            execution = BodyExecution(
                owner = BodyExecutionOwner.CANONICAL_ENGINE,
                policy = BodyExecutionPolicy.Retrying(
                    policy = RetryPolicy(),
                    waitUntil = WaitUntilShape(),
                ),
            ),
            introduces = null,
        ),
    )

    /**
     * The capability this route needs to reach the predicate, and the one the engine has not
     * supplied: a handler can only re-run a body the engine has already bound to it, and the
     * engine binds a continuation only for a [BodyExecutionOwner.HANDLER_CONTINUATION] owner.
     */
    private val predicateCapability: StepCapability = BODY_CONTINUATION_CAPABILITY

    /**
     * NOT REACHED on the production path (see the object KDoc).
     * Present because [StepDefinition] requires a handler. It REFUSES: it cannot reach the
     * predicate, so it cannot report a verdict, and reporting one anyway — a `Satisfied` it never
     * evaluated — is the silent no-op the Semantic Constitution forbids.
     *
     * The refusal is a typed RESULT, not a thrown exception: [StepHandler] forbids throwing to
     * signal a step failure, and an exception would be classified upstream as an engine bug
     * rather than as the run's verdict. The inverse condition IS an engine bug — a
     * CANONICAL_ENGINE owner has no continuation to bind, so the engine cannot have bound one —
     * and is reported as [EngineInvariantViolation], exactly as the registry boundary reports a
     * handler that reached execution without its declared capabilities.
    */
    private val capabilityRoutedHandler: StepHandler<WaitUntilInput, WaitUntilOutput> =
        StepHandler { _, ctx ->
            val missing = setOf(predicateCapability) - ctx.capabilities.available()
            if (missing.isEmpty()) {
                throw EngineInvariantViolation(
                    "registry step '${KEY.value}' reached its fallback route with the bound " +
                        "continuation capability available: a CANONICAL_ENGINE owner has no " +
                        "continuation to bind, so its predicate is unreachable there",
                )
            }
            WaitUntilOutput.Refused(
                reason = MissingCapabilityReason.of(KEY, missing),
                missingCapabilities = missing.map { it.key },
            )
        }

    val definition: StepDefinition<WaitUntilInput, WaitUntilOutput> =
        object : StepDefinition<WaitUntilInput, WaitUntilOutput> {
            override val contract: StepContract<WaitUntilInput, WaitUntilOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(EVENT_SINK_CAPABILITY),
            )

            override val handler: StepHandler<WaitUntilInput, WaitUntilOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(builder: StepRegistryBuilder) {
        builder.add(definition)
    }
}
