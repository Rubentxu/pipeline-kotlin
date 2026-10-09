package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
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
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Typed input payload of `core.waitUntil`.
 *
 * The legacy wire payload for `waitUntil(initialRecurrencePeriod = X, quiet = Y)` is:
 * `{"kind":"waitUntil","initialRecurrencePeriod":X,"quiet":Y}`
 *
 * The codec preserves the legacy envelope structure.
 */
data class WaitUntilInput(
    val initialRecurrencePeriod: Long = 1000L,
    val quiet: Boolean = false,
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
 * The handler emits WaitUntilPolled and WaitUntilCompleted events as the observable
 * runtime output. The typed output carries the final outcome for scriptability.
 *
 * ## Why the terminal is a [WaitUntilCompletion] and not a `String`
 *
 * This used to carry `resultOutcome: String`, and [outcome] was reconstructed by matching one
 * literal — `if (resultOutcome == "completed") Success else Failure(TIMEOUT)`. A `String` cannot
 * fail closed: a typo, a terminal a future version adds, or a corrupted journal all take the
 * `else` branch and produce a run that ended in a deadline it never reached, reported with a
 * message about a wait it never exceeded. Nothing in the type said "these three are all of them".
 *
 * The closed ADT [WaitUntilCompletion] already existed in `pipeline-domain` with exactly the
 * three terminals the domain has, and `WaitUntilEngine` already projected all five of its
 * emission sites from it. This value was the last string authority, and it now derives from the
 * same ADT as the durable engine: there is no field a caller could set to a token inconsistent
 * with the terminal it claims to report.
 *
 * ## The wire token is a projection, and it does not move
 *
 * [resultOutcome] remains available to scripting because the encoded `outcome` field is a
 * **published, frozen** surface (S8 freezes event schemas against these three tokens, and an
 * older journal has to keep decoding). It is derived from [completion] on every read, so it
 * cannot drift from the terminal — and the codec is the only place that parses it back, where an
 * unrecognised value is refused instead of interpreted. Direction of authority: ADT → token,
 * never token → decision.
 *
 * @see WaitUntilCompletion for the three terminals and what each one owns.
 */
data class WaitUntilOutput(
    val completion: WaitUntilCompletion,
    val totalAttempts: Int,
    val totalDurationMs: Long,
) : TypedStepOutput {

    /**
     * The historical wire token, derived rather than stored.
     *
     * Kept as a property because it is published scripting surface; it is not a field because a
     * stored token is a second source of truth that could disagree with [completion].
     */
    val resultOutcome: String get() = completion.wireOutcome

    override val outcome: StepOutcome get() = completion.toStepOutcome()
}

/**
 * Registry definition for `core.waitUntil`.
 *
 * `core.waitUntil` is REGISTRY_PRIMARY (retired from `LEGACY_PLUGIN_IDS` at
 * WU-LPR-301 / G5). The descriptor declares
 * `BodyExecutionPolicy.Retrying(waitUntil = WaitUntilShape())` with
 * `BodyExecutionOwner.CANONICAL_ENGINE`, so [StepDispatchEngine] takes the
 * CANONICAL_ENGINE short-circuit: the engine runs the block and
 * [capabilityRoutedHandler] is NEVER invoked.
 *
 * Verified, not assumed (B1.3, 2026-10-08): the handler was given a deliberately
 * absurd clock (1970-01-01) and no `WaitUntilPolled` carried that stamp — the
 * polling events come from `WaitUntilEngine`, and the body's own effect does happen
 * (asserted by `CoreWaitUntilBodyVsHandlerTest`). So the stub below is DEAD CODE on
 * the production path. It is kept, not deleted, because the StepDefinition requires a
 * handler and because deleting it would be a wider change than this slice allows;
 * the KDoc previously claimed this stub is reached through the legacy dispatcher,
 * which stopped being true at G5 and was the reason nobody questioned it.
 *
 * The stub must not be "completed" as if it were a second polling loop: if it were
 * ever made reachable it would report `Satisfied` without evaluating the condition,
 * which Semantic Constitution §2 classifies as a silent no-op.
 *
 * Capability design:
 * - [EVENT_SINK_CAPABILITY] publishes the durable observation events.
 */
object CoreWaitUntilStep {

    val KEY: PluginStepId = PluginStepId("core.waitUntil")

    private const val MAX_BACKOFF_MS = 60_000L
    private const val DEADLINE_MS = Long.MAX_VALUE

    private val inputCodec = object : StepCodec<WaitUntilInput> {
        override fun encode(value: WaitUntilInput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("waitUntil"))
                        put("initialRecurrencePeriod", JsonPrimitive(value.initialRecurrencePeriod))
                        put("quiet", JsonPrimitive(value.quiet))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): WaitUntilInput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "waitUntil") {
                "core.waitUntil payload kind must be 'waitUntil'"
            }
            val initialRecurrencePeriod = obj["initialRecurrencePeriod"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000L
            val quiet = obj["quiet"]?.jsonPrimitive?.booleanOrNull ?: false
            return WaitUntilInput(initialRecurrencePeriod, quiet)
        }
    }

    private val outputCodec = object : StepCodec<WaitUntilOutput> {
        override fun encode(value: WaitUntilOutput): EncodedStepValue =
            EncodedStepValue(
                Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("kind", JsonPrimitive("waitUntil"))
                        put("outcome", JsonPrimitive(value.resultOutcome))
                        put("totalAttempts", JsonPrimitive(value.totalAttempts))
                        put("totalDurationMs", JsonPrimitive(value.totalDurationMs))
                    },
                ),
            )

        override fun decode(encoded: EncodedStepValue): WaitUntilOutput {
            val obj = Json.parseToJsonElement(encoded.value).jsonObject
            require(obj["kind"]?.jsonPrimitive?.content == "waitUntil") {
                "core.waitUntil output payload kind must be 'waitUntil'"
            }
            val wireOutcome = obj.getValue("outcome").jsonPrimitive.content
            return WaitUntilOutput(
                completion = completionFromWireOutcome(wireOutcome),
                totalAttempts = obj.getValue("totalAttempts").jsonPrimitive.content.toInt(),
                totalDurationMs = obj.getValue("totalDurationMs").jsonPrimitive.content.toLong(),
            )
        }
    }

    /**
     * Parses a historical wire token back into the closed ADT, failing closed on anything else.
     *
     * This is the one place where a token becomes a terminal again, and it is deliberately the
     * *only* place: the token is a projection of [WaitUntilCompletion], and a reader of it that
     * cannot name the terminal it corresponds to has no authority to produce one. Coercing an
     * unknown value into a deadline (the old `else` branch) reported a timeout the run never
     * reached; refusing it reports a corrupt or newer journal, which is diagnosable and true.
     *
     * The mapping is total over the frozen set and undefined outside it, so a terminal whose wire
     * representation nobody decided is refused here rather than silently accepted.
     */
    private fun completionFromWireOutcome(wireOutcome: String): WaitUntilCompletion =
        when (wireOutcome) {
            WaitUntilCompletion.Satisfied.wireOutcome -> WaitUntilCompletion.Satisfied
            WaitUntilCompletion.DeadlineExceeded(attempt = 0, ceilingMs = 0).wireOutcome ->
                WaitUntilCompletion.DeadlineExceeded(attempt = 0, ceilingMs = 0)
            WaitUntilCompletion.Aborted(WaitUntilAbortCause.DurableRowAlreadyAborted).wireOutcome ->
                WaitUntilCompletion.Aborted(WaitUntilAbortCause.DurableRowAlreadyAborted)
            else -> throw IllegalArgumentException(
                "core.waitUntil output outcome '$wireOutcome' is not one of the frozen terminals " +
                    "${WaitUntilCompletionWireOutcomes.sorted()}. Refusing it rather than reading it " +
                    "as a deadline: an unknown value is a corrupt or newer journal, not a timeout " +
                    "the run reached.",
            )
        }

    // WU-LPR-301: waitUntil declares its execution shape structurally via the
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
     * NOT REACHED on the production path (see the object KDoc).
     *
     * Present because [StepDefinition] requires a handler, and because the contract
     * suite resolves the definition. It emits a single satisfied poll pair without
     * evaluating the condition. Do not wire it to a live path: a reachable version
     * would report a condition it never checked.
     */
    private val capabilityRoutedHandler: StepHandler<WaitUntilInput, WaitUntilOutput> =
        StepHandler { input, ctx ->
            val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)
            val startTime = System.currentTimeMillis()

            // Emit one WaitUntilPolled event (stub pattern: condition assumed true)
            sink.append(
                WaitUntilPolled(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    attempt = 1,
                    durationMs = 0L,
                    conditionResult = true, // Stub: condition assumed met
                ),
            )

            // Emit WaitUntilCompleted with "completed" outcome
            val completion = WaitUntilCompletion.Satisfied
            sink.append(
                WaitUntilCompleted(
                    eventId = UUID.randomUUID().toString(),
                    runId = ctx.runId.value,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    totalAttempts = 1,
                    totalDurationMs = 0L,
                    outcome = completion.wireOutcome,
                ),
            )

            WaitUntilOutput(
                completion = completion,
                totalAttempts = 1,
                totalDurationMs = 0L,
            )
        }

    val definition: StepDefinition<WaitUntilInput, WaitUntilOutput> =
        object : StepDefinition<WaitUntilInput, WaitUntilOutput> {
            override val contract: StepContract<WaitUntilInput, WaitUntilOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(EVENT_SINK_CAPABILITY) as Set<dev.rubentxu.pipeline.v2.domain.step.StepCapability>,
            )

            override val handler: StepHandler<WaitUntilInput, WaitUntilOutput> =
                capabilityRoutedHandler
        }

    /** Registers the candidate through the same open registry seam as any external Step. */
    fun registerInto(builder: StepRegistryBuilder) {
        builder.add(definition)
    }
}
