package com.pipeline.v2.application.durable

import com.pipeline.v2.application.walkPipelineSpecDurable
import com.pipeline.v2.domain.durable.DivergenceDetector
import com.pipeline.v2.domain.durable.DivergenceException
import com.pipeline.v2.events.EventSink
import com.pipeline.v2.events.RunFinished
import com.pipeline.v2.events.RunStarted
import com.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import com.pipeline.v2.events.durable.OperationJournal
import com.pipeline.v2.events.durable.ReplayCursor
import com.pipeline.v2.events.durable.ReplayCursorStore
import com.pipeline.v2.dsl.PipelineSpec
import com.pipeline.v2.scripting.ScriptingDiagnostic
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Top-level orchestrator for a durable pipeline run.
 *
 * Owns the durable runtime: coordinates the [OperationJournal],
 * [ReplayCursorStore], [DivergenceDetector], and [EffectReplayPolicy]
 * to execute a [PipelineSpec] with full replay-safety and fail-closed
 * divergence detection.
 *
 * ## Responsibilities
 *
 * 1. Loads the [ReplayCursor] for the run (if any) to support resume.
 * 2. Delegates step execution to [walkPipelineSpecDurable], passing all
 *    durable dependencies.
 * 3. Emits [RunStarted] / [RunFinished] events wrapping the execution.
 * 4. Translates [DivergenceException] into a failed [RunFinished].
 *
 * @see <a href="design.md §4.4">Design §4.4</a>
 */
class PipelineOrchestrator(
    private val journal: OperationJournal,
    private val cursorStore: ReplayCursorStore,
    private val divergenceDetector: DivergenceDetector,
    private val effectReplayPolicy: EffectReplayPolicy,
    private val eventSink: EventSink,
) {
    /**
     * Executes a pipeline spec with full durable guarantees.
     *
     * @param spec            The pipeline specification to execute.
     * @param runId           The deterministic run identifier.
     * @param startFromCursor If true, loads the persisted [ReplayCursor] and resumes
     *                        from the last journaled operation. If false, starts from
     *                        the beginning (idempotent rerun of the full pipeline).
     * @return [Result.success] with the run outcome string on success,
     *         or [Result.failure] with [DivergenceException] if divergence was detected.
     */
    fun run(
        spec: PipelineSpec,
        runId: String,
        startFromCursor: Boolean,
    ): Result<String> {
        val runStartedId = UUID.randomUUID().toString()
        val runStartedAt = Instant.now()
        eventSink.append(
            RunStarted(
                eventId = runStartedId,
                runId = runId,
                sequence = 0L,
                occurredAt = runStartedAt,
                scriptPath = "",
            )
        )

        val outcome: Result<String> = try {
            // Load cursor if resuming
            val cursor = if (startFromCursor) {
                cursorStore.load(runId)
            } else {
                null
            }

            // Execute with durable walk
            val runOutcome = walkPipelineSpecDurable(
                spec = spec,
                runId = runId,
                eventSink = eventSink,
                journal = journal,
                cursorStore = cursorStore,
                divergenceDetector = divergenceDetector,
                effectReplayPolicy = effectReplayPolicy,
                startFromStageIndex = cursor?.stageIndex ?: 0,
                startFromStepIndex = 0,
            )
            Result.success(runOutcome)
        } catch (ex: DivergenceException) {
            Result.failure(ex)
        }

        val runOutcomeValue = outcome.getOrElse { "failure" }
        val runFinishedId = UUID.randomUUID().toString()
        val runFinishedAt = Instant.now()
        eventSink.append(
            RunFinished(
                eventId = runFinishedId,
                runId = runId,
                sequence = 0L,
                occurredAt = runFinishedAt,
                outcome = runOutcomeValue,
                diagnostics = if (outcome.isFailure) {
                    listOf(
                        ScriptingDiagnostic(
                            severity = com.pipeline.v2.scripting.ScriptDiagnosticSeverity.ERROR,
                            message = outcome.exceptionOrNull()?.message ?: "Unknown error",
                            line = 0,
                            column = 0,
                            path = "",
                        )
                    )
                } else {
                    emptyList()
                },
            )
        )

        return outcome
    }

    /**
     * Derives a deterministic runId from the script path and content.
     */
    private fun deriveRunId(scriptPath: String, scriptContent: String): String {
        val input = "$scriptPath|$scriptContent"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(36)
    }
}
