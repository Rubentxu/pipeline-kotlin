package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellResult
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellState
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EnvModel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.executeDurableShell
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.sh as sdkSh
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
     * @param controlDirRoot The explicit control directory root (null = non-durable fallback).
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
        controlDirRoot: java.nio.file.Path?,
        eventSink: EventSink,
    ): String {
        // Use explicit controlDirRoot if provided; null means non-durable fallback
        // (preserves base behavior: when PipelineOrchestrator has no controlDirRoot,
        // we fall back to direct bash -c which works without filesystem privileges)
        if (controlDirRoot == null) {
            // Non-durable fallback: script written to temp file; argv = [bash, <path>]
            // P2: env injected via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurable(step.command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId)
        }

        val workspaceResolver = WorkspaceResolver(controlDirRoot)
        val workspacePath = workspaceResolver.ensureCreated(
            workspaceResolver.resolve("stage-$stageIndex", stageIndex)
        )

        val effectiveOptions = shOptions.copy(workspaceRoot = workspacePath)
        // controlDir is sibling to workspace: {controlDirRoot}/{opId}
        val controlDir = controlDirRoot.resolve(opId.format())

        return try {
            val config = DurableShConfig.fromSystemProperties()

            // Apply EnvModel transformations (JAVA_HOME/M2_HOME prepend to PATH) (WS-S-006/WS-S-007)
            val envOptions = effectiveOptions.copy(env = EnvModel.apply(effectiveOptions.env))

            // Execute with tee-gated wrapper if captureStdout is enabled
            // P2: env injected via pb.environment().putAll (not argv) in DurableShellExecutor.launch()
            // Timeout threaded via timeoutMs parameter (TMO-S-013: 0 = no timeout)
            // workspaceRoot threaded via effectiveOptions.workspaceRoot (DEC-1 cwd flip)
            val result: DurableShellResult = if (envOptions.captureStdout) {
                val executor = DurableShellExecutor()
                executor.execute(controlDir, step.command, opId.format(), envOptions)
            } else {
                executeDurableShell(controlDir, step.command, opId.format(), config, envOptions.timeoutMs ?: 0L, envOptions.env, effectiveOptions.sandbox, effectiveOptions.workspaceRoot)
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
                    // TMO-S-001: timeout is terminal - distinct from plain failure
                    "timeout"
                }
                DurableShellState.LOST,
                DurableShellState.LAUNCH_FAILED,
                DurableShellState.LAUNCHING,
                DurableShellState.RUNNING -> "failure"
            }
        } catch (e: dev.rubentxu.pipeline.v2.sdk.runtime.durable.LinuxRequiredException) {
            // Non-durable fallback for non-Linux platforms
            // P2: script via temp file; env via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurable(step.command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId)
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
            // P2: script via temp file; env via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurable(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId)
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

            // Apply EnvModel transformations (JAVA_HOME/M2_HOME prepend to PATH) (WS-S-006/WS-S-007)
            val envOptions = effectiveOptions.copy(env = EnvModel.apply(effectiveOptions.env))

            val result: DurableShellResult = if (envOptions.captureStdout) {
                val executor = DurableShellExecutor()
                executor.execute(controlDir, command, branchOpId.format(), envOptions)
            } else {
                // P2: env via pb.environment().putAll; timeout via timeoutMs parameter
                executeDurableShell(controlDir, command, branchOpId.format(), config, envOptions.timeoutMs ?: 0L, envOptions.env)
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
            // Non-durable fallback for non-Linux platforms
            // P2: script via temp file; env via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurable(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId)
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

    /**
     * Executes a script via a temporary script file (P2-compliant).
     *
     * Script content is written to a temp file; argv contains only the file path.
     * Env is injected via [ProcessBuilder.environment] (P2: env via env map, NOT argv).
     * Best-effort temp file cleanup after execution.
     *
     * @param scriptContent The shell script content.
     * @param env Environment variables to inject via pb.environment().putAll.
     * @param eventSink Event sink for EchoOutputCaptured emission.
     * @param stepIndex The step index for event sequencing.
     * @param runId The run identifier.
     * @return "success" if exit code is 0, "failure" otherwise.
     */
    private fun executeNonDurable(
        scriptContent: String,
        env: Map<String, SecretHandle>,
        eventSink: EventSink,
        stepIndex: Int,
        runId: String,
    ): String {
        val scriptPath: Path = try {
            Files.createTempFile("pipeline-sh-", ".sh")
        } catch (_: Exception) {
            // Fallback: cannot create temp file → fail
            return "failure"
        }
        try {
            // Write script content to temp file
            Files.writeString(scriptPath, scriptContent)
            // Set executable permissions (owner read+write+execute)
            Files.setPosixFilePermissions(
                scriptPath,
                PosixFilePermissions.fromString("rwx------")
            )
            // Execute: bash <script-path>
            val pb = ProcessBuilder("bash", scriptPath.toString())
            // P2: env injected via pb.environment().putAll (WS-S-005)
            // T2: env is Map<String, SecretHandle>; coerce at pb.environment() choke
            if (env.isNotEmpty()) {
                val coercedEnv: Map<String, String> = env.mapValues { it.value.materialize() }
                pb.environment().putAll(coercedEnv)
            }
            val process = pb.start()
            // Read stdout from the process's input stream
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            // Emit EchoOutputCaptured (matching durable path behavior)
            eventSink.append(EchoOutputCaptured(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                stepIndex = stepIndex,
                content = output,
            ))
            return if (exitCode != 0) "failure" else "success"
        } catch (_: Exception) {
            return "failure"
        } finally {
            // Best-effort cleanup
            try {
                Files.deleteIfExists(scriptPath)
            } catch (_: Exception) {
                // Ignore cleanup failures
            }
        }
    }
}
