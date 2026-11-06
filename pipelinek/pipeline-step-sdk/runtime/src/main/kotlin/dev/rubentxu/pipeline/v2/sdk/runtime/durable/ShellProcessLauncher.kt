package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where one of the child's two output descriptors goes.
 *
 * Each case names the FILE it points at, because "where does the stderr go" is only answerable
 * with a path: a typed value polluted by stderr is a different defect from a transcript written
 * twice, and neither is distinguishable from the other without the path.
 */
internal sealed interface ChildDescriptorTarget {

    /** The typed value file: the stdout the execution asked to capture. */
    data class ValueFile(val file: Path) : ChildDescriptorTarget

    /** The console transcript: the file a reader streams the child's bytes from. */
    data class TranscriptFile(val file: Path) : ChildDescriptorTarget

    /** A pipe the runtime drains itself, one pump per channel. */
    data object Pipe : ChildDescriptorTarget

    /** A FIFO of the ingest agent, which owns the read end. */
    data class AgentFifo(val file: Path) : ChildDescriptorTarget
}

/**
 * The routing of the child's two output descriptors.
 *
 * @property stdout Where the child's stdout goes.
 * @property stderr Where the child's stderr goes.
 */
internal data class ChildDescriptorPlan(
    val stdout: ChildDescriptorTarget,
    val stderr: ChildDescriptorTarget,
)

/**
 * Launches one durable shell child and returns its identity.
 *
 * ## What this owns
 *
 * The LAUNCH: the argv, the working directory, the environment, the stdin redirect, and the
 * routing of the child's two output descriptors. Nothing else. The watchdog, the terminal decision
 * and the cleanup are other components and are not here.
 *
 * ## This is a process-creation site, and it is not the port's adapter
 *
 * The product reaches for a process in FOUR places, and this is one of them: the durable shell's
 * child. It is NOT the adapter of `DurableTaskRuntime`, and it does not travel through that port.
 * The port is for GENERIC TASKS — an argv or a script executed as a task — while the durable shell
 * has a protocol of its own: a wrapper argv, a session of its own, a transcript with its own
 * grammar and a `result.txt` whose contents mean something else entirely. The two families even
 * write the same file names with different grammars, so they cannot share a reader; that is what
 * [ShellProtocolLayout] exists to say.
 *
 * What is therefore THIS class's own:
 *
 *  - the argv carries the wrapper and nothing else (the rule below);
 *  - the child leads its own session, so the unit of termination is the session;
 *  - the routing of the child's two output descriptors is a pure function of the composition.
 *
 * What is NOT this class's, and lives with the mechanism that really implements it: the DEADLINE
 * and the termination are [ShellWatchdog]'s; the verdict on the transcript is [TranscriptDrain]'s;
 * the names of the protocol are [ShellProtocolLayout]'s.
 *
 * ## The two rules this states
 *
 * **The argv is the wrapper and nothing else.** `setsid`, `bash`, and the wrapper's path. The
 * user's script never reaches the process arguments: it is written to a file and the wrapper
 * refers to it by path. An argument is readable through `/proc/<pid>/cmdline` by every user on
 * the host, so a script that reached this list would be readable by all of them.
 *
 * **The descriptors are routed by a table, and the table is a value.** Where the child's stdout
 * and stderr go depends on two facts — whether the stdout is captured, and whether the bytes are
 * pumped through the JVM — and the routing is decided by a pure function. That makes the answer
 * readable, and checkable, as a rule, instead of only observable by starting a child and looking
 * at the files it left behind.
 *
 * ## What this does NOT own
 *
 * The protocol's names belong to [ShellProtocolLayout]; the script and the environment belong to
 * [ShellLaunchPreparation]; the launch's identity belongs to [LaunchedShell]; the verdict on the
 * transcript belongs to [TranscriptDrain]. The agent's rendezvous is not a component yet, so it
 * arrives here as a callback: this class knows WHEN the agent owns the descriptors, and not what
 * the agent is.
 */
internal class ShellProcessLauncher(
    private val opId: String,
    private val config: DurableShConfig,
) {

    /**
     * Starts the child and returns the launch: the child AND the transcript state that belongs to
     * it.
     *
     * @param controlDir The control directory. Created when it does not exist.
     * @param scriptContent The user's shell script.
     * @param request What the execution asked for.
     * @param transcriptRedactor The wrap factory applied to EACH piped stream BEFORE any byte
     *   reaches its sink. Null means an explicit composition with no redaction.
     * @param channelSinks One sink per channel. A missing sink means "pump it to the control-dir
     *   file", never "drop it": a byte with no destination must not become a missing byte.
     * @param attachRendezvous The agent's rendezvous. It is invoked ONLY when the agent owns the
     *   child's descriptors, and BEFORE the child starts, because opening a FIFO for write blocks
     *   until a reader appears. It returns the descriptors the runtime holds open until the child
     *   has finished.
     * @return The launch.
     */
    fun launch(
        controlDir: Path,
        scriptContent: String,
        request: DurableShellExecutionRequest,
        transcriptRedactor: ((InputStream) -> InputStream)?,
        channelSinks: Map<ProcessOutputChannel, ProcessOutputSink>,
        attachRendezvous: (ShellProtocolLayout) -> List<Closeable>,
    ): LaunchedShell {
        requireLinuxHost()

        // Create control directory structure
        Files.createDirectories(controlDir)

        val protocol = ShellProtocolLayout(controlDir)
        val preparation = ShellLaunchPreparation(
            protocol = protocol,
            opId = opId,
            heartbeatCheckInterval = config.heartbeatCheckInterval,
            pumpedViaAgent = config.pumpedViaAgent,
            confinement = confinementFor(request, controlDir),
        )
        preparation.materialise(scriptContent)

        val logFile = protocol.consoleLog

        // Jenkins-faithful launch: setsid creates a new session+process group.
        // argv = [setsid, bash, wrapper.sh] — no -c, no inline content in argv.
        // setsid EXECS in place when its caller is not already a process group leader, and a
        // JVM's child never is one: it inherits the JVM's group and receives a fresh pid, so it
        // can never lead that group. The ProcessHandle therefore IS the wrapper, which is the
        // session leader, and the wrapper's death and its exit code are the wrapper's own.
        // The argv already demands the tool the detachment needs, so a host without it fails
        // this launch with this launch's own refusal, and never reaches a probe.
        val builder = ProcessBuilder(shellLaunchArgv(protocol.wrapperFile))
        builder.directory((request.workspaceRoot ?: controlDir).toFile())

        // The child's environment is composed by the preparation, from a snapshot of the
        // launcher's own environment. The launcher then adopts the composed map wholesale, so the
        // child sees exactly what the preparation decided and nothing else.
        val ambient = builder.environment().toMap()
        builder.environment().clear()
        builder.environment().putAll(
            preparation.composeEnvironment(
                ambient = ambient,
                sandbox = request.sandbox,
                userEnv = request.env,
            ),
        )

        // The stdin of a durable child is /dev/null: a child that waited for input would wait
        // forever, because there is nobody there to give it any.
        builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        val pumpedThroughJvm = pumpsThroughJvm(transcriptRedactor != null, channelSinks.size)
        val agentOwns = agentOwnsDescriptors(config.pumpedViaAgent, pumpedThroughJvm)
        val plan = planChildDescriptors(
            protocol = protocol,
            captureStdout = request.captureStdout,
            pumpedThroughJvm = pumpedThroughJvm,
            agentOwns = agentOwns,
        )

        // Pre-create console.log so external observers always see the transcript file, and so a
        // fallback sink owns the ONLY writer handle from the first byte. When a composed sink
        // exists this file is not written at all, and creating it would only leave an empty
        // second copy of a transcript that lives somewhere else.
        if (!pumpedThroughJvm && !request.captureStdout) {
            Files.newOutputStream(logFile).use { /* create/truncate */ }
        }

        // The agent's rendezvous happens BEFORE the child starts. It is OUTSIDE the try below, so
        // a failure to attach surfaces as the agent's own error and not as a wrapped launch
        // failure: the two are different facts and the caller reads them differently.
        val warmup = if (agentOwns) attachRendezvous(protocol) else emptyList()

        return try {
            builder.redirectOutput(redirectFor(plan.stdout))
            builder.redirectError(redirectFor(plan.stderr))

            val process = builder.start()

            // Drain the redirected streams through the redactor into the composed sinks on daemon
            // pumps. Redaction happens BEFORE persistence: at any instant the durable authority
            // contains only sanitized bytes, and a JVM crash can leave at most a partial SANITIZED
            // transcript. The typed value channel (output.txt) is unaffected: capture-mode stdout
            // remains an exact redirect (typed values are never scrubbed).
            var transcriptPumps: List<TranscriptPumpView> = emptyList()
            var transcriptSinks: List<ProcessOutputSink> = emptyList()
            if (pumpedThroughJvm && !config.pumpedViaAgent) {
                // When the agent path is active, the runtime does NOT start its per-channel pump
                // threads: the agent JVM owns the FIFO read ends and is the single durable
                // authority for the transcript.
                val (startedPumps, startedSinks) = startTranscriptPumps(
                    process = process,
                    captureStdout = request.captureStdout,
                    transcriptRedactor = transcriptRedactor,
                    channelSinks = channelSinks,
                    fallbackLogFile = logFile,
                )
                transcriptPumps = startedPumps
                transcriptSinks = startedSinks
            }

            LaunchedShell(
                handle = process.toHandle(),
                exitCode = ShellExitCode { process.exitCodeOrNull() },
                transcriptPumps = transcriptPumps,
                transcriptSinks = transcriptSinks,
                agentWarmup = warmup,
            )
        } catch (e: Exception) {
            // The agent's FIFOs were opened BEFORE the child started, and their warm-up descriptors
            // are the only writers on them. If the child never started, nobody else will close
            // them: the caller never receives a launch, so it cannot close them either, and the
            // agent would wait for an EOF that never comes.
            warmup.forEach { runCatching { it.close() } }
            throw IllegalStateException(launchRefusalMessage(opId), e)
        }
    }

    /**
     * The confinement this request asks for, or null when it asks for none.
     *
     * The confined profile needs two things this host either has or does not: the tool
     * that applies the boundary, and a workspace to confine. Both are checked HERE, at
     * the one place where the launch is built, so a run that cannot be confined fails
     * before the child exists instead of running unconfined.
     *
     * The control directory is part of the confinement, and it is part of it READ-ONLY:
     * the script the run is about to execute lives there, and a run that cannot see it
     * does not run at all. The exit code the run publishes is written by the wrapper,
     * which runs OUTSIDE the boundary, so the run cannot forge its own verdict.
     *
     * The admission core has already refused a request this host cannot honour, so
     * this is the second line and not the first: it exists so that a host that loses
     * the tool between the decision and the launch fails closed.
     */
    private fun confinementFor(
        request: DurableShellExecutionRequest,
        controlDir: Path,
    ): SandboxConfinement? {
        if (request.sandbox.profile != SandboxProfile.CONFINED) return null
        val workspace = request.workspaceRoot
            ?: throw SandboxProfileUnsupportedException(
                "the confined profile confines a workspace, and this run has none",
            )
        val binary = SandboxConfinement.locate()
            ?: throw SandboxProfileUnsupportedException(
                "the confined profile needs '${SandboxConfinement.TOOL}', " +
                    "which this host does not have",
            )
        return SandboxConfinement.forRun(binary, workspace, controlDir)
    }

    /**
     * The redirect a planned descriptor becomes.
     *
     * The three file cases differ in WHICH file they name, not in how they are applied: every one
     * of them is an exact redirect, and the pipe is the only case where the runtime owns the read
     * end.
     */
    private fun redirectFor(target: ChildDescriptorTarget): ProcessBuilder.Redirect = when (target) {
        is ChildDescriptorTarget.ValueFile -> ProcessBuilder.Redirect.to(target.file.toFile())
        is ChildDescriptorTarget.TranscriptFile -> ProcessBuilder.Redirect.to(target.file.toFile())
        ChildDescriptorTarget.Pipe -> ProcessBuilder.Redirect.PIPE
        is ChildDescriptorTarget.AgentFifo -> ProcessBuilder.Redirect.to(target.file.toFile())
    }

    /**
     * Starts the per-channel transcript pumps for [process] and returns them alongside their
     * [GuardedProcessOutputSink]s.
     *
     * The shape of the loop is one thread per channel, writing through the [GuardedProcessOutputSink]
     * so every `Throwable` becomes a [ProcessOutputRefusal] on the same channel. The capture-mode
     * case pipes only stderr because stdout is an exact redirect to the typed-value file; the
     * plain-mode case pipes both. The `pumpedViaAgent` case never reaches here because the agent
     * JVM owns the FIFO read ends.
     */
    private fun startTranscriptPumps(
        process: Process,
        captureStdout: Boolean,
        transcriptRedactor: ((InputStream) -> InputStream)?,
        channelSinks: Map<ProcessOutputChannel, ProcessOutputSink>,
        fallbackLogFile: Path,
    ): Pair<List<TranscriptPumpView>, List<ProcessOutputSink>> {
        val pumpedChannels: Map<ProcessOutputChannel, InputStream> =
            if (captureStdout) {
                mapOf(ProcessOutputChannel.STDERR to process.errorStream)
            } else {
                mapOf(
                    ProcessOutputChannel.STDOUT to process.inputStream,
                    ProcessOutputChannel.STDERR to process.errorStream,
                )
            }

        val pumps = mutableListOf<TranscriptPumpView>()
        val sinks = mutableListOf<ProcessOutputSink>()

        for ((channel, raw) in pumpedChannels) {
            val redacted = transcriptRedactor?.invoke(raw) ?: raw
            val target: ProcessOutputSink = channelSinks[channel]
                ?: StreamProcessOutputSink(
                    java.io.BufferedOutputStream(
                        Files.newOutputStream(fallbackLogFile, java.nio.file.StandardOpenOption.APPEND),
                    ),
                )
            val guarded = GuardedProcessOutputSink(target)
            // The flag is the pump's attention. A pump that is writing to the output plane is
            // WORKING, even while the write is slow; a pump that is waiting for the child is
            // waiting for bytes nobody is going to write. That difference is what the drain reads,
            // and it is the difference between a verdict about the child and a verdict about the
            // machine.
            val waitingForInput = java.util.concurrent.atomic.AtomicBoolean(true)
            val pump = Thread {
                val window = ByteArray(TRANSCRIPT_LIVE_WINDOW_BYTES)
                try {
                    while (true) {
                        // The pump's read sizing: read directly from the redacting stream with the
                        // whole window, and let it block until at least one byte is available, then
                        // return as many as the source can give. The loop only ever consults
                        // `redacted.read`, which is the canonical "have at least one byte or block"
                        // primitive the `RedactingInputStream` already implements.
                        waitingForInput.set(true)
                        val n = redacted.read(window, 0, window.size)
                        if (n < 0) break
                        waitingForInput.set(false)
                        guarded.write(window, 0, n)
                    }
                } catch (t: Throwable) {
                    guarded.recordPumpFailure("read", t)
                } finally {
                    try {
                        redacted.close()
                    } catch (t: Throwable) {
                        guarded.recordPumpFailure("redaction-close", t)
                    }
                    guarded.close()
                }
            }
            pump.isDaemon = true
            pump.name = "durable-sh-transcript-pump-$opId-${channel.name.lowercase()}"
            pump.start()
            pumps += ThreadTranscriptPump(pump, waitingForInput)
            sinks += guarded
        }
        return pumps to sinks
    }

    internal companion object {

        /**
         * The argv of the durable child: [setsid], [bash] and the wrapper's path.
         *
         * The user's script never reaches the arguments. It is written to a file, and the wrapper
         * refers to it by path, so the only thing an observer of `/proc/<pid>/cmdline` learns is
         * which wrapper ran.
         */
        fun shellLaunchArgv(wrapperFile: Path): List<String> =
            listOf("setsid", "bash", wrapperFile.toString())

        /**
         * The refusal a launch that could not start the child publishes.
         *
         * It names the operation and says that the launch failed, and nothing more: the CAUSE
         * carries the host's own refusal — the tool that is not there, the shell that is not
         * there — and the cause is what a reader needs. The message is a value so that the
         * refusal's identity is checkable without launching a child on a host that cannot launch
         * one.
         */
        fun launchRefusalMessage(opId: String): String =
            "Failed to launch durable shell for $opId"

        /**
         * Whether the child's bytes travel through the JVM.
         *
         * "Pumped" means "there is a per-channel destination", which is what makes one pump per
         * channel possible at all. A redaction alone is not enough: a piped but unredacted
         * composition still needs one writer per channel, otherwise the same file would have two
         * writers of the same bytes.
         */
        fun pumpsThroughJvm(hasRedactor: Boolean, channelSinkCount: Int): Boolean =
            hasRedactor || channelSinkCount > 0

        /**
         * Whether the ingest agent owns the child's descriptors.
         *
         * The agent owns them only when it is the one pumping. With nothing pumped there are no
         * descriptors for it to own, and the bytes go to the transcript file.
         */
        fun agentOwnsDescriptors(pumpedViaAgent: Boolean, pumpedThroughJvm: Boolean): Boolean =
            pumpedViaAgent && pumpedThroughJvm

        /**
         * Whether the capture and the ingest agent cannot both hold the stdout.
         *
         * They cannot, and the reason is the routing: when the agent owns the descriptors the
         * stdout goes to its FIFO, so the typed value file has no writer at all. The two modes
         * are not two ways of doing the same thing — the capture asks for the EXACT bytes as a
         * typed value, and the agent asks for the SANITIZED bytes as the durable transcript — and
         * the stdout cannot be both at once.
         *
         * The fact lives HERE, with the routing it is read from, so the admission and the routing
         * cannot disagree: what this refuses is exactly what would have lost the value. An
         * admission that lived anywhere else would be a second copy of the rule, and two copies
         * of a rule are two authorities.
         *
         * @param captureStdout Whether the child's stdout is captured as the step's typed value.
         * @param pumpedViaAgent Whether the bytes are pumped through the ingest agent.
         * @param pumpedThroughJvm Whether the bytes travel through the JVM.
         * @return Whether the two modes are incompatible.
         */
        fun captureIsIncompatibleWithTheAgent(
            captureStdout: Boolean,
            pumpedViaAgent: Boolean,
            pumpedThroughJvm: Boolean,
        ): Boolean = captureStdout && agentOwnsDescriptors(pumpedViaAgent, pumpedThroughJvm)

        /**
         * The routing of the child's two output descriptors.
         *
         * The rules, in order:
         *
         *  - the agent owns both channels when it is the one pumping;
         *  - a captured stdout goes to the typed value file, and the stderr to a pipe when the
         *    bytes are pumped and to the transcript otherwise. The typed value is never polluted by
         *    the stderr, and the two channels are never fused into one descriptor;
         *  - without a captured stdout, both channels are piped when the bytes are pumped, and both
         *    go to the transcript otherwise. One file is honest about the fact that this
         *    composition does not separate them.
         *
         * @param protocol The control directory's layout: the names the plan points at.
         * @param captureStdout Whether the child's stdout is captured as the step's typed value.
         * @param pumpedThroughJvm Whether the bytes travel through the JVM.
         * @param agentOwns Whether the ingest agent owns the descriptors.
         * @return Where each channel goes.
         */
        fun planChildDescriptors(
            protocol: ShellProtocolLayout,
            captureStdout: Boolean,
            pumpedThroughJvm: Boolean,
            agentOwns: Boolean,
        ): ChildDescriptorPlan = when {
            agentOwns -> ChildDescriptorPlan(
                stdout = ChildDescriptorTarget.AgentFifo(protocol.stdoutFifo),
                stderr = ChildDescriptorTarget.AgentFifo(protocol.stderrFifo),
            )
            captureStdout && pumpedThroughJvm -> ChildDescriptorPlan(
                stdout = ChildDescriptorTarget.ValueFile(protocol.outputFile),
                stderr = ChildDescriptorTarget.Pipe,
            )
            captureStdout -> ChildDescriptorPlan(
                stdout = ChildDescriptorTarget.ValueFile(protocol.outputFile),
                stderr = ChildDescriptorTarget.TranscriptFile(protocol.consoleLog),
            )
            pumpedThroughJvm -> ChildDescriptorPlan(
                stdout = ChildDescriptorTarget.Pipe,
                stderr = ChildDescriptorTarget.Pipe,
            )
            else -> ChildDescriptorPlan(
                stdout = ChildDescriptorTarget.TranscriptFile(protocol.consoleLog),
                stderr = ChildDescriptorTarget.TranscriptFile(protocol.consoleLog),
            )
        }
    }
}
