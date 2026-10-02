package dev.rubentxu.pipeline.v2.application

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
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputDenied
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `core.input` — ask a human and continue only if they say yes (RP6-B / WU-092).
 *
 * Contract: `docs/v2/07-uat/SPEC_WU092_INPUT.md`.
 *
 * ## Why HANDLER_CONTINUATION and not a body policy
 *
 * The body is CONDITIONAL in the strongest sense: an `Abort` means the author
 * asked the pipeline NOT to continue. `CANONICAL_ENGINE` would run the body
 * unconditionally, which would make `input` a no-op with extra steps. So the
 * handler owns the body and only invokes it on a `Proceed`.
 *
 * ## What the handler does NOT do
 *
 * It does not touch the filesystem, the clock or the journal. It asks through
 * [InputDecisions] and interprets what comes back. Deciding the question and its
 * bound is [inputIntentOf]'s job, once and purely.
 */
object CoreInputStep {

    val KEY: PluginStepId = PluginStepId("core.input")

    private val inputCodec = CoreInputWireCodec

    private val descriptor = StepDescriptor(
        stepId = KEY.value,
        name = "input",
        configRef = "",
        pluginId = "core",
        pluginVersion = "0.1.0",
        executionLocation = ExecutionLocation.CONTROLLER,
        // The Step writes a question file and reads an answer file. It touches
        // neither the workspace nor a subprocess, and the decision it produces is a
        // fact about the run rather than a change to it.
        effects = listOf(Effect.READ_ONLY),
        // MEMOIZED, and this is the substantive difference from `core.lock`: a lock
        // hold does not survive the process, so the acquisition re-runs; a human
        // DECISION does survive (it is durable on disk and journaled), so a re-run
        // replays the recorded answer and MUST NOT ask again. Asking twice would
        // be a second, contradictory question about the same operation.
        replayPolicy = ReplayPolicy.MEMOIZED,
        body = StepBody.Declared(
            invocation = BodyInvocationPolicy.ONCE,
            execution = BodyExecution(
                owner = BodyExecutionOwner.HANDLER_CONTINUATION,
                policy = BodyExecutionPolicy.Sequential,
            ),
            introduces = null,
        ),
    )

    private val handler: StepHandler<CoreInputInput, CoreInputOutput> = StepHandler { input, ctx ->
        val decisions: InputDecisions = ctx.capabilities.get(INPUT_DECISIONS_CAPABILITY)
        val continuation: BodyContinuation = ctx.capabilities.get(BODY_CONTINUATION_CAPABILITY)
        val budget: ExecutionBudget = ctx.capabilities.get(EXECUTION_BUDGET_CAPABILITY)
        val sink: EventSink = ctx.capabilities.get(EVENT_SINK_CAPABILITY)

        // The question is observable even when the declaration is rejected: the
        // event carries what the author wrote, not what the Step went on to do.
        sink.append(
            InputRequested(
                eventId = UUID.randomUUID().toString(),
                runId = ctx.runId.value,
                sequence = 0L,
                occurredAt = Instant.now(),
                message = input.message,
                submitter = input.submitter,
                id = input.id,
            ),
        )

        when (val resolution = inputIntentOf(input, budget)) {
            is InputIntentResolution.Rejected -> CoreInputOutput(
                requested = input.message,
                decision = null,
                denial = InputDenialReason.Unanswerable(resolution.error.diagnostic),
                bodyRan = false,
            ).also { denied(sink, ctx.runId.value, it) }

            is InputIntentResolution.Resolved -> when (
                val answer = decisions.awaitDecision(
                    request = InputRequest(
                        opId = ctx.runId.value,
                        message = input.message,
                        ok = input.ok,
                        submitter = input.submitter,
                        id = input.id,
                    ),
                    waitMillis = resolution.waitMillis,
                )
            ) {
                is InputResolution.Denied -> CoreInputOutput(
                    requested = input.message,
                    decision = null,
                    denial = answer.reason,
                    bodyRan = false,
                ).also { denied(sink, ctx.runId.value, it) }

                is InputResolution.Answered -> when (val decision = answer.decision) {
                    is InputDecision.Proceed -> {
                        sink.append(
                            InputProceed(
                                eventId = UUID.randomUUID().toString(),
                                runId = ctx.runId.value,
                                sequence = 0L,
                                occurredAt = Instant.now(),
                                submitter = decision.submitter,
                                message = decision.message,
                            ),
                        )
                        runBody(input.message, decision, continuation)
                    }
                    is InputDecision.Abort -> {
                        sink.append(
                            InputAborted(
                                eventId = UUID.randomUUID().toString(),
                                runId = ctx.runId.value,
                                sequence = 0L,
                                occurredAt = Instant.now(),
                                submitter = decision.submitter,
                                message = decision.message,
                            ),
                        )
                        // No continuation.invoke: an abort is the author saying
                        // the rest of this block must not happen.
                        CoreInputOutput(
                            requested = input.message,
                            decision = decision,
                            denial = null,
                            bodyRan = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * The body runs ONLY on a granted `Proceed`, and its outcome is the run's
     * outcome for everything downstream of the question.
     */
    private suspend fun runBody(
        requested: String,
        decision: InputDecision.Proceed,
        continuation: BodyContinuation,
    ): CoreInputOutput = when (val body = continuation.invoke(BodyInvocationContext())) {
        is BodyOutcome.Completed -> CoreInputOutput(
            requested = requested,
            decision = decision,
            denial = null,
            bodyRan = true,
            outcome = body.outcome,
        )
        is BodyOutcome.Cancelled -> CoreInputOutput(
            requested = requested,
            decision = decision,
            denial = null,
            bodyRan = true,
            outcome = StepOutcome.Failure(
                PipelineFailure(
                    kind = FailureKind.INFRASTRUCTURE,
                    message = "core.input: body of '$requested' was cancelled after the answer",
                ),
            ),
        )
    }

    /** One event for every non-answer, carrying the reason the author needs. */
    private fun denied(sink: EventSink, runId: String, output: CoreInputOutput) {
        val reason = output.denial ?: return
        sink.append(
            InputDenied(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                reason = when (reason) {
                    is InputDenialReason.TimedOut -> "TIMED_OUT after ${reason.waitedMillis}ms"
                    is InputDenialReason.Cancelled -> "CANCELLED"
                    is InputDenialReason.Unanswerable -> "UNANSWERABLE: ${reason.diagnostic}"
                },
            ),
        )
    }

    val definition: StepDefinition<CoreInputInput, CoreInputOutput> =
        object : StepDefinition<CoreInputInput, CoreInputOutput> {
            override val contract: StepContract<CoreInputInput, CoreInputOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = inputCodec,
                outputCodec = CoreInputOutputCodec,
                // One declaration, four halves: the port that asks and waits, the
                // continuation the granted Proceed runs, the budget that bounds the
                // wait, and the sink that makes the decision observable. Admission
                // is fail-closed before the handler runs when any is absent.
                requiredCapabilities = setOf(
                    INPUT_DECISIONS_CAPABILITY,
                    BODY_CONTINUATION_CAPABILITY,
                    EXECUTION_BUDGET_CAPABILITY,
                    EVENT_SINK_CAPABILITY,
                ),
            )

            override val handler: StepHandler<CoreInputInput, CoreInputOutput> = this@CoreInputStep.handler
        }

    fun registerInto(registry: StepRegistry) {
        registry.register(definition)
    }
}

internal fun decisionDiscriminant(decision: InputDecision): String = when (decision) {
    is InputDecision.Proceed -> "PROCEED"
    is InputDecision.Abort -> "ABORT"
}
