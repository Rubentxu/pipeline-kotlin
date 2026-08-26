package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellResult
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellState
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.executeDurableShell
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.sh as sdkSh
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Shell execution orchestration for durable steps.
 *
 * Extracted from PipelineRun.kt (FIND-268736 god-file split).
 * Provides a single canonical implementation for shell step execution
 * that threads workspace and env options.
 *
 * ## Responsibilities
 *
 * - `runShStep`: Executes a shell step with durable semantics
 * - `executeBranchStep`: Executes shell steps within parallel branches (W8 fold)
 *
 * ## P2 Invariant
 *
 * User script content NEVER appears in any argv. The wrapper script is constructed
 * with single-quoted path variables only (no script content in argv).
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 * @see <a href="ADR-0047">ADR-0047 — FAILED_TIMEOUT Terminal State</a>
 */
object ShExecution {

    /**
     * Executes a shell step with durable semantics.
     *
     * @param step The shell step specification.
     * @param opId The operation ID for this step.
     * @param runId The run identifier.
     * @param stageIndex The stage index for workspace naming.
     * @param stepIndex The step index for event sequencing.
     * @param shOptions Shell execution options (workspaceRoot, captureStdout, timeoutMs, env).
     * @param eventSink The event sink for emitting EchoOutputCaptured events.
     * @return "success" if exit code is 0, "failure" otherwise.
     */
    suspend fun runShStep(
        step: StepSpec.Shell,
        opId: OpId,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        shOptions: ShOptions,
        eventSink: EventSink,
    ): String {
        // Derive controlDirRoot from shOptions.workspaceRoot:
        // workspaceRoot is <controlRoot>/workspace/<stageName>-<stageIndex>/...
        // parent = <controlRoot>/workspace/, parent.parent = <controlRoot>
        val derivedControlDirRoot = shOptions.workspaceRoot.parent?.parent
        if (derivedControlDirRoot == null) {
            // Fallback to non-durable execution if no control dir root
            val argv = listOf("bash", "-c", step.command)
            val result = sdkSh(StepContext(runId = runId), argv, eventSink, stepIndex)
            return if (result.exitCode != 0) "failure" else "success"
        }

        val workspaceResolver = WorkspaceResolver(derivedControlDirRoot)
        val workspacePath = workspaceResolver.ensureCreated(
            workspaceResolver.resolve("stage-$stageIndex", stageIndex)
        )

        val effectiveOptions = shOptions.copy(workspaceRoot = workspacePath)
        // controlDir is sibling to workspace: {controlDirRoot}/{opId}
        val controlDir = derivedControlDirRoot.resolve(opId.format())

        return try {
            val config = DurableShConfig.fromSystemProperties()

            // Execute with tee-gated wrapper if captureStdout is enabled
            val result: DurableShellResult = if (effectiveOptions.captureStdout) {
                val executor = DurableShellExecutor()
                executor.execute(controlDir, step.command, opId.format(), effectiveOptions)
            } else {
                executeDurableShell(controlDir, step.command, opId.format(), config)
            }

            // Emit EchoOutputCaptured from jenkins-log.txt (stdout+stderr of the script)
            // L1 constraint: output.txt is only read when captureStdout=true
            try {
                val logFile = controlDir.resolve("jenkins-log.txt")
                if (Files.exists(logFile)) {
                    val capturedOutput = Files.readString(logFile)
                    eventSink.append(EchoOutputCaptured(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId,
                        sequence = 0L,
                        occurredAt = Instant.now(),
                        stepIndex = stepIndex,
                        content = capturedOutput,
                    ))
                }
            } catch (_: Exception) {
                // Don't fail the step if we can't read the log
            }

            when (result.state) {
                DurableShellState.COMPLETE -> {
                    if (result.exitCode != 0) "failure" else "success"
                }
                DurableShellState.TIMED_OUT -> {
                    // Timeout is terminal - return "failure"
                    "failure"
                }
                DurableShellState.LOST,
                DurableShellState.LAUNCH_FAILED,
                DurableShellState.LAUNCHING,
                DurableShellState.RUNNING -> "failure"
            }
        } catch (e: dev.rubentxu.pipeline.v2.sdk.runtime.durable.LinuxRequiredException) {
            // Fallback to non-durable on non-Linux
            val argv = listOf("bash", "-c", step.command)
            val result = sdkSh(StepContext(runId = runId), argv, eventSink, stepIndex)
            if (result.exitCode != 0) "failure" else "success"
        } catch (e: Exception) {
            "failure"
        }
    }

    /**
     * Executes a shell step within a parallel branch (W8 fold).
     *
     * This method replaces the 3-line bash -c block in walkBranchDurable.
     * Routes parallel Shell steps through the same durable execution path.
     *
     * @param stageIndex The stage index for workspace naming.
     * @param stepIndex The step index for event sequencing.
     * @param branchOpId The operation ID for this branch step.
     * @param runId The run identifier.
     * @param command The shell command to execute.
     * @param shOptions Shell execution options.
     * @param controlDirRoot The control directory root (explicit, not derived).
     * @param eventSink The event sink for emitting EchoOutputCaptured events.
     * @return "success" if exit code is 0, "failure" otherwise.
     */
    suspend fun executeBranchStep(
        stageIndex: Int,
        stepIndex: Int,
        branchOpId: OpId,
        runId: String,
        command: String,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path?,
        eventSink: EventSink,
    ): String {
        if (controlDirRoot == null) {
            // Non-durable fallback for branch steps
            val argv = listOf("bash", "-c", command)
            val result = sdkSh(StepContext(runId = runId), argv, eventSink, stepIndex)
            return if (result.exitCode != 0) "failure" else "success"
        }

        val workspaceResolver = WorkspaceResolver(controlDirRoot)
        val stageName = "stage-$stageIndex-branch"
        val workspacePath = workspaceResolver.ensureCreated(
            workspaceResolver.resolve(stageName, stageIndex)
        )

        val effectiveOptions = shOptions.copy(workspaceRoot = workspacePath)
        // controlDir is sibling to workspace: {controlDirRoot}/{branchOpId}
        val controlDir = controlDirRoot.resolve(branchOpId.format())

        return try {
            val config = DurableShConfig.fromSystemProperties()

            val result: DurableShellResult = if (effectiveOptions.captureStdout) {
                val executor = DurableShellExecutor()
                executor.execute(controlDir, command, branchOpId.format(), effectiveOptions)
            } else {
                executeDurableShell(controlDir, command, branchOpId.format(), config)
            }

            when (result.state) {
                DurableShellState.COMPLETE -> {
                    if (result.exitCode != 0) "failure" else "success"
                }
                DurableShellState.TIMED_OUT -> "failure"
                DurableShellState.LOST,
                DurableShellState.LAUNCH_FAILED,
                DurableShellState.LAUNCHING,
                DurableShellState.RUNNING -> "failure"
            }
        } catch (e: dev.rubentxu.pipeline.v2.sdk.runtime.durable.LinuxRequiredException) {
            val argv = listOf("bash", "-c", "")
            val result = sdkSh(StepContext(runId = runId), argv, eventSink, stepIndex)
            if (result.exitCode != 0) "failure" else "success"
        } catch (e: Exception) {
            "failure"
        }
    }

    /**
     * No-op event sink for shell execution where events are not persisted.
     */
    private object NoOpEventSink : EventSink {
        override fun append(event: dev.rubentxu.pipeline.v2.events.DomainEvent) {
            // no-op
        }

        override fun eventsFor(runId: String): Sequence<dev.rubentxu.pipeline.v2.events.DomainEvent> {
            return emptySequence()
        }
    }
}
