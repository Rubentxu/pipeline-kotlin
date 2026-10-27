package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.util.concurrent.TimeUnit

/**
 * The rendezvous between the durable child and the ingest agent.
 *
 * ## What this owns
 *
 * The POLICY of the rendezvous: the FIFOs exist, the agent is attached, and the child is safe to
 * start — or, when any of that fails, nothing of the rendezvous is left open. The EFFECTS are the
 * host's ([RendezvousHost]), and the split is what lets the policy be observed by an instrument
 * that needs neither a real FIFO, nor a real agent JVM, nor a real clock: the agent path has no
 * executable scenario in this tree, so an instrument that can drive it with fakes is the only one
 * available for it.
 *
 * ## The contract is public because the agent is in another module
 *
 * Two processes meet here, and they live in two modules: the runtime launches the agent, and the
 * agent reads the argv it was handed. The values they agree on — the argv's shape, the marker the
 * agent writes, the type the FIFO must have, the variable that carries the secret — are therefore a
 * CONTRACT, and a contract only one side can see is a contract the two sides can drift on.
 *
 * The dependency runs the right way for that: `pipeline-application` already depends on
 * `pipeline-step-sdk:runtime`, so the contract can live in the runtime and the agent can read it
 * there. The other direction would be a cycle.
 *
 * ## What this replaces
 *
 * Before this cut each side carried its own copy of the agreement:
 *
 *  - the runtime built the argv with a private builder, and the agent read it by position. The
 *    agent also published an `argv` helper whose KDoc claimed the launcher used it, so the two
 *    could not drift. It did not use it: the helper had ZERO readers, and the launcher had its
 *    own builder;
 *  - the FIFO type predicate existed twice, once per module, as two copies of the same
 *    security invariant;
 *  - the agent published a `READY_CONTENT` that nothing read, and wrote the marker with an
 *    inline literal instead.
 *
 * The shared [Args] replaces all of it: one value, one writer ([Args.toArgv]) and one reader
 * ([Args.fromArgv]), with the positions stated once.
 *
 * ## The secret does not travel in the argv
 *
 * The agent registers a secret so it can redact it from the child's bytes. A command-line
 * argument is readable through `/proc/<pid>/cmdline` by EVERY user on the host — measured on
 * the reference host as mode 444, with no `hidepid` — so a secret in the argv is a secret
 * handed to every user of the machine. The secret therefore travels in the agent's
 * ENVIRONMENT, which `/proc/<pid>/environ` exposes to its owner alone (mode 400), under
 * [AGENT_SECRET_ENV]. The argv carries identifiers and paths, and nothing that must stay
 * secret.
 *
 * ## A failed rendezvous leaves nothing of its own open
 *
 * The warm-up descriptors are the FIFOs' only writers besides the child, and the agent reads
 * until EOF. A rendezvous that fails without releasing them leaves two descriptors open in this
 * JVM and — because the agent never sees the EOF it is waiting for — an agent JVM alive with
 * nothing owning it, waiting for a transcript that will never grow. The failed attach would
 * leave an orphan behind every time it happened.
 *
 * So the policy is: what the rendezvous acquires, it releases when the rendezvous does not
 * complete. The descriptors go first, because closing them is what lets the agent see EOF; the
 * agent is killed afterwards, because a release that leaves a process running has not released
 * anything.
 */
class AgentRendezvous internal constructor(
    opId: String,
    config: DurableShConfig,
    private val host: RendezvousHost = ProcessRendezvousHost(opId, config),
) {

    /**
     * The values the runtime hands to the agent, and the values the agent reads back.
     *
     * The shape is stated ONCE: [toArgv] writes the positions and [fromArgv] reads them, so
     * the two cannot disagree about what the third position means. Before this cut the writer
     * and the reader were two separate lists in two modules, and nothing checked that they
     * still agreed.
     *
     * @property controlDirRoot The root that holds the control directory.
     * @property runId The run the streams are keyed by.
     * @property opId The operation within the run.
     * @property fifoDir The directory holding `stdout.fifo` and `stderr.fifo`.
     * @property readyMarker The file the agent touches once both FIFOs are open.
     */
    data class Args(
        val controlDirRoot: Path,
        val runId: String,
        val opId: String,
        val fifoDir: Path,
        val readyMarker: Path,
    ) {

        /** The argv, in the order the agent reads it. */
        fun toArgv(): Array<String> = arrayOf(
            controlDirRoot.toString(),
            runId,
            opId,
            fifoDir.toString(),
            readyMarker.toString(),
        )

        companion object {

            /** How many arguments the argv carries. */
            const val ARGUMENT_COUNT: Int = 5

            /**
             * The values an argv carries.
             *
             * The count is checked BEFORE the positions are read. An argv with a missing value
             * would otherwise be read as a shorter argv, and the agent would drain files that
             * belong to another operation.
             */
            fun fromArgv(argv: Array<String>): Args {
                require(argv.size == ARGUMENT_COUNT) {
                    "the agent's argv carries ${argv.size} arguments; $ARGUMENT_COUNT were expected"
                }
                return Args(
                    controlDirRoot = Path.of(argv[0]),
                    runId = argv[1],
                    opId = argv[2],
                    fifoDir = Path.of(argv[3]),
                    readyMarker = Path.of(argv[4]),
                )
            }
        }
    }

    /**
     * The rendezvous: the FIFOs exist, the agent is attached, and the child is safe to start.
     *
     * The order is the agent's: create the FIFOs, hold them open so the agent's read-only open
     * does not block, launch the agent, and wait for its ready marker. Only then may the child
     * start, because opening a FIFO for write blocks until a reader appears.
     *
     * When any step fails, everything the rendezvous acquired is released before the failure is
     * reported. The release is not a courtesy: the warm-up descriptors are the FIFOs' only
     * writers besides the child, so an attach that failed without releasing them leaves the agent
     * waiting for an EOF that will never arrive.
     *
     * @param protocol The control directory's layout.
     * @return The warm-up descriptors, which are the only writers on the FIFOs until the child
     *   writes to them.
     */
    internal fun rendezvous(protocol: ShellProtocolLayout): List<Closeable> {
        val fifoDir = host.materialiseFifos(protocol)
        val warmup = host.openWarmup(protocol)
        var agent: Process? = null
        try {
            val launched = host.launchAgent(protocol, fifoDir)
            agent = launched
            host.awaitReady(protocol, launched)
        } catch (failure: Throwable) {
            releaseFailedRendezvous(warmup, agent)
            throw failure
        }
        return warmup
    }

    /**
     * Releases what a failed rendezvous acquired, and nothing else.
     *
     * Two things must not survive a failed attach:
     *
     *  - the warm-up descriptors, which are the FIFOs' only writers besides the child. The agent
     *    reads until EOF, and EOF comes when every writer is gone: a descriptor left open keeps
     *    the agent waiting for a transcript that will not grow, and leaks a descriptor for the
     *    life of this JVM;
     *  - the agent process, when it is still alive. It holds the FIFOs' read ends, and with the
     *    warm-up closed it would drain an empty pipe and leave — but only if it gets that far. A
     *    forced kill is what makes "nothing of ours is left open" true rather than likely.
     *
     * The descriptors go first, because closing them is what releases the agent's read ends; the
     * agent is killed afterwards, because a release that leaves a process running has not
     * released anything.
     *
     * Neither step's failure stops the other: a descriptor that refuses to close must not leave
     * the agent running, and an agent that refuses to die must not leave the descriptors open.
     */
    private fun releaseFailedRendezvous(warmup: List<Closeable>, agent: Process?) {
        warmup.asReversed().forEach { descriptor ->
            try {
                descriptor.close()
            } catch (_: Throwable) {
                // Deliberate: the next descriptor is released anyway, and the agent is killed
                // either way. A failure to release one must not change the verdict of the
                // failure that caused the release.
            }
        }
        agent?.let { process -> terminateAgent(process) }
    }

    /**
     * Kills an agent that outlived its rendezvous, and waits, bounded, for it to be gone.
     *
     * The kill is forced: the agent is a separate session, and a polite request would leave a
     * process whose only purpose was to drain FIFOs nobody will write to. The wait is bounded so
     * a stuck agent cannot hold the failure hostage, and a wait that times out does not replace
     * the failure that caused the teardown: the failure is the verdict, and the agent's fate is
     * a fact about the teardown.
     */
    private fun terminateAgent(process: Process) {
        try {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            process.waitFor(AGENT_TERMINATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Deliberate: the failure that caused the teardown is the one that is reported.
        }
    }

    companion object {

        /**
         * Fully-qualified entry point of the production ingest agent. The runtime launches this
         * class as a separate JVM when [DurableShConfig.pumpedViaAgent] is `true`. The name is
         * a string the runtime owns, to keep `pipeline-step-sdk:runtime` free of a backward
         * dependency on `pipeline-application`.
         */
        const val AGENT_MAIN_CLASS: String =
            "dev.rubentxu.pipeline.v2.application.durable.ingest.ObsPc2IngestAgent"

        /**
         * The text the agent writes into the ready marker, so "the agent is attached" is an
         * event and not a sleep.
         */
        const val READY_CONTENT: String = "ready\n"

        /**
         * Bounded wait for the agent's ready marker. The agent's class init + `mkfifo` + the
         * marker's write is sub-second in practice; 10s is a generous upper bound that also
         * surfaces a crashed agent JVM as a typed error rather than an infinite wait.
         */
        const val AGENT_READY_TIMEOUT_MILLIS: Long = 10_000L

        /**
         * Bounded wait for a killed agent to be gone.
         *
         * The wait exists so "the agent was released" is a fact rather than a request that was
         * sent. It is a bound, not a promise: a wait that times out leaves the failure that
         * caused the teardown as the verdict.
         */
        const val AGENT_TERMINATION_TIMEOUT_MILLIS: Long = 5_000L

        /**
         * The environment variable that carries the agent's secret.
         *
         * The argv cannot carry it: a command-line argument is readable through
         * `/proc/<pid>/cmdline` by every user on the host.
         */
        const val AGENT_SECRET_ENV: String = "PIPELINEK_AGENT_SECRET"

        /**
         * Whether [path] is a named pipe (POSIX FIFO).
         *
         * `java.nio.file` exposes no direct FIFO test, but
         * [PosixFileAttributes.isOther] answers it exactly: a FIFO is neither a regular file nor
         * a directory, which is precisely what `isOther` means. Asking the JDK keeps this check
         * in the same trust domain as the read that follows it.
         *
         * The predicate fails CLOSED: a path that cannot be read is not a FIFO.
         */
        fun isFifo(path: Path): Boolean = runCatching {
            Files.readAttributes(path, PosixFileAttributes::class.java).isOther
        }.getOrDefault(false)

        /**
         * The root that holds the control directory.
         *
         * The control dir is `<root>/<opId>`, NOT `<root>/<runId>/<opId>`: the hierarchy the
         * agent needs to recover is exactly one level above the control dir. An earlier
         * implementation walked one level too high, putting the agent on the temp root while
         * the test read from the temp root's `control` child — the two stores never overlapped
         * and every "committed N bytes / reader saw 0" row was silent.
         */
        fun controlDirRootOf(controlDir: Path): Path = controlDir.parent
            ?: error("controlDir $controlDir does not have the expected <root>/<opId> shape")

        /**
         * The environment the agent is launched with: the secret, and nothing else.
         */
        fun agentEnvironment(secret: String): Map<String, String> = mapOf(AGENT_SECRET_ENV to secret)

        /**
         * The secret the agent reads from its environment.
         *
         * An absent or empty secret is a refusal, not a default: an agent that redacts nothing
         * would write the child's raw bytes to the Output Plane.
         */
        fun secretFromEnvironment(environment: Map<String, String>): String =
            environment[AGENT_SECRET_ENV]?.takeIf { it.isNotEmpty() }
                ?: error("the agent's secret is not in the environment under $AGENT_SECRET_ENV")
    }
}

/**
 * The rendezvous's effects on the host.
 *
 * The policy that OWNS what a rendezvous acquires — and releases it when the rendezvous fails —
 * is the guarantee, and a policy that can only be exercised against a real FIFO, a real agent JVM
 * and a real clock has no instrument: the agent path has no executable scenario in this tree.
 * The seam is what lets the policy be driven with fakes, so the guarantee is observed rather than
 * asserted.
 */
internal interface RendezvousHost {

    /**
     * Creates the FIFO rendezvous under `controlDir/fifos/`.
     */
    fun materialiseFifos(protocol: ShellProtocolLayout): Path

    /**
     * Opens the warm-up pair of read-write descriptors.
     */
    fun openWarmup(protocol: ShellProtocolLayout): List<Closeable>

    /**
     * Launches the ingest agent as a separate JVM that owns the FIFO read ends.
     */
    fun launchAgent(protocol: ShellProtocolLayout, fifoDir: Path): Process

    /**
     * Blocks until the agent's ready marker exists, or refuses.
     */
    fun awaitReady(protocol: ShellProtocolLayout, agent: Process)
}
