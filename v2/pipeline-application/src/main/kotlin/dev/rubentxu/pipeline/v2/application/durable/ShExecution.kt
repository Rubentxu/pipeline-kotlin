package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.InterpreterPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellResult
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellState
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EnvModel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.executeDurableShell
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.sdk.runtime.sh as sdkSh
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Typed shell command accepted by the durable execution spine. */
data class DurableShellCommand(val script: String)

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
    ): String = runShellCommand(
        command = DurableShellCommand(step.command),
        opId = opId,
        runId = runId,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        shOptions = shOptions,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    )

    /** Executes a typed shell command without requiring a DSL step object. */
    suspend fun runShellCommand(
        command: DurableShellCommand,
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
            return executeNonDurable(command.script, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, opId.format(), controlDirRoot)
        }

        // workspaceRoot from shOptions is set by PipelineRun.kt with the correct stageName → stageIndex mapping.
        // ShExecution just passes it through; no recomputation needed.
        val effectiveOptions = shOptions
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
                executor.execute(controlDir, command.script, opId.format(), envOptions)
            } else {
                executeDurableShell(controlDir, command.script, opId.format(), config, envOptions.timeoutMs ?: 0L, envOptions.env, effectiveOptions.sandbox, effectiveOptions.workspaceRoot)
            }

            // Emit EchoOutputCaptured. Two paths:
            //   1. captureStdout=true  → wrapper tees stdout to output.txt; executor reads it BEFORE cleanup
            //      and stores it in result.capturedStdout. jenkins-log.txt in that mode contains only stderr.
            //   2. captureStdout=false → wrapper writes stdout+stderr (2>&1) to jenkins-log.txt; cleanup
            //      deletes the control dir on success BEFORE we get here, so we read result.capturedStdout
            //      which executeDurableShell leaves null when captureStdout=false. As a fallback we try
            //      to read jenkins-log.txt if it still exists (e.g. cleanupRetainOnFailure on error).
            val capturedOutput: String = result.capturedStdout
                ?: try {
                    val logFile = controlDir.resolve("jenkins-log.txt")
                    if (Files.exists(logFile)) Files.readString(logFile) else ""
                } catch (_: Exception) {
                    ""
                }
            if (capturedOutput.isNotEmpty()) {
                eventSink.append(EchoOutputCaptured(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stepIndex = stepIndex,
                    content = capturedOutput,
                ))
            }

            when (result.state) {
                DurableShellState.COMPLETE -> {
                    if (result.exitCode != 0) {
                        // Emit StepFailed for non-zero exit (INC-R8-ARC-001)
                        val message = "sh exited with code ${result.exitCode}" +
                            if (capturedOutput.isNotBlank()) ": ${capturedOutput.take(256)}" else ""
                        eventSink.append(StepFailed(
                            eventId = UUID.randomUUID().toString(),
                            runId = runId,
                            sequence = 0L,
                            occurredAt = Instant.now(),
                            stepIndex = stepIndex,
                            stepName = "sh",
                            stepType = "sh",
                            failureKind = FailureKind.SCRIPT,
                            message = message,
                        ))
                        "failure"
                    } else "success"
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
            return executeNonDurable(command.script, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, opId.format(), controlDirRoot)
        } catch (e: Exception) {
            "failure"
        }
    }

    /**
     * C2: Structured failure mapping with additive compatibility.
     * Executes a typed shell command and returns StepOutcome with proper failure kinds.
     *
     * Maps durable shell terminal states to typed failures:
     * - COMPLETE + exit 0 → Success
     * - COMPLETE + exit ≠ 0 → Failure(SCRIPT)
     * - TIMED_OUT → Failure(TIMEOUT)
     * - LOST / LAUNCH_FAILED → Failure(INFRASTRUCTURE)
     * - LAUNCHING / RUNNING → Failure(SCHEMA)
     *
     * @return StepOutcome typed outcome for the shell command
     */
    suspend fun runShellCommandTyped(
        command: DurableShellCommand,
        opId: OpId,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path?,
        eventSink: EventSink,
    ): StepOutcome {
        val resultString = runShellCommand(command, opId, runId, stageIndex, stepIndex, shOptions, controlDirRoot, eventSink)
        return when (resultString) {
            "success" -> StepOutcome.Success
            "timeout" -> StepOutcome.Failure(
                PipelineFailure(FailureKind.TIMEOUT, "core.sh timed out for '${opId.format()}'")
            )
            else -> StepOutcome.Failure(
                PipelineFailure(FailureKind.SCRIPT, "core.sh failed for '${opId.format()}'")
            )
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
            return executeNonDurable(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, branchOpId.format(), null)
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
            return executeNonDurable(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, branchOpId.format(), controlDirRoot)
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
     * Executes a shell step via the M3 task runtime (non-durable fallback).
     *
     * Replaces the legacy direct `ProcessBuilder("bash", scriptPath)` path with
     * [ProcessDurableTaskRuntime] + [TaskSpec.ShellScriptTask]. The runtime owns
     * the script-file lifecycle (write, executable perms, delete), the process
     * tree, the chunked stdout/stderr streams, and the durable result — leaving
     * this method responsible only for opaque env coercion and event emission.
     *
     * Used when the durable-shell sandbox refuses to launch (e.g. non-Linux) or
     * when no [controlDirRoot] is provided. Behaviour parity with the legacy
     * path: P2 (env injected via env map, NOT argv), best-effort fail-closed,
     * one [EchoOutputCaptured] per step with the full script output.
     *
     * @param scriptContent The shell script content.
     * @param env Environment variables (SecretHandle values coerced at the runtime boundary).
     * @param eventSink Event sink for EchoOutputCaptured and StepFailed emission.
     * @param stepIndex The step index for event sequencing.
     * @param runId The run identifier (string form — typed RunId built at the request boundary).
     * @param opId Operation ID used for the runtime control dir and the task request.
     * @param controlDirRoot Optional stable control dir root; null = best-effort temp dir.
     * @return "success" if exit code is 0, "failure" otherwise.
     */
    private suspend fun executeNonDurable(
        scriptContent: String,
        env: Map<String, SecretHandle>,
        eventSink: EventSink,
        stepIndex: Int,
        runId: String,
        opId: String,
        controlDirRoot: Path?,
    ): String {
        // Env flows typed through the runtime boundary (M3 invariant: secret
        // bytes never escape SecretHandle here). The runtime materialises them
        // at the moment it hands them to the OS env block, and never persists
        // them. The legacy code coerced eagerly at pb.environment().putAll —
        // the runtime collapses that responsibility into one place.
        val controlDir: Path = try {
            controlDirRoot?.resolve(opId) ?: Files.createTempDirectory("pipeline-sh-non-durable")
        } catch (_: Exception) {
            return "failure"
        }

        val runtime = ProcessDurableTaskRuntime(
            controlDir,
            object : Clock {
                override fun now(): Instant = Instant.now()
            },
        )
        val request = TaskExecutionRequest(
            task = TaskSpec.ShellScriptTask(
                script = scriptContent,
                interpreter = InterpreterPolicy.BASH,
            ),
            runId = RunId(runId),
            opId = opId,
            timeoutMs = null,
            env = env,
        )

        // Accumulate chunks into one EchoOutputCaptured (matches legacy behaviour
        // — one event per step, full script output). O(chunk) memory at the sink.
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        val sink = ExecutionOutputSink { chunk ->
            when (chunk.stream) {
                TaskStream.STDOUT -> stdoutBuilder.append(String(chunk.data, Charsets.UTF_8))
                TaskStream.STDERR -> stderrBuilder.append(String(chunk.data, Charsets.UTF_8))
            }
        }

        val result = try {
            runtime.execute(request, sink)
        } catch (_: Exception) {
            return "failure"
        }

        // stdout + stderr merged into the single EchoOutputCaptured (matches
        // the legacy readText() which read merged process output). Tests assert
        // a single event per step.
        val output = stdoutBuilder.toString() + stderrBuilder.toString()
        eventSink.append(
            EchoOutputCaptured(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                stepIndex = stepIndex,
                content = output,
            ),
        )

        if (result.exitCode != 0 || result.timedOut) {
            val message = if (result.timedOut) "sh timed out"
            else "sh exited with code ${result.exitCode}" +
                if (output.isNotBlank()) ": ${output.take(256)}" else ""
            eventSink.append(
                StepFailed(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stepIndex = stepIndex,
                    stepName = "sh",
                    stepType = "sh",
                    failureKind = FailureKind.SCRIPT,
                    message = message,
                ),
            )
        }

        return when {
            result.timedOut -> "failure"
            result.exitCode != 0 -> "failure"
            else -> "success"
        }
    }
}
