package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.classifyShellTerminal
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskOutput
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.TaskTerminalDecision
import dev.rubentxu.pipeline.v2.domain.durable.decideTaskTerminal
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.InterpreterPolicy
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionKind
import dev.rubentxu.pipeline.v2.domain.durable.InterruptionRecord
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.durable.TaskStream
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.StepContext
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EnvModel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.OUTPUT_PERSISTENCE_REFUSED
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputChannel
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.ProcessDurableTaskRuntime
import dev.rubentxu.pipeline.v2.output.store.SealOutcome
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Shell execution orchestration for durable steps.
 * Extracted from PipelineRun.kt (god-file split)
 * Provides a single canonical implementation for shell step execution
 * that threads workspace and env options.
 * ## Responsibilities
 * - `invokeShell`: the ONE entry that runs a shell step, with durable semantics
 *  Invariant
 * User script content NEVER appears in any argv. The wrapper script is constructed
 * with single-quoted path variables only (no script content in argv).
 * Durable sh Pattern</a>
 * FAILED_TIMEOUT Terminal State</a>
*/
/**
 * Whether the terminal itself says the transcript could not be completed.
 * The runtime refuses to claim a complete transcript for exactly one reason and reports it
 * with exactly one code; the caller that must not seal reads the SAME constant from the SAME module
 * rather than matching a string literal. There is one producer of the fact and one consumer of it,
 * so a rename cannot leave half the contract behind.
 * Pure and therefore testable in isolation: it is a predicate over the terminal ADT, and pretending more
 * is needed to check it would mean inventing a process.
*/
internal fun terminalDeclaresTranscriptIncomplete(terminal: DurableTaskTerminal): Boolean =
    terminal is DurableTaskTerminal.LaunchFailed &&
        terminal.failure.code == OUTPUT_PERSISTENCE_REFUSED

object ShExecution {


    /**
     * Redacts observable transcript content when a secret registry
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
     * Invokes the durable shell substrate and returns its closed semantic result.
     * This is the ONE entry that runs a shell step: the control directory root is
     * not optional, so there is no second journey to choose between, and the caller
     * classifies from this value rather than from a projected string.
*/
    suspend fun invokeShell(
        command: ShellCommand,
        opId: OpId,
        runId: String,
        stageIndex: Int,
        stepIndex: Int,
        shOptions: ShOptions,
        controlDirRoot: java.nio.file.Path,
        eventSink: EventSink,
        secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
        // The run's output-wake signal. Each committed chunk announces on it
        // so the run's own live observer can read sooner than its poll
        // deadline. Null = nobody is listening; the announce is a no-op.
        outputWakeups: dev.rubentxu.pipeline.v2.application.observation.WakeupSignal? = null,
    ): ShellInvocationResult {
        // The control directory root is NOT optional: the run always has one, so there is
        // no journey to choose here and no branch that could silently pick a different
        // substrate for the same step.
        //
        // workspaceRoot from shOptions is set by PipelineRun.kt with the correct stageName → stageIndex mapping.
        // ShExecution just passes it through; no recomputation needed.
        // The SDK launch seam takes workspaceRoot as its process CWD. Preserve the
        // stage workspace while projecting a scoped dir context for this child only.
        val effectiveOptions = shOptions.copy(workspaceRoot = shOptions.workingDirectory ?: shOptions.workspaceRoot)
        // controlDir is sibling to workspace: {controlDirRoot}/{opId}
        val controlDir = controlDirRoot.resolve(opId.format())

        return try {
            // The environment is composed ONCE, and by the substrate that launches the
            // child: `ShellLaunchPreparation.composeEnvironment` applies the environment
            // model and merges the result with the ambient environment under the active
            // sandbox profile.
            //
            // Applying the model HERE as well applied it twice, and the model is not
            // idempotent: it prepends the toolchain directory to PATH on every call, so
            // the child saw `/opt/jdk/bin` TWICE in its PATH for the same declared
            // environment. The duplicate was invisible under the local profile -- which
            // rebuilds the path from the keep-set -- and visible under the default one,
            // which does not.
            //
            // What the model does NOT change is what reaches the child: the PATH+= entries
            // are consumed by the same call either way, a masked PATH is propagated as-is
            // either way, and an empty environment stays empty either way. The declared
            // environment is therefore handed over untouched, and the composition happens
            // once, at the boundary where the child is actually launched.
            val envOptions = effectiveOptions.copy(
                captureStdout = command.returnMode == ShellReturnMode.STDOUT,
            )

            // The composed destination for the child's bytes. This is the whole change — the
            // transcript is handed to the Output Plane by the pump WHILE the step runs, instead of
            // being written to a control-dir file and ingested once the step has returned.
            // One ingress per CHANNEL, each addressing its own channel-addressed stream.
            // The channel is part of the stream identity, so attribution survives a crash, a reopen
            // and a cursor hand-off with nothing extra to keep in step with the bytes. Declaring
            // both streams up front is what makes a crash between the first byte commit and the
            // first frame recoverable instead of lost — see OutputFrameIndex.declareStream.
            // The handles are NOT opened here. `RedactingOutputIngress` opens and declares on its
            // first write of at least one byte, which keeps that recovery property (the first
            // reserve creates the first committed byte, and the stream is declared by then) while
            // stopping a step that printed nothing from leaving a stream behind. Declaring both
            // channels eagerly made every `sh` look like it produced console output, and in capture
            // mode it declared a STDOUT stream for a channel that is the typed value and must never
            // be part of the transcript.
            // The control directory root always exists, so the durable sinks
            // always exist too: there is no conditional admission left to
            // describe.
            val ingressSinks: Map<ProcessOutputChannel, ProcessOutputSink> = run {
                val store = OutputPlaneProvider.storeForWriting(controlDirRoot)
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
                                afterChunkCommitted = { outputWakeups?.signal() },
                            ) as ProcessOutputSink,
                        )
                    }
                }
            }

            // Execute with tee-gated wrapper if captureStdout is enabled
            // Env injected via pb.environment.putAll (not argv) in DurableShellExecutor.launch
            // Timeout threaded via timeoutMs parameter (: 0 = no timeout)
            // WorkspaceRoot threaded via effectiveOptions.workspaceRoot (cwd flip)
            // When the caller opts into the agent path
            // (`pumpedViaAgent = true` in ShOptions), build a DurableShConfig
            // that flips the same flag and carries the secret the agent uses
            // for in-memory redaction. The default config
            // (`pumpedViaAgent = false`) preserves the existing in-JVM pump
            // behaviour.
            val durableConfig = if (effectiveOptions.pumpedViaAgent) {
                DurableShConfig.fromSystemProperties().copy(
                    pumpedViaAgent = true,
                    runId = runId,
                    agentSecret = effectiveOptions.agentSecret,
                )
            } else {
                DurableShConfig.fromSystemProperties()
            }
            val terminal = DurableShellExecutor().executeTerminal(
                controlDir = controlDir,
                scriptContent = command.script,
                opId = opId.format(),
                shOptions = envOptions,
                config = durableConfig,
                // The durable authority receives ONLY
                // already-redacted bytes. The wrap factory is built per launch; each
                // StreamingRedactor instance carries independent boundary state.
                transcriptRedactor = secretPatternRegistry?.let { registry ->
                    { raw -> dev.rubentxu.pipeline.v2.credentials.api.StreamingRedactor(registry).wrap(raw) }
                },
                // Live destination, one per channel. The control directory root
                // always exists, so these sinks always exist too.
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
            // There is nothing left to ingest here, and that is the point.
            // Established that the transcript enters the Output Plane exactly once and that
            // no console event carries it. Before this it entered by a whole-file read AFTER the
            // step returned, which meant the store was authoritative but never live: a reader
            // tailing the stream saw nothing until the step ended, however long it ran.
            // The pump now writes each sanitized chunk straight into the channel stream above, so:
            //   - `console.log` is not written, not read, and not deleted. It was a staging buffer
            //     whose lifetime was the reason the store could not be live.
            //   - post-mortem retention is no longer the existence of a file that a `finally`
            //     block happens to leave behind. It is the Output Plane's retention policy, which
            //     is where the other durable authorities already keep it.
            // 3 added the per-channel split on top of that: there is no merged stream at all
            // so a merged console is a read-side projection over two streams and the frame index
            // that orders them — never a third copy on disk.
            // What did NOT change, and is checked above:
            // redaction happens before persistence, the bytes land in one authority, and no
            // console event is emitted for them.
            // The terminal is the only place that knows the operation is over, so it is the
            // only place a tail may be sealed. The pump ending is NOT that fact: when the JVM dies
            // mid-`sh`, the pump ends while the operation continues, and a resumed run re-attaches to
            // the same operation id and appends to these very streams. Sealing on pump close would
            // seal a stream that is legitimately about to grow again.
            // Both channels are sealed because.3 gave the operation one stream per channel
            // and a consumer that merged them must be able to see both reach their end.
            // Per-channel seal. A stdout-only script (e.g. `sh("echo hi")`)
            // never opened stderr; sealing it would be a "no seal" outcome, not a
            // warning. The [OutputSealPort.seal] return type makes the four cases
            // explicit, and the per-channel loop below is the place that decides
            // which of them surfaces to the user.
            val terminalResult = classifyShellTerminal(terminal, command.returnMode)
            // The control directory root always exists, so the operation tail
            // always has a place to be sealed in.
            // A seal is the durable statement "this operation's output is complete", and it is
            // the statement `console --follow` terminates on. So it MUST NOT be made for a
            // transcript the runtime has just declared it could not complete: a sink that lost a
            // write, or a pump still holding the read end at the drain budget. Sealing there would
            // tell every downstream reader that a short, ordered, plausible transcript is the final
            // one — which is the exact artifact this whole path exists to refuse.
            // The terminal fact is still known and still reported (the step fails with an explicit
            // INFRASTRUCTURE cause). What is withheld is the completeness claim, and only that.
            if (!terminalDeclaresTranscriptIncomplete(terminal)) {
                val sealFailure: PipelineFailure? = sealOperationTail(controlDirRoot, runId, opId.format())
                sealFailure?.let { failure ->
                    return ShellInvocationResult.Failed(failure)
                }
            }
            terminalResult
        } catch (e: dev.rubentxu.pipeline.v2.sdk.runtime.durable.LinuxRequiredException) {
            // The only route left is the platform one: the durable-shell
            // substrate requires a Linux host, and this host is not Linux.
            // It is a DIFFERENT SUBSTRATE (the generic process runtime), not
            // a second choice for the same step, and it is the ONLY way
            // `core.sh` runs on a non-Linux host — macOS is a supported
            // platform, so this route is live, not a legacy leftover.
            // Script via temp file; env via pb.environment.putAll
            // JAVA_HOME/_HOME prepend applied via EnvModel.apply
            return executeNonLinuxInvocation(
                command = command,
                env = EnvModel.apply(shOptions.env),
                eventSink = eventSink,
                stepIndex = stepIndex,
                runId = runId,
                opId = opId.format(),
                controlDirRoot = controlDirRoot,
                timeoutMs = shOptions.timeoutMs,
                secretPatternRegistry = secretPatternRegistry,
            )
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // A CancellationException is an execution mechanism, not a failure.
            // Mapping it to Failed(INFRASTRUCTURE) would make "the run was cancelled"
            // indistinguishable from "the shell substrate broke". Rethrow unchanged.
            throw ce
        } catch (failure: EngineInvariantViolation) {
            throw failure
        } catch (e: Exception) {
            ShellInvocationResult.Failed(
                PipelineFailure(FailureKind.INFRASTRUCTURE, e.message ?: "core.sh could not execute"),
            )
        }
    }

    /**
     * Whether the terminal itself says the transcript could not be completed.
     * The runtime refuses to claim a complete transcript for exactly one reason, and it
     * reports it with exactly one code; the caller that must not seal reads the SAME constant from
     * the SAME module rather than matching a string literal. There is one production of the fact and
     * one consumer of it, so a rename cannot leave half of the contract behind.
*/

    /**
     * Seals the operation's per-channel output tails and decides which
     * outcomes are surfaced.
     * :
     *  - [SealOutcome.Sealed] / [SealOutcome.AlreadySealed] — silent.
     *  - [SealOutcome.NeverOpened] — silent. A stdout-only script never
     *    opened stderr, and a stderr-only script never opened stdout; that
     *    is a legitimate absence, not a warning.
     *  - [SealOutcome.Failure] — surfaces as a [PipelineFailure] of kind
     *    INFRASTRUCTURE with the failure cause verbatim. The terminal is
     *    reclassified so a downstream consumer sees the seal failure
     *    rather than the (potentially-successful) shell exit.
     * The previous shape printed "[ShExecution] could not seal the output
     * tail..." to stderr for every kind of failure including legitimate
     * absence (the user's "stream not opened" noise). The new shape is
     * silent on the legitimate paths and only surfaces real failures,
     * which is what a healthy `pipelinek run` was missing.
*/
    private fun sealOperationTail(
        controlDirRoot: java.nio.file.Path,
        runId: String,
        opId: String,
    ): PipelineFailure? {
        val store = OutputPlaneProvider.storeForWriting(controlDirRoot)
        val streams = OutputPlaneProvider.streamsOf(runId, opId)
        var firstFailure: PipelineFailure? = null
        for (address in streams.all) {
            when (val outcome = store.seal(address.stream)) {
                is SealOutcome.Sealed,
                is SealOutcome.AlreadySealed,
                is SealOutcome.NeverOpened,
                -> Unit
                is SealOutcome.Failure -> {
                    if (firstFailure == null) {
                        firstFailure = PipelineFailure(
                            FailureKind.INFRASTRUCTURE,
                            "could not seal the output tail of $runId/$opId on " +
                                "${address.stream.value}: ${outcome.cause.message ?: outcome.cause.toString()}",
                        )
                    }
                }
            }
        }
        return firstFailure
    }

    /**
     * Executes a shell step via the generic process task runtime, on a host
     * where the durable-shell substrate cannot run.
     *
     * The durable-shell substrate requires Linux (`requireLinuxHost`); on a
     * non-Linux host this is the ONLY way `core.sh` executes, and macOS is a
     * supported platform of this product. So this is not a legacy leftover
     * and it is not a second choice for the same step: it is a different
     * SUBSTRATE for a different PLATFORM.
     *
     * It owns the script-file lifecycle (write, executable perms, delete), the
     * process tree, the chunked stdout/stderr streams, and the durable result
     * — leaving this method responsible only for opaque env coercion and event
     * emission. Behaviour parity with the legacy path: (env injected via env
     * map, NOT argv), best-effort fail-closed one [EchoOutputCaptured] per step
     * with the full script output.
     * @param command The canonical shell command to execute.
     * @param env Environment variables (SecretHandle values coerced at the runtime boundary).
     * @param eventSink Event sink for EchoOutputCaptured emission.
     * @param stepIndex The step index for event sequencing.
     * @param runId The run identifier (string form — typed RunId built at the request boundary).
     * @param opId Operation ID used for the runtime control dir and the task request.
     * @param controlDirRoot The control directory root of the run. It always
     *   exists: the runtime's own control directory is a per-operation
     *   subdirectory of it, exactly as on the durable route, so no scratch
     *   directory is created and none has to be deleted.
     * @return "success" if exit code is 0, "failure" otherwise.
*/
    private suspend fun executeNonLinuxInvocation(
        command: ShellCommand,
        env: Map<String, SecretHandle>,
        eventSink: EventSink,
        stepIndex: Int,
        runId: String,
        opId: String,
        controlDirRoot: Path,
        timeoutMs: Long?,
        secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    ): ShellInvocationResult {
        // The caller's budget must reach the runtime. `0` means "no
        // Timeout" on BOTH routes (the durable route, DurableShellExecutor:
        // `timeoutMs > 0` arms the watchdog); a negative budget is not a mode this route can
        // honour, so it is refused explicitly instead of being silently dropped.
        val effectiveTimeoutMs = when {
            timeoutMs == null || timeoutMs == 0L -> null
            timeoutMs > 0L -> timeoutMs
            else -> return ShellInvocationResult.Failed(
                PipelineFailure(
                    FailureKind.INFRASTRUCTURE,
                    "core.sh timeoutMs must be positive or null/0 for no timeout: $timeoutMs",
                ),
            )
        }

        // Env flows typed through the runtime boundary (invariant: secret
        // bytes never escape SecretHandle here). The runtime materialises them
        // at the moment it hands them to the OS env block, and never persists
        // them. The legacy code coerced eagerly at pb.environment.putAll —
        // the runtime collapses that responsibility into one place.
        // The control directory root always exists, so the runtime's own
        // control directory is a per-operation subdirectory of the run's root,
        // exactly as on the durable route. This route creates nothing outside
        // that root and therefore deletes nothing.
        val controlDir: Path = try {
            controlDirRoot.resolve(opId)
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
            timeoutMs = effectiveTimeoutMs,
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
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // A cancellation is an execution mechanism, not a failure. The
            // runtime already recorded its own durable CANCELLED terminal; mapping
            // it to a Failed(INFRASTRUCTURE) value is the forbidden conversion.
            throw ce
        } catch (failure: EngineInvariantViolation) {
            throw failure
        } catch (failure: Exception) {
            return ShellInvocationResult.Failed(
                PipelineFailure(FailureKind.INFRASTRUCTURE, failure.message ?: "core.sh could not execute"),
            )
        }

        // The fallback, and the one place where a console event is still correct
        // This path runs when the durable-shell substrate cannot run — it requires a Linux host —
        // and NOT because the control directory root is missing: the root exists here exactly as it
        // does on the durable route, and the runtime's own control directory is a per-operation
        // subdirectory of it. What this route does NOT have is an Output Plane sink: the durable
        // route hands the redactor to the executor, which streams the transcript into the store
        // while the step runs, whereas here the runtime's chunks are accumulated and published as
        // one event. The Output Plane forbids a SECOND
        // authority over the same bytes; it does not require one to exist where none can. Here the
        // event is the only rendering, so it is not a second anything.
        // The law is therefore per-execution, not per-type: "for any
        // one execution, the transcript bytes exist in exactly one place." A guard written as
        // "EchoOutputCaptured is never emitted" would have been wrong here and would have pushed
        // someone into deleting the only observable console this path has.
        // The observable content is redacted with the chunk-boundary-safe redactor
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

        val terminal = when (val decision = decideTaskTerminal(result)) {
            is TaskTerminalDecision.TimedOut -> DurableTaskTerminal.Cancelled(
                InterruptionRecord(
                    kind = InterruptionKind.TIMEOUT,
                    message = "core.sh timed out",
                    operationId = opId,
                ),
            )
            is TaskTerminalDecision.Cancelled -> DurableTaskTerminal.Cancelled(
                InterruptionRecord(
                    kind = InterruptionKind.PARENT_CANCELLED,
                    message = "core.sh was cancelled",
                    operationId = opId,
                ),
            )
            is TaskTerminalDecision.Exited -> DurableTaskTerminal.Exited(
                exitCode = decision.exitCode,
                output = DurableTaskOutput(controlDir.toString(), output),
            )
        }
        return classifyShellTerminal(terminal, command.returnMode)
    }
}
