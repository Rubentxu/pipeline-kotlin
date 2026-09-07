package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.classifyShellTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.InterpreterPolicy
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EnvModel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
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
     * @param controlDirRoot The explicit control directory root (null = non-durable fallback).
     * @param eventSink The event sink for emitting EchoOutputCaptured events.
     * @return the closed shell invocation result. Lifecycle callers decide how
     * terminal failure and interruption affect their own run state.
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
    ): ShellInvocationResult = invokeShell(
        command = ShellCommand(
            script = step.command,
            returnMode = if (step.returnStdout) ShellReturnMode.STDOUT else ShellReturnMode.NONE,
        ),
        opId = opId,
        runId = runId,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        shOptions = shOptions,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    )

    /**
     * Executes a typed shell command without requiring a DSL step object.
     *
     * DEPRECATED (EM_DEAD_CODE_AUDIT A1): projects the typed result through a
     * lossy legacy [String] status. Callers must classify from
     * [invokeShell]'s closed semantic result instead; removal is scheduled
     * for EM-10 once no compatibility caller remains.
     */
    @Deprecated(
        message = "Legacy String status projection; use invokeShell() and classify the typed result",
        replaceWith = ReplaceWith("invokeShell(command, opId, runId, stageIndex, stepIndex, shOptions, controlDirRoot, eventSink)"),
    )
    suspend fun runShellCommand(
        command: ShellCommand,
        opId: OpId,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path?,
        eventSink: EventSink,
    ): String = invokeShell(
        command = command,
        opId = opId,
        runId = runId,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        shOptions = shOptions,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    ).toLegacyStatus(eventSink, runId, stepIndex)

    /**
     * Invokes the durable shell substrate and returns its closed semantic result.
     *
     * Compatibility callers may project this result through [runShellCommand],
     * but canonical callers must classify from this value rather than a string.
     */
    suspend fun invokeShell(
        command: ShellCommand,
        opId: OpId,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path?,
        eventSink: EventSink,
    ): ShellInvocationResult {
        // Use explicit controlDirRoot if provided; null means non-durable fallback
        // (preserves base behavior: when PipelineOrchestrator has no controlDirRoot,
        // we fall back to direct bash -c which works without filesystem privileges)
        if (controlDirRoot == null) {
            // Non-durable fallback: script written to temp file; argv = [bash, <path>]
            // P2: env injected via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurableInvocation(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, opId.format(), controlDirRoot)
        }

        // workspaceRoot from shOptions is set by PipelineRun.kt with the correct stageName → stageIndex mapping.
        // ShExecution just passes it through; no recomputation needed.
        // The SDK launch seam takes workspaceRoot as its process CWD. Preserve the
        // stage workspace while projecting a scoped dir context for this child only.
        val effectiveOptions = shOptions.copy(workspaceRoot = shOptions.workingDirectory ?: shOptions.workspaceRoot)
        // controlDir is sibling to workspace: {controlDirRoot}/{opId}
        val controlDir = controlDirRoot.resolve(opId.format())

        return try {
            // Apply EnvModel transformations (JAVA_HOME/M2_HOME prepend to PATH) (WS-S-006/WS-S-007)
            val envOptions = effectiveOptions.copy(
                env = EnvModel.apply(effectiveOptions.env),
                captureStdout = command.returnMode == ShellReturnMode.STDOUT,
            )

            // Execute with tee-gated wrapper if captureStdout is enabled
            // P2: env injected via pb.environment().putAll (not argv) in DurableShellExecutor.launch()
            // Timeout threaded via timeoutMs parameter (TMO-S-013: 0 = no timeout)
            // workspaceRoot threaded via effectiveOptions.workspaceRoot (DEC-1 cwd flip)
            val terminal = DurableShellExecutor().executeTerminal(
                controlDir = controlDir,
                scriptContent = command.script,
                opId = opId.format(),
                shOptions = envOptions,
            )

            // Emit EchoOutputCaptured. Two paths:
            //   1. captureStdout=true  → wrapper tees stdout to output.txt; executor reads it BEFORE cleanup
            //      and stores it in result.capturedStdout. jenkins-log.txt in that mode contains only stderr.
            //   2. captureStdout=false → wrapper writes stdout+stderr (2>&1) to jenkins-log.txt; cleanup
            //      stores it in the typed terminal. For a retained failure control
            //      directory, the log-file fallback preserves the existing event behavior.
            val capturedOutput: String = (terminal as? DurableTaskTerminal.Exited)?.output?.capturedStdout
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

            classifyShellTerminal(terminal, command.returnMode)
        } catch (e: dev.rubentxu.pipeline.v2.sdk.runtime.durable.LinuxRequiredException) {
            // Non-durable fallback for non-Linux platforms
            // P2: script via temp file; env via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurableInvocation(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, opId.format(), controlDirRoot)
        } catch (failure: EngineInvariantViolation) {
            throw failure
        } catch (e: Exception) {
            ShellInvocationResult.Failed(
                PipelineFailure(FailureKind.INFRASTRUCTURE, e.message ?: "core.sh could not execute"),
            )
        }
    }

    /** Maps the closed shell result to the lifecycle-only outcome contract. */
    suspend fun runShellCommandTyped(
        command: ShellCommand,
        opId: OpId,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path?,
        eventSink: EventSink,
    ): StepOutcome = invokeShell(
        command = command,
        opId = opId,
        runId = runId,
        stageIndex = stageIndex,
        stepIndex = stepIndex,
        shOptions = shOptions,
        controlDirRoot = controlDirRoot,
        eventSink = eventSink,
    ).toStepOutcome()

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
     * @param command The canonical shell command to execute.
     * @param shOptions Shell execution options.
     * @param controlDirRoot The control directory root (explicit, not derived).
     * @param eventSink The event sink for emitting EchoOutputCaptured events.
     * @return the closed shell invocation result. This preserves a timeout or
     * cancellation as [ShellInvocationResult.Interrupted] instead of
     * collapsing it into a failure string.
     */
    suspend fun executeBranchStep(
        stageIndex: Int,
        stepIndex: Int,
        branchOpId: OpId,
        runId: String,
        command: ShellCommand,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path?,
        eventSink: EventSink,
    ): ShellInvocationResult {
        if (controlDirRoot == null) {
            return invokeShell(
                command = command,
                opId = branchOpId,
                runId = runId,
                stageIndex = stageIndex,
                stepIndex = stepIndex,
                shOptions = shOptions,
                controlDirRoot = null,
                eventSink = eventSink,
            )
        }

        val workspaceResolver = WorkspaceResolver(controlDirRoot)
        val stageName = "stage-$stageIndex-branch"
        val workspacePath = workspaceResolver.ensureCreated(
            workspaceResolver.resolve(stageName, stageIndex)
        )

        val effectiveOptions = shOptions.copy(workspaceRoot = workspacePath)
        return invokeShell(
            command = command,
            opId = branchOpId,
            runId = runId,
            stageIndex = stageIndex,
            stepIndex = stepIndex,
            shOptions = effectiveOptions,
            controlDirRoot = controlDirRoot,
            eventSink = eventSink,
        )
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
    private suspend fun executeNonDurableInvocation(
        command: ShellCommand,
        env: Map<String, SecretHandle>,
        eventSink: EventSink,
        stepIndex: Int,
        runId: String,
        opId: String,
        controlDirRoot: Path?,
    ): ShellInvocationResult {
        // Env flows typed through the runtime boundary (M3 invariant: secret
        // bytes never escape SecretHandle here). The runtime materialises them
        // at the moment it hands them to the OS env block, and never persists
        // them. The legacy code coerced eagerly at pb.environment().putAll —
        // the runtime collapses that responsibility into one place.
        val controlDir: Path = try {
            controlDirRoot?.resolve(opId) ?: Files.createTempDirectory("pipeline-sh-non-durable")
        } catch (_: Exception) {
            return ShellInvocationResult.Failed(
                PipelineFailure(FailureKind.INFRASTRUCTURE, "core.sh could not create its control directory"),
            )
        }

        val runtime = ProcessDurableTaskRuntime(
            controlDir,
            object : Clock {
                override fun now(): Instant = Instant.now()
            },
        )
        val request = TaskExecutionRequest(
            task = TaskSpec.ShellScriptTask(
                script = command.script,
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
        } catch (failure: EngineInvariantViolation) {
            throw failure
        } catch (failure: Exception) {
            return ShellInvocationResult.Failed(
                PipelineFailure(FailureKind.INFRASTRUCTURE, failure.message ?: "core.sh could not execute"),
            )
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

        val terminal = when {
            result.timedOut -> DurableTaskTerminal.Cancelled(
                InterruptionRecord(
                    kind = InterruptionKind.TIMEOUT,
                    message = "core.sh timed out",
                    operationId = opId,
                ),
            )
            result.cancelled -> DurableTaskTerminal.Cancelled(
                InterruptionRecord(
                    kind = InterruptionKind.PARENT_CANCELLED,
                    message = "core.sh was cancelled",
                    operationId = opId,
                ),
            )
            else -> DurableTaskTerminal.Exited(
                exitCode = result.exitCode,
                output = DurableTaskOutput(controlDir.toString(), output),
            )
        }
        return classifyShellTerminal(terminal, command.returnMode)
    }

    private fun ShellInvocationResult.toStepOutcome(): StepOutcome = when (this) {
        ShellInvocationResult.UnitValue,
        is ShellInvocationResult.Stdout,
        is ShellInvocationResult.Status,
        -> StepOutcome.Success

        is ShellInvocationResult.Failed -> StepOutcome.Failure(failure)
        is ShellInvocationResult.Interrupted -> StepOutcome.Failure(
            PipelineFailure(FailureKind.TIMEOUT, interruption.message),
        )
    }

    private fun ShellInvocationResult.toLegacyStatus(
        eventSink: EventSink,
        runId: String,
        stepIndex: Int,
    ): String = when (this) {
        ShellInvocationResult.UnitValue,
        is ShellInvocationResult.Stdout,
        is ShellInvocationResult.Status,
        -> "success"

        is ShellInvocationResult.Failed -> {
            eventSink.append(
                StepFailed(
                    eventId = UUID.randomUUID().toString(),
                    runId = runId,
                    sequence = 0L,
                    occurredAt = Instant.now(),
                    stepIndex = stepIndex,
                    stepName = "sh",
                    stepType = "sh",
                    failureKind = failure.kind,
                    message = failure.message,
                ),
            )
            "failure"
        }

        is ShellInvocationResult.Interrupted -> if (interruption.kind == InterruptionKind.TIMEOUT) {
            "timeout"
        } else {
            "failure"
        }
    }
}
