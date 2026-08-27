package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.BranchReconciler
import dev.rubentxu.pipeline.v2.application.walkPipelineSpecDurable
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceException
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking

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
    private val clock: Clock,
    /**
     * Root directory for durable shell control directories.
     *
     * Set from --db parent in Main.kt. Each step gets a subdirectory:
     * $controlDirRoot/$opId/
     */
    private val controlDirRoot: Path? = null,
    /**
     * Sandbox profile for this pipeline run (ML-R3).
     *
     * NONE: no sandbox (backward compatible)
     * LOCAL: apply deny-list + PATH normalization
     * OS: container-based sandbox (not implemented in L3 — throws at CLI)
     */
    private val sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    /**
     * Redacting event sink for secret redaction (T6).
     *
     * When non-null, the [EventSink] passed to step execution is replaced
     * with this redaction decorator. Registered patterns are dropped on
     * [CredentialScope] close (CR-RD-011).
     */
    private val redactingEventSink: RedactingEventSink? = null,
    /**
     * Secret store for credential resolution in withCredentials blocks (T11).
     *
     * When non-null, credentials can be resolved and injected into step execution.
     * When null, withCredentials blocks will execute inner steps without credential injection.
     */
    private val secretStore: SecretStore? = null,
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
    suspend fun run(
        spec: PipelineSpec,
        runId: String,
        startFromCursor: Boolean,
    ): Result<String> {
        return runBlocking {
            val runStartedId = UUID.randomUUID().toString()
            val runStartedAt = clock.now()
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

                // M3-R5 B1: Construct BranchReconciler once and thread via DurableWalkContext.
                // ADR-0040 Path A: reconciler is constructed here (not in walkPipelineSpecDurable)
                // and passed through ctx.branchReconciler for the LIVE reconciliation pass.
                val branchReconciler = BranchReconciler(journal, cursorStore, clock)

                // ML-R1: Construct StepReconcilerL1 for durable shell classification
                val stepReconcilerL1 = if (controlDirRoot != null) {
                    StepReconcilerL1(clock, controlDirRoot, DurableShConfig.fromSystemProperties(), journal)
                } else {
                    null
                }

                // ML-R2 T4: Construct WorkspaceResolver for per-stage workspace management
                val workspaceResolver = if (controlDirRoot != null) {
                    WorkspaceResolver(controlDirRoot)
                } else {
                    null
                }

                // ML-R2 T4: Default ShOptions (stage/step options override via merge in walkPipelineSpecDurable)
                val defaultShOptions = if (controlDirRoot != null) {
                    ShOptions(
                        workspaceRoot = controlDirRoot.resolve("workspace"),
                        captureStdout = false,
                        timeoutMs = null,
                        env = emptyMap(),
                    )
                } else {
                    null
                }

                val ctx = DurableWalkContext(
                    clock = clock,
                    opJournal = journal,
                    cursorStore = cursorStore,
                    branchReconciler = branchReconciler,
                    eventSink = eventSink,
                    stepReconcilerL1 = stepReconcilerL1,
                    controlDirRoot = controlDirRoot,
                    workspaceResolver = workspaceResolver,
                    shOptions = defaultShOptions,
                    sandboxProfile = sandboxProfile,
                    secretStore = secretStore,
                )

                // Execute with durable walk — ctx carries branchReconciler for LIVE reconciliation
                val runOutcome = walkPipelineSpecDurable(
                    spec = spec,
                    runId = runId,
                    ctx = ctx,
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
            val runFinishedAt = clock.now()
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
                                severity = dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity.ERROR,
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

            outcome
        }
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
