package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.StepOutcome
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
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EnvModel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import java.io.InputStream
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
     * WU-LPR-011: redacts observable transcript content when a secret registry
     * is active. Chunk-boundary-safe: a secret split across process output
     * chunks is still scrubbed (the registry's StreamingRedactor carries
     * boundary state across reads).
     */
    private fun redactTranscript(
        content: String,
        secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry?,
    ): String = if (secretPatternRegistry == null) content else {
        dev.rubentxu.pipeline.v2.credentials.api.TranscriptRedactor(secretPatternRegistry)
            .redactStream(content.byteInputStream())
    }

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
        secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    ): ShellInvocationResult {
        // Use explicit controlDirRoot if provided; null means non-durable fallback
        // (preserves base behavior: when PipelineOrchestrator has no controlDirRoot,
        // we fall back to direct bash -c which works without filesystem privileges)
        if (controlDirRoot == null) {
            // Non-durable fallback: script written to temp file; argv = [bash, <path>]
            // P2: env injected via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurableInvocation(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, opId.format(), controlDirRoot, secretPatternRegistry)
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

            // OBS-B: the composed destination for the child's bytes. This is the whole change — the
            // transcript is handed to the Output Plane by the pump WHILE the step runs, instead of
            // being written to a control-dir file and ingested once the step has returned.
            //
            // OBS-C2.3: one ingress per CHANNEL, each addressing its own channel-addressed stream.
            // The channel is part of the stream identity, so attribution survives a crash, a reopen
            // and a cursor hand-off with nothing extra to keep in step with the bytes. Declaring
            // both streams up front is what makes a crash between the first byte commit and the
            // first frame recoverable instead of lost — see OutputFrameIndex.declareStream.
            //
            // The handles are NOT opened here. `RedactingOutputIngress` opens and declares on its
            // first write of at least one byte, which keeps that recovery property (the first
            // reserve creates the first committed byte, and the stream is declared by then) while
            // stopping a step that printed nothing from leaving a stream behind. Declaring both
            // channels eagerly made every `sh` look like it produced console output, and in capture
            // mode it declared a STDOUT stream for a channel that is the typed value and must never
            // be part of the transcript.
            val ingressSinks: Map<ProcessOutputChannel, ProcessOutputSink> = controlDirRoot?.let { root ->
                val store = OutputPlaneProvider.storeForWriting(root)
                val streams = OutputPlaneProvider.streamsOf(runId, opId.format())
                buildMap {
                    ProcessOutputChannel.all.forEach { channel ->
                        val address = when (channel) {
                            ProcessOutputChannel.STDOUT -> streams.stdout
                            ProcessOutputChannel.STDERR -> streams.stderr
                        }
                        put(
                            channel,
                            RedactingOutputIngress(
                                { store.open(address.stream) },
                                store.frameIndex(),
                                address,
                            ) as ProcessOutputSink,
                        )
                    }
                }
            } ?: emptyMap()

            // Execute with tee-gated wrapper if captureStdout is enabled
            // P2: env injected via pb.environment().putAll (not argv) in DurableShellExecutor.launch()
            // Timeout threaded via timeoutMs parameter (TMO-S-013: 0 = no timeout)
            // workspaceRoot threaded via effectiveOptions.workspaceRoot (DEC-1 cwd flip)
            val terminal = DurableShellExecutor().executeTerminal(
                controlDir = controlDir,
                scriptContent = command.script,
                opId = opId.format(),
                shOptions = envOptions,
                // WU-LPR-011R2 (Gate-1 at-rest closure): the durable authority receives ONLY
                // already-redacted bytes. The wrap factory is built per launch; each
                // StreamingRedactor instance carries independent boundary state.
                transcriptRedactor = secretPatternRegistry?.let { registry ->
                    { raw -> dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor(registry).wrap(raw) }
                },
                // OBS-B: live destination, one per channel. Empty only when there is no control-dir root,
                // which is the non-durable fallback handled above.
                channelSinks = ingressSinks,
            )

            // Project the durable console transcript and the typed value separately.
            //   plain (returnMode != STDOUT): stdout and stderr are SEPARATE channel-addressed
            //     streams in the Output Plane. Neither is merged on the way in, so neither is
            //     merged on the way out: a consumer reads the one it wants, or reads both and
            //     interleaves them by OutputFrame.ordinal. No typed value.
            //   captureStdout (returnMode == STDOUT): stdout is the captured typed VALUE (output.txt,
            //     read as terminal.capturedStdout) and is NOT part of the transcript at all; the
            //     stderr stream is the observable transcript. The stdout value must NOT be re-emitted
            //     as a console event.
            // OBS-B: there is nothing left to ingest here, and that is the point.
            //
            // M1-P2 established that the transcript enters the Output Plane exactly once and that
            // no console event carries it. Before this it entered by a whole-file read AFTER the
            // step returned, which meant the store was authoritative but never live: a reader
            // tailing the stream saw nothing until the step ended, however long it ran.
            //
            // The pump now writes each sanitized chunk straight into the channel stream above, so:
            //   - `console.log` is not written, not read, and not deleted. It was a staging buffer
            //     whose lifetime was the reason the store could not be live.
            //   - post-mortem retention is no longer the existence of a file that a `finally`
            //     block happens to leave behind. It is the Output Plane's retention policy, which
            //     is where the other durable authorities already keep it.
            //
            // OBS-C2.3 added the per-channel split on top of that: there is no merged stream at all,
            // so a merged console is a read-side projection over two streams and the frame index
            // that orders them — never a third copy on disk.
            //
            // What did NOT change, and is checked above and by OutputSingleAuthorityFitnessTest:
            // redaction happens before persistence, the bytes land in one authority, and no
            // console event is emitted for them.
            // OBS-C3: the terminal is the only place that knows the operation is over, so it is the
            // only place a tail may be sealed. The pump ending is NOT that fact: when the JVM dies
            // mid-`sh`, the pump ends while the operation continues, and a resumed run re-attaches to
            // the same operation id and appends to these very streams. Sealing on pump close would
            // seal a stream that is legitimately about to grow again.
            //
            // Both channels are sealed because OBS-C2.3 gave the operation one stream per channel,
            // and a consumer that merged them must be able to see both reach their end.
            val terminalResult = classifyShellTerminal(terminal, command.returnMode)
            if (controlDirRoot != null) {
                runCatching {
                    val store = OutputPlaneProvider.storeForWriting(controlDirRoot)
                    val streams = OutputPlaneProvider.streamsOf(runId, opId.format())
                    streams.all.forEach { address -> store.seal(address.stream) }
                }.onFailure { failure ->
                    // A failure to seal does not invalidate the bytes already committed: the tail stays
                    // Open, which is the SAFE direction — a consumer keeps polling rather than
                    // declaring a finished run finished. Reporting it would be worse than the gap.
                    System.err.println(
                        "[ShExecution] could not seal the output tail of $runId/${opId.format()}: " +
                            "${failure.message}",
                    )
                }
            }
            terminalResult
        } catch (e: dev.rubentxu.pipeline.v2.sdk.runtime.durable.LinuxRequiredException) {
            // Non-durable fallback for non-Linux platforms
            // P2: script via temp file; env via pb.environment().putAll (WS-S-005)
            // JAVA_HOME/M2_HOME prepend applied via EnvModel.apply() (WS-S-006/WS-S-007)
            return executeNonDurableInvocation(command, EnvModel.apply(shOptions.env), eventSink, stepIndex, runId, opId.format(), controlDirRoot, secretPatternRegistry)
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
        workspaceBase: java.nio.file.Path? = null,
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

        val workspaceResolver = WorkspaceResolver(controlDirRoot, workspaceBase)
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
        secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
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

        // The NON-DURABLE fallback, and the one place where a console event is still correct.
        //
        // This path runs when there is no controlDirRoot at all — no filesystem privileges, or a
        // non-Linux host — so there is no Output Plane to write into. ADR-M1 D2 forbids a SECOND
        // authority over the same bytes; it does not require one to exist where none can. Here the
        // event is the only rendering, so it is not a second anything.
        //
        // The law the fitness test enforces is therefore per-execution, not per-type: "for any
        // one execution, the transcript bytes exist in exactly one place." A guard written as
        // "EchoOutputCaptured is never emitted" would have been wrong here and would have pushed
        // someone into deleting the only observable console this path has.
        //
        // WU-LPR-011: the observable content is redacted with the chunk-boundary-safe redactor
        // before emission, exactly as on the durable path.
        val output = stdoutBuilder.toString() + stderrBuilder.toString()
        val observableContent = redactTranscript(output, secretPatternRegistry)
        eventSink.append(
            EchoOutputCaptured(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                sequence = 0L,
                occurredAt = Instant.now(),
                stepIndex = stepIndex,
                content = observableContent,
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
