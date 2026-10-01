package example.block

import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.AttemptSegment
import dev.rubentxu.pipeline.v2.domain.step.BODY_CONTINUATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyContinuation
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import java.util.concurrent.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Example external BLOCK plugin (WU-RP-035, slice D).
 *
 * This is the plugin ADR-0081 claimed was possible: an external Step that owns a body and
 * whose OWN logic decides whether, when and how many times that body runs. It depends only
 * on public SDK contracts (`pipeline-domain`) and the public DSL (`pipeline-scripting-api`),
 * contains no import of `pipeline-application`, and is discovered through
 * [StepDefinitionContributor] via ServiceLoader. Zero core edits.
 *
 * What it proves that an atomic plugin cannot:
 *
 *  1. The handler is actually INVOKED for a body-bearing external Step. Before RP-035 the
 *     engine short-circuited a `BlockStepNode` straight to the body dispatcher, so this
 *     handler would never have run and `times` would have been ignored.
 *  2. The handler reaches its body only through the bound [BodyContinuation]. It never sees a
 *     `BodyRef`, never holds a `StepNode` and never iterates children.
 *  3. `times = 0` runs the body ZERO times. The engine does not substitute its own body
 *     semantics for the plugin's.
 *  4. Repetition is DURABLE, not a loop. Each iteration carries its own [AttemptSegment], so
 *     each gets a distinct body path and therefore its own journal rows. Invoking the same
 *     body twice WITHOUT a segment would replay the journal instead of duplicating effects —
 *     which is the exactly-once law doing its job, and the reason a naive repeat would have
 *     been a lie.
 */
@Serializable
data class RepeatInput(val times: Int) {
    init {
        require(times in 0..MAX_TIMES) { "times must be in 0..$MAX_TIMES, got $times" }
    }

    companion object {
        const val MAX_TIMES: Int = 10
    }
}

/**
 * Typed output AND the typed outcome carrier ([TypedStepOutput]).
 *
 * A registry handler has exactly one way to report an expected operational failure: return a
 * value that carries the outcome. Throwing would be wrong — the boundary classifies a thrown
 * handler as `FailureKind.ENGINE`, which is the classification for a programmer defect, not
 * for a body that legitimately failed. Cancellation is the single exception, and it is
 * rethrown as structured control (see the handler).
 *
 * The failure travels as two plain serializable fields and is rebuilt into the same
 * [PipelineFailure] it arrived as, so the kind and message survive the durable round trip
 * instead of being flattened.
 */
@Serializable
data class RepeatOutput(
    val invocations: Int,
    val succeeded: Int,
    val failureKind: String? = null,
    val failureMessage: String? = null,
) : TypedStepOutput {

    override val outcome: StepOutcome
        get() = if (failureKind == null || failureMessage == null) {
            StepOutcome.Success
        } else {
            StepOutcome.Failure(PipelineFailure(kind = FailureKind.valueOf(failureKind), message = failureMessage))
        }

    companion object {
        fun succeeded(invocations: Int, succeeded: Int): RepeatOutput = RepeatOutput(invocations, succeeded)

        fun failedAt(
            invocations: Int,
            succeeded: Int,
            failure: PipelineFailure,
        ): RepeatOutput = RepeatOutput(
            invocations = invocations,
            succeeded = succeeded,
            failureKind = failure.kind.name,
            failureMessage = failure.message,
        )
    }
}

object RepeatInputCodec : StepCodec<RepeatInput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: RepeatInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(RepeatInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): RepeatInput =
        json.decodeFromString(RepeatInput.serializer(), encoded.value)
}

object RepeatOutputCodec : StepCodec<RepeatOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: RepeatOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(RepeatOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): RepeatOutput =
        json.decodeFromString(RepeatOutput.serializer(), encoded.value)
}

object RepeatBodyStepDefinition : StepDefinition<RepeatInput, RepeatOutput> {

    val KEY = PluginStepId("example.repeat")

    /**
     * The per-iteration identity key, OWNED BY THIS PLUGIN.
     *
     * It must not be a child Step's key: if it were, re-shaping the body would rewrite the
     * durable identity of every iteration already journaled, and a past run could no longer
     * be told apart from a differently-shaped one.
     */
    val REPEAT_ATTEMPT_KEY = PluginStepId("example.repeat-attempt")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "repeat",
            configRef = "",
            pluginId = "example.repeat",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
            // The one declaration a plugin makes that core has to honour: the handler drives
            // the body. Everything else about this Step is ordinary registry contract.
            body = StepBody.Declared(
                invocation = BodyInvocationPolicy.ONCE,
                execution = BodyExecution(
                    owner = BodyExecutionOwner.HANDLER_CONTINUATION,
                    policy = BodyExecutionPolicy.Sequential,
                ),
                introduces = null,
            ),
        ),
        inputCodec = RepeatInputCodec,
        outputCodec = RepeatOutputCodec,
        requiredCapabilities = setOf(BODY_CONTINUATION_CAPABILITY),
    )

    override val handler = StepHandler<RepeatInput, RepeatOutput> { input, context ->
        val continuation: BodyContinuation = context.capabilities.get(BODY_CONTINUATION_CAPABILITY)
        var succeeded = 0
        var attempted = 0
        for (iteration in 1..input.times) {
            attempted++
            // The plugin states an INTENT (which iteration this is). It never builds a path,
            // an OpId, a journal key or a fingerprint: the engine turns this typed segment
            // into a concrete durable identity. The KEY is owned by this plugin's contract, so
            // a future refactor of the body's children cannot silently change the identity of
            // past iterations.
            val outcome = continuation.invoke(
                BodyInvocationContext(attempt = AttemptSegment(iteration, REPEAT_ATTEMPT_KEY)),
            )
            when (outcome) {
                is BodyOutcome.Cancelled ->
                    // Cancellation is structured control owned by an ancestor, not an outcome
                    // this plugin may translate into a failure. Rethrown so the boundary keeps
                    // it out of the ENGINE classification entirely. The stdlib exception type
                    // is deliberately used: on the JVM the engine's coroutines
                    // CancellationException is a typealias of this same class, so rethrowing
                    // it matches without the plugin gaining a coroutines dependency.
                    throw CancellationException(
                        "body iteration $iteration cancelled: ${outcome.reason}",
                    )
                is BodyOutcome.Completed -> when (val body = outcome.outcome) {
                    is StepOutcome.Failure -> return@StepHandler RepeatOutput.failedAt(attempted, succeeded, body.failure)
                    else -> succeeded++
                }
            }
        }
        RepeatOutput.succeeded(attempted, succeeded)
    }
}

/**
 * ServiceLoader entry point, identical in shape to the atomic example plugin: the runtime
 * discovers the contributor generically and never learns the name `repeat`.
 */
class RepeatContributor : StepDefinitionContributor {
    override val id: String = "example.repeat"

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(RepeatBodyStepDefinition)
}
