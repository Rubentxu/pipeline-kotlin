package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * The rendezvous against the real host: FIFOs on disk, descriptors in this JVM, an agent in
 * another.
 *
 * The effects live here and the POLICY lives in [AgentRendezvous], because a policy that can only
 * be exercised against a real FIFO, a real agent JVM and a real clock cannot be observed at all:
 * the agent path has no executable scenario in this tree.
 */
internal class ProcessRendezvousHost(
    private val opId: String,
    private val config: DurableShConfig,
) : RendezvousHost {

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
    override fun materialiseFifos(protocol: ShellProtocolLayout): Path {
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
            require(AgentRendezvous.isFifo(fifo)) {
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
    override fun openWarmup(protocol: ShellProtocolLayout): List<Closeable> = listOf(
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
     * ## Detachment, and who owns the agent's life
     *
     * The agent runs in its OWN session: `setsid` is what puts it there, so the runtime's death
     * does not take the agent down — the agent owns the FIFO read ends and survives its writer's
     * death, which is the whole point of the Level B design.
     *
     * The runtime does NOT keep the agent's handle beyond the rendezvous, and an earlier KDoc
     * here said that it did. It does not: the handle is what the rendezvous holds while it waits
     * for the agent to attach, and on a FAILED rendezvous it is what kills the agent before the
     * failure is reported. On a SUCCESSFUL one the handle is dropped, and the agent's life is
     * bounded by the FIFO's EOF: it drains until every writer is gone, and the warm-up
     * descriptors are the writers, so the agent leaves when the step's cleanup closes them.
     */
    override fun launchAgent(protocol: ShellProtocolLayout, fifoDir: Path): Process {
        val runId = config.runId ?: error("runId must be set when pumpedViaAgent is true")
        val secret = config.agentSecret ?: error("agentSecret must be set when pumpedViaAgent is true")
        val args = AgentRendezvous.Args(
            controlDirRoot = AgentRendezvous.controlDirRootOf(protocol.controlDir),
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
        // Measured on the reference host from a `ProcessBuilder` child: `setsid` does NOT fork
        // when its caller is not already a process group leader, so the pid this handle carries
        // IS the pid that runs the agent, and `destroyForcibly()` reaches it.
        //
        // The agent's output (including the `pc2-agent-pump-*-failed` lines the agent emits on
        // its catch block) is captured to a log file in the control dir so a failed run can read
        // it when the assertion fails. The runtime itself does not depend on this log; the FIFO
        // is the contract.
        val builder = ProcessBuilder(
            listOf("setsid", javaBin, "-cp", classpath, AgentRendezvous.AGENT_MAIN_CLASS) +
                args.toArgv().asList(),
        )
        builder.directory(protocol.controlDir.toFile())
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.to(protocol.agentLog.toFile()))
        // The secret travels in the environment, not in the argv: see the class KDoc.
        builder.environment().putAll(AgentRendezvous.agentEnvironment(secret))
        return builder.start()
    }

    /**
     * Blocks until the agent's ready marker exists, with a bounded deadline.
     *
     * The agent touches the marker once it has both FIFOs open and is ready to drain. The
     * runtime MUST wait on the marker before starting the child: opening a FIFO for write blocks
     * until a reader appears, so a child started before the agent attaches would deadlock on
     * its first write.
     *
     * The deadline is measured with the MONOTONIC clock: a jump of the wall clock must not end
     * the wait of an execution, and the wait is about elapsed time, not about an instant.
     */
    override fun awaitReady(protocol: ShellProtocolLayout, agent: Process) {
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(AgentRendezvous.AGENT_READY_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            if (Files.exists(protocol.agentReadyMarker)) return
            Thread.sleep(AGENT_READY_POLL_INTERVAL_MILLIS)
        }
        val detail = if (!agent.isAlive) {
            " (agent process exited before attaching)"
        } else {
            " (readyMarker ${protocol.agentReadyMarker} not found within " +
                "${AgentRendezvous.AGENT_READY_TIMEOUT_MILLIS}ms)"
        }
        error("ingest agent failed to attach: readyMarker missing" + detail)
    }

    companion object {

        /** How often the readiness wait looks at the marker. */
        const val AGENT_READY_POLL_INTERVAL_MILLIS: Long = 20L
    }
}
