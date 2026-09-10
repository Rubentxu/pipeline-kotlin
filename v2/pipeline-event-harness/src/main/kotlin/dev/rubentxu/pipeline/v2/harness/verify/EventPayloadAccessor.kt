package dev.rubentxu.pipeline.v2.harness.verify

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.identity.PipelineEventEnvelope
import dev.rubentxu.pipeline.v2.harness.model.FieldMatch

/**
 * Pair of the observable envelope with its decoded typed payload.
 */
data class TypedEvent(
    val envelope: PipelineEventEnvelope,
    val event: DomainEvent,
) {
    val kind: String get() = envelope.kind
    val sequence: Long get() = envelope.sequence
}

/**
 * Typed field extraction. Resolution decodes the stored DomainEvent and reads
 * named domain fields via exhaustive matching over the relevant kinds.
 * A match on a kind that does not carry that field is simply false (selector
 * mismatch), never an error — errors are reserved for decode corruption.
 */
object EventPayloadAccessor {

    fun matches(event: TypedEvent, where: List<FieldMatch>): Boolean =
        where.all { matchesOne(event, it) }

    private fun matchesOne(event: TypedEvent, m: FieldMatch): Boolean {
        val e = event.event
        return when (m) {
            is FieldMatch.CatchBuildResult ->
                (e as? dev.rubentxu.pipeline.v2.events.CatchErrorTriggered)?.buildResult == m.value
            is FieldMatch.RetryOutcome ->
                (e as? dev.rubentxu.pipeline.v2.events.RetryAttemptFinished)?.outcome == m.value
            is FieldMatch.AttemptNumber ->
                (e as? dev.rubentxu.pipeline.v2.events.RetryAttemptFinished)?.attemptNumber == m.value
            is FieldMatch.BranchIndex ->
                (e as? dev.rubentxu.pipeline.v2.events.ParallelBranchStarted)?.branchIndex == m.value ||
                    (e as? dev.rubentxu.pipeline.v2.events.ParallelBranchFinished)?.branchIndex == m.value
            is FieldMatch.Outcome ->
                payloadOutcome(e) == m.value
            is FieldMatch.MessageContains ->
                payloadMessage(e)?.contains(m.fragment) == true
            is FieldMatch.StageIndex ->
                payloadStageIndex(e) == m.value
            is FieldMatch.StepIndex ->
                payloadStepIndex(e) == m.value
        }
    }

    /** Typed composite key used by RelationScope.SameKey grouping. */
    fun scopeKey(event: TypedEvent, kind: dev.rubentxu.pipeline.v2.harness.model.KeyKind): String? {
        val e = event.event
        return when (kind) {
            dev.rubentxu.pipeline.v2.harness.model.KeyKind.BRANCH -> when (e) {
                is dev.rubentxu.pipeline.v2.events.ParallelBranchStarted -> "${e.parentStageIndex}:${e.branchIndex}"
                is dev.rubentxu.pipeline.v2.events.ParallelBranchFinished -> "${e.parentStageIndex}:${e.branchIndex}"
                else -> null
            }
            dev.rubentxu.pipeline.v2.harness.model.KeyKind.STAGE -> payloadStageIndex(e)?.let { "$it" }
            dev.rubentxu.pipeline.v2.harness.model.KeyKind.STEP ->
                if (payloadStageIndex(e) != null && payloadStepIndex(e) != null)
                    "${payloadStageIndex(e)}:${payloadStepIndex(e)}" else null
        }
    }

    fun summary(event: TypedEvent): String = when (val e = event.event) {
        is dev.rubentxu.pipeline.v2.events.RunFinished -> "outcome=${e.outcome}"
        is dev.rubentxu.pipeline.v2.events.StageFinished -> "stage=${e.stageIndex} outcome=${e.outcome}"
        is dev.rubentxu.pipeline.v2.events.StageStarted -> "stage=${e.stageIndex}"
        is dev.rubentxu.pipeline.v2.events.ParallelBranchStarted -> "branch=${e.parentStageIndex}:${e.branchIndex} '${e.branchName}'"
        is dev.rubentxu.pipeline.v2.events.ParallelBranchFinished -> "branch=${e.parentStageIndex}:${e.branchIndex} outcome=${e.outcome}"
        is dev.rubentxu.pipeline.v2.events.RetryAttemptFinished -> "attempt=${e.attemptNumber} outcome=${e.outcome}"
        is dev.rubentxu.pipeline.v2.events.RetryAttemptStarted -> "attempt=${e.attemptNumber}"
        is dev.rubentxu.pipeline.v2.events.CatchErrorTriggered -> "buildResult=${e.buildResult}"
        is dev.rubentxu.pipeline.v2.events.StepStarted -> "stage=${e.stageIndex} step=${e.stepIndex}"
        is dev.rubentxu.pipeline.v2.events.StepFailed -> "msg~'${e.message.take(40)}'"
        is dev.rubentxu.pipeline.v2.events.TimeoutScheduled -> "timeout=${e.timeoutSeconds}s"
        else -> ""
    }

    private fun payloadOutcome(e: DomainEvent): String? = when (e) {
        is dev.rubentxu.pipeline.v2.events.RunFinished -> e.outcome
        is dev.rubentxu.pipeline.v2.events.StageFinished -> e.outcome
        is dev.rubentxu.pipeline.v2.events.ParallelBranchFinished -> e.outcome
        is dev.rubentxu.pipeline.v2.events.RetryAttemptFinished -> e.outcome
        else -> null
    }

    private fun payloadMessage(e: DomainEvent): String? = when (e) {
        is dev.rubentxu.pipeline.v2.events.StepFailed -> e.message
        is dev.rubentxu.pipeline.v2.events.CatchErrorTriggered -> e.message
        is dev.rubentxu.pipeline.v2.events.EchoOutputCaptured -> e.content
        else -> null
    }

    private fun payloadStageIndex(e: DomainEvent): Int? = when (e) {
        is dev.rubentxu.pipeline.v2.events.StageStarted -> e.stageIndex
        is dev.rubentxu.pipeline.v2.events.StageFinished -> e.stageIndex
        is dev.rubentxu.pipeline.v2.events.StepStarted -> e.stageIndex
        is dev.rubentxu.pipeline.v2.events.ParallelBranchStarted -> e.parentStageIndex
        is dev.rubentxu.pipeline.v2.events.ParallelBranchFinished -> e.parentStageIndex
        is dev.rubentxu.pipeline.v2.events.RetryAttemptStarted -> e.stageIndex
        is dev.rubentxu.pipeline.v2.events.RetryAttemptFinished -> e.stageIndex
        is dev.rubentxu.pipeline.v2.events.TimeoutScheduled -> e.stageIndex
        else -> null
    }

    private fun payloadStepIndex(e: DomainEvent): Int? = when (e) {
        is dev.rubentxu.pipeline.v2.events.StepStarted -> e.stepIndex
        is dev.rubentxu.pipeline.v2.events.RetryAttemptStarted -> e.stepIndex
        is dev.rubentxu.pipeline.v2.events.RetryAttemptFinished -> e.stepIndex
        is dev.rubentxu.pipeline.v2.events.TimeoutScheduled -> e.stepIndex
        else -> null
    }
}
