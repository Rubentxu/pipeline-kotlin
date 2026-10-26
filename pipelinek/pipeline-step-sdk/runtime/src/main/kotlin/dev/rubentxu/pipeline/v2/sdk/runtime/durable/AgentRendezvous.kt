package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/**
 * The rendezvous between the durable child and the ingest agent.
 *
 * ## What this owns
 *
 * The FIFOs, the agent's launch, and the readiness the runtime waits for before the child is
 * allowed to write to them. Nothing else: the CHILD's launch is [ShellProcessLauncher]'s, and
 * the agent's own draining is the agent's.
 *
 * ## The contract is public because the agent is in another module
 *
 * Two processes meet here, and they live in two modules: the runtime launches the agent, and
 * the agent reads the argv it was handed. The values they agree on — the argv's shape, the
 * marker the agent writes, the type the FIFO must have, the variable that carries the secret —
 * are therefore a CONTRACT, and a contract only one side can see is a contract the two sides
 * can drift on.
 *
 * The dependency runs the right way for that: `pipeline-application` already depends on
 * `pipeline-step-sdk:runtime`, so the contract can live in the runtime and the agent can read
 * it there. The other direction would be a cycle.
 *
 * ## What this replaces
 *
 * Before this cut each side carried its own copy of the agreement:
 *
 *  - the runtime built the argv with a private `buildAgentArgv`, and the agent read it by
 *    position. The agent also published an `argv` helper whose KDoc claimed the launcher used
 *    it, so the two could not drift. It did not use it: the helper had ZERO readers, and the
 *    launcher had its own builder;
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
 */
class AgentRendezvous internal constructor(
    private val opId: String,
    private val config: DurableShConfig,
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
     * @param protocol The control directory's layout.
     * @return The warm-up descriptors, which are the only writers on the FIFOs until the child
     *   writes to them.
     */
    internal fun rendezvous(protocol: ShellProtocolLayout): List<Closeable> {
        val fifoDir = materialiseFifos(protocol)
        val warmup = openWarmup(protocol)
        val agent = launchAgent(protocol, fifoDir)
        awaitReady(protocol, agent)
        return warmup
    }

    /**
     * Creates the FIFO rendezvous under `controlDir/fifos/`. The directory and the FIFOs are
     * owned by the runtime; the child writes to them, the agent reads from them.
     *
     * `java.nio.file` cannot create a FIFO, so we shell out to `mkfifo`. The mkfifo binary is
     * the standard coreutils command and is present on every POSIX host the durable shell
     * already requires (`LinuxRequiredException`).
     *
     * `mkfifo` defaults to `0666 & ~umask`, which on a typical umask 022 host is 0644 —
     * world-readable. The child's raw bytes flow through the FIFO before redaction, so a
     * world-readable FIFO is a plaintext-spool exposure (the same threat class the Output Plane
     * excludes). We chmod 0600 immediately after `mkfifo` and assert the type is a named pipe,
     * so a pre-existing regular file or symlink at the path is rejected rather than silently
     * accepted as a spool.
     */
    private fun materialiseFifos(protocol: ShellProtocolLayout): Path {
        val fifoDir = protocol.fifoDir
        Files.createDirectories(fifoDir)
        // Lock down the dir too — only the owner may traverse it, so even if the FIFO perms
        // were wider, the path itself cannot be reached by another user.
        Files.setPosixFilePermissions(
            fifoDir,
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val mkfifo = ProcessBuilder(
            "mkfifo",
            ShellProtocolLayout.STDOUT_FIFO,
            ShellProtocolLayout.STDERR_FIFO,
        )
            .directory(fifoDir.toFile())
            .redirectErrorStream(true)
            .start()
        val exit = mkfifo.waitFor()
        check(exit == 0) { "mkfifo failed with exit $exit in $fifoDir" }
        for (name in listOf(ShellProtocolLayout.STDOUT_FIFO, ShellProtocolLayout.STDERR_FIFO)) {
            val fifo = fifoDir.resolve(name)
            // Refuse anything that is not a named pipe. A symlink or regular file pre-placed by
            // another process would have made the child's output a plaintext spool on disk,
            // exactly the threat class the excludes name.
            require(isFifo(fifo)) {
                "$fifo is not a FIFO; refusing to use it as a rendezvous"
            }
            // Owner read+write only. The agent (same uid as the runtime) opens it; no other user
            // can.
            Files.setPosixFilePermissions(
                fifo,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        return fifoDir
    }

    /**
     * Opens the warm-up pair of read-write descriptors.
     *
     * The agent opens each FIFO O_RDONLY, and a read-only open on a named pipe BLOCKS until a
     * writer appears. The warm-up IS that writer: it releases the agent's open, and the runtime
     * holds it until the child has finished, after which the child is the only writer and the
     * agent sees the EOF that ends the stream.
     */
    private fun openWarmup(protocol: ShellProtocolLayout): List<Closeable> = listOf(
        FileChannel.open(
            protocol.stdoutFifo,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ),
        FileChannel.open(
            protocol.stderrFifo,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ),
    )

    /**
     * Launches the production ingest agent as a separate JVM that owns the FIFO read ends.
     *
     * The agent lives in
     * `dev.rubentxu.pipeline.v2.application.durable.ingest.ObsPc2IngestAgent` (production, in
     * `pipeline-application`). The runtime module cannot depend on `pipeline-application` (it
     * would be a cycle: `application` already depends on `runtime`), so the agent is loaded by
     * name. The class name is a string the runtime owns; if the class is missing the runtime
     * fails the launch with a typed message rather than a `ClassNotFoundException` leaking the
     * architectural boundary.
     *
     * The agent opens the FIFOs O_RDONLY (no spurious EOF), redacts in memory, and commits
     * sanitized bytes to the Output Plane. The runtime does NOT start its per-channel pump
     * threads in this mode.
     *
     * ## Detachment
     *
     * The agent is launched with the same JVM as the runtime and inherits the runtime's
     * session. The runtime keeps the [Process] handle so the run terminal can send `SIGTERM`
     * and, after a 5-second grace, `SIGKILL`. Killing the runtime JVM does NOT automatically
     * kill the agent: the agent owns the FIFO read ends and survives its writer's death, which
     * is the whole point of the Level B design.
     */
    private fun launchAgent(protocol: ShellProtocolLayout, fifoDir: Path): Process {
        val runId = config.runId ?: error("runId must be set when pumpedViaAgent is true")
        val secret = config.agentSecret ?: error("agentSecret must be set when pumpedViaAgent is true")
        val args = Args(
            controlDirRoot = controlDirRootOf(protocol.controlDir),
            runId = runId,
            opId = opId,
            fifoDir = fifoDir,
            readyMarker = protocol.agentReadyMarker,
        )

        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        // The agent MUST outlive the runtime JVM, so it is launched via `setsid` to create a
        // new session. The default ProcessBuilder launch would put the agent in the runtime's
        // process group, and a `kill -9` on the runtime would also kill the agent — defeating
        // the whole point of Level B. `setsid` makes the agent its own session leader; the
        // runtime killing its own process group does not take the agent down.
        //
        // The agent's output (including the `pc2-agent-pump-*-failed` lines the agent emits on
        // its catch block) is captured to a log file in the control dir so a failed run can read
        // it when the assertion fails. The runtime itself does not depend on this log; the FIFO
        // is the contract.
        val builder = ProcessBuilder(
            listOf("setsid", javaBin, "-cp", classpath, AGENT_MAIN_CLASS) + args.toArgv().asList(),
        )
        builder.directory(protocol.controlDir.toFile())
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.to(protocol.agentLog.toFile()))
        // The secret travels in the environment, not in the argv: see the class KDoc.
        builder.environment().putAll(agentEnvironment(secret))
        return builder.start()
    }

    /**
     * Blocks until the agent's ready marker exists, with a bounded deadline.
     *
     * The agent touches the marker once it has both FIFOs open and is ready to drain. The
     * runtime MUST wait on the marker before starting the child: opening a FIFO for write blocks
     * until a reader appears, so a child started before the agent attaches would deadlock on
     * its first write.
     */
    private fun awaitReady(protocol: ShellProtocolLayout, agent: Process) {
        val deadline = System.currentTimeMillis() + AGENT_READY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(protocol.agentReadyMarker)) return
            Thread.sleep(20L)
        }
        val detail = if (!agent.isAlive) {
            " (agent process exited before attaching)"
        } else {
            " (readyMarker ${protocol.agentReadyMarker} not found within " +
                "${AGENT_READY_TIMEOUT_MILLIS}ms)"
        }
        error("ingest agent failed to attach: readyMarker missing" + detail)
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
