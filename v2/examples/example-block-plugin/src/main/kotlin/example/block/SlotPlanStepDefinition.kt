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
import dev.rubentxu.pipeline.v2.domain.step.BODY_SLOT_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.BodyInvocationContext
import dev.rubentxu.pipeline.v2.domain.step.BodyOutcome
import dev.rubentxu.pipeline.v2.domain.step.BodySlotInvoker
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The SLOT seam, exercised by an EXTERNAL plugin.
 *
 * ## The problem this Step exists to answer
 *
 * The body continuation runs the WHOLE body. That is the right shape for a loop
 * (`repeat` runs its body N times) and the wrong one for a plan with
 * alternatives: a Saga runs the FORWARD body on the happy path and the
 * COMPENSATE body after a failure, and it has to be able to CHOOSE. Before
 * the slot seam existed, that choice was impossible from outside the engine:
 * a handler could not name one body of its own.
 *
 * ## What the handler does
 *
 * It names two slots — the FORWARD body and the COMPENSATE body — runs the
 * forward one, and runs the compensate one only if the forward one failed. The
 * handler never sees a body identity, never holds a child and never iterates
 * children: it asks for a slot BY NAME and the engine resolves it, dispatches
 * it and owns its durable identity.
 *
 * ## Why the slots are core blocks in this fixture
 *
 * The seam addresses a slot by the PLUGIN STEP KEY of the child, so the two
 * children of a body must have DIFFERENT keys — two `sh` steps in one body
 * share `core.sh`, and the seam refuses an ambiguous name rather than
 * guessing. This fixture uses two core blocks (`core.dir` and `core.withEnv`),
 * which are two real bodies with two different keys and cost no extra
 * definitions. A Saga plugin that wants two bodies of its OWN kind declares
 * two definitions; nothing in the seam changes.
 */
@Serializable
data class SlotPlanInput(
    val forwardSlot: String,
    val compensateSlot: String,
)

/**
 * What the plan did, in plain serializable fields.
 *
 * The failure travels as a kind and a message and is rebuilt into the same
 * [PipelineFailure] it arrived as, so the durable round trip keeps the kind
 * instead of flattening it.
 */
@Serializable
data class SlotPlanOutput(
    val forward: String,
    val compensate: String,
    val failureKind: String? = null,
    val failureMessage: String? = null,
) : TypedStepOutput {

    override val outcome: StepOutcome
        get() = if (failureKind == null || failureMessage == null) {
            StepOutcome.Success
        } else {
            StepOutcome.Failure(
                PipelineFailure(kind = FailureKind.valueOf(failureKind), message = failureMessage),
            )
        }

    companion object {
        const val NOT_RUN = "not-run"
        const val SUCCEEDED = "succeeded"
        const val FAILED = "failed"
    }
}

object SlotPlanCodec : StepCodec<SlotPlanInput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: SlotPlanInput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(SlotPlanInput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): SlotPlanInput =
        json.decodeFromString(SlotPlanInput.serializer(), encoded.value)
}

object SlotPlanOutputCodec : StepCodec<SlotPlanOutput> {
    private val json = Json { encodeDefaults = true }

    override fun encode(value: SlotPlanOutput): EncodedStepValue =
        EncodedStepValue(json.encodeToString(SlotPlanOutput.serializer(), value))

    override fun decode(encoded: EncodedStepValue): SlotPlanOutput =
        json.decodeFromString(SlotPlanOutput.serializer(), encoded.value)
}

object SlotPlanStepDefinition : StepDefinition<SlotPlanInput, SlotPlanOutput> {

    val KEY = PluginStepId("example.block.slots")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "slots",
            configRef = "",
            pluginId = "example.block",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
            // The handler drives the body, exactly as the repeat Step does. The
            // difference is WHICH part of the body it drives: the repeat Step
            // runs the whole body N times, this one runs ONE of the bodies.
            body = StepBody.Declared(
                invocation = BodyInvocationPolicy.ONCE,
                execution = BodyExecution(
                    owner = dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner.HANDLER_CONTINUATION,
                    policy = dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy.Sequential,
                ),
                introduces = null,
            ),
        ),
        inputCodec = SlotPlanCodec,
        outputCodec = SlotPlanOutputCodec,
        // The one capability this Step needs: the port that runs a named child
        // of its own body. The engine binds it only for a Step whose body is
        // already bound, so a Step that declares it and does not get it fails
        // closed at admission instead of running without the seam.
        requiredCapabilities = setOf(BODY_SLOT_CAPABILITY),
    )

    override val handler = StepHandler<SlotPlanInput, SlotPlanOutput> { input, context ->
        val slotInvoker: BodySlotInvoker = context.capabilities.get(BODY_SLOT_CAPABILITY)

        when (val forward = runSlot(slotInvoker, input.forwardSlot)) {
            is SlotRun.Cancelled -> SlotPlanOutput(
                forward = SlotPlanOutput.NOT_RUN,
                compensate = SlotPlanOutput.NOT_RUN,
                failureKind = FailureKind.ENGINE.name,
                failureMessage = "forward slot cancelled: ${forward.reason.name}",
            )

            is SlotRun.Succeeded -> SlotPlanOutput(
                forward = SlotPlanOutput.SUCCEEDED,
                compensate = SlotPlanOutput.NOT_RUN,
            )

            is SlotRun.Failed -> {
                // The forward body failed, so the plan compensates. The
                // compensate slot is a DIFFERENT child of the same body, so it
                // carries a different durable identity: the engine dispatched
                // it, and the journal says so.
                when (val compensate = runSlot(slotInvoker, input.compensateSlot)) {
                    is SlotRun.Cancelled -> SlotPlanOutput(
                        forward = SlotPlanOutput.FAILED,
                        compensate = SlotPlanOutput.NOT_RUN,
                        failureKind = FailureKind.ENGINE.name,
                        failureMessage = "compensate slot cancelled: ${compensate.reason.name}",
                    )

                    is SlotRun.Succeeded -> SlotPlanOutput(
                        forward = SlotPlanOutput.FAILED,
                        compensate = SlotPlanOutput.SUCCEEDED,
                    )

                    is SlotRun.Failed -> SlotPlanOutput(
                        forward = SlotPlanOutput.FAILED,
                        compensate = SlotPlanOutput.FAILED,
                        failureKind = compensate.failure.kind.name,
                        failureMessage = compensate.failure.message,
                    )
                }
            }
        }
    }

    /**
     * One slot, run once, reduced to the three things a plan can observe.
     *
     * A local algebra rather than a nested `when`: the engine's [BodyOutcome]
     * is a two-case sealed type and [StepOutcome] is a three-case one, so
     * folding them here keeps the plan readable and keeps the compiler
     * honest about the cases it did not think about.
     */
    private sealed interface SlotRun {
        data class Succeeded(val outcome: StepOutcome) : SlotRun

        data class Failed(val failure: PipelineFailure) : SlotRun

        data class Cancelled(val reason: dev.rubentxu.pipeline.v2.domain.step.CancellationReason) : SlotRun
    }

    private suspend fun runSlot(slotInvoker: BodySlotInvoker, slot: String): SlotRun =
        when (val outcome = slotInvoker.invoke(PluginStepId(slot), BodyInvocationContext())) {
            is BodyOutcome.Cancelled -> SlotRun.Cancelled(outcome.reason)
            is BodyOutcome.Completed -> when (val result = outcome.outcome) {
                is StepOutcome.Failure -> SlotRun.Failed(result.failure)
                else -> SlotRun.Succeeded(result)
            }
        }
}
