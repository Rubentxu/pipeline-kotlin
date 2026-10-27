package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/**
 * The rendezvous's contract, and it is a contract between two processes.
 *
 * **The guarantee.** The values the runtime hands the ingest agent are the values the agent
 * reads back, position for position; and the secret the agent must redact never travels
 * where every user on the host can read it.
 *
 * **The observable capacity it serves.** The agent drains two FIFOs and commits sanitized
 * bytes to the Output Plane. If the runtime wrote the FIFOs' directory in the position the
 * agent reads the operation id — or if the secret rode in the argv — the agent would open
 * another operation's files, or publish the secret it exists to hide. Both failures are
 * silent: the agent would either drain nothing or redact nothing, and the run would report a
 * clean transcript.
 *
 * **The second guarantee.** A rendezvous that does not complete leaves nothing of its own
 * open: the warm-up descriptors are released and the agent is killed. The warm-up descriptors
 * are the FIFOs' only writers besides the child, and the agent reads until EOF — a failed
 * attach that kept them open left an agent JVM alive with nothing owning it, waiting forever
 * for a transcript that would never grow.
 *
 * **Why this instrument and not another.** The contract is a value with a writer and a
 * reader, so the cheapest instrument that can fail is to write it and read it back. A real
 * FIFO, a real agent JVM and a real child would exercise the whole rendezvous, and the agent
 * path has no executable scenario at all; a unit test that does not need one is the only
 * instrument available here, and it is the one that names WHICH half broke.
 *
 * **The failure path is driven through a seam, not through the host.** The FIFOs, the
 * descriptors, the agent JVM and the clock are the HOST's ([RendezvousHost]); what the
 * rendezvous OWNS — release what it acquired when it does not complete — is the policy. The
 * seam is what lets the policy be driven with fakes, so the guarantee is observed instead of
 * asserted, and so the instrument needs no process, no FIFO, no clock and no sleep.
 *
 * **What this does not freeze.** The FIFO type predicate is deliberately NOT asserted here:
 * its behaviour is unchanged by this cut — it moved to the shared contract with the same
 * body — and asserting that a path is or is not a named pipe needs a real file, which a unit
 * test must not create. The FIFO's type is checked in production, at the moment the FIFO is
 * created and at the moment it is opened.
 */
class AgentRendezvousTest {

    private val secret = "GHS_DBG_SECRET"

    /** The control directory's layout. The paths are never touched: the host is faked. */
    private val protocol = ShellProtocolLayout(Path.of("/var/lib/pipelinek/run-42/op-7"))

    private val args = AgentRendezvous.Args(
        controlDirRoot = Path.of("/var/lib/pipelinek/run-42"),
        runId = "run-42",
        opId = "op-7",
        fifoDir = Path.of("/var/lib/pipelinek/run-42/op-7/fifos"),
        readyMarker = Path.of("/var/lib/pipelinek/run-42/op-7/agent.ready"),
    )

    @Test
    fun `the argv carries the five values the agent reads`() {
        val argv = args.toArgv()

        assertEquals(
            AgentRendezvous.Args.ARGUMENT_COUNT,
            argv.size,
            "the argv does not carry the values the agent reads",
        )
        assertEquals("/var/lib/pipelinek/run-42", argv[0], "the control dir's root is not the first value")
        assertEquals("run-42", argv[1], "the run id is not the second value")
        assertEquals("op-7", argv[2], "the operation id is not the third value")
        assertEquals(
            "/var/lib/pipelinek/run-42/op-7/fifos",
            argv[3],
            "the FIFOs' directory is not the fourth value",
        )
        assertEquals(
            "/var/lib/pipelinek/run-42/op-7/agent.ready",
            argv[4],
            "the ready marker is not the fifth value",
        )
    }

    @Test
    fun `the argv the runtime writes is the argv the agent reads`() {
        val readBack = AgentRendezvous.Args.fromArgv(args.toArgv())

        // The whole point of the shared value: one writer, one reader, one shape. Before this
        // cut the writer was a private list in the runtime and the reader was a positional
        // read in the agent, and nothing checked that they still agreed.
        assertEquals(args, readBack, "the agent does not read back what the runtime wrote")
    }

    @Test
    fun `an argv with the wrong number of values is refused`() {
        val short = arrayOf("/var/lib/pipelinek/run-42", "run-42", "op-7")

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            AgentRendezvous.Args.fromArgv(short)
        }
        // Read as a shorter argv, the agent would have taken the FIFOs' directory for the
        // operation id and opened a path that does not exist.
        assertTrue(
            refusal.message!!.contains("3 arguments"),
            "the refusal does not say how many arguments arrived: ${refusal.message}",
        )
    }

    @Test
    fun `the secret travels in the environment and not in the argv`() {
        val environment = AgentRendezvous.agentEnvironment(secret)

        assertEquals(
            secret,
            environment[AgentRendezvous.AGENT_SECRET_ENV],
            "the secret does not reach the agent's environment",
        )
        // The argv is readable through /proc/<pid>/cmdline by EVERY user on the host — measured
        // on the reference host as mode 444, with no hidepid. A secret there is a secret
        // handed to every user of the machine.
        assertFalse(
            args.toArgv().any { it.contains(secret) },
            "the secret rides in the argv, where every user on the host can read it",
        )
    }

    @Test
    fun `the agent reads the secret back from the environment`() {
        val readBack = AgentRendezvous.secretFromEnvironment(
            AgentRendezvous.agentEnvironment(secret),
        )

        assertEquals(secret, readBack, "the agent does not read back the secret it was given")
    }

    @Test
    fun `an environment without the secret is refused`() {
        val refusal = assertThrows(IllegalStateException::class.java) {
            AgentRendezvous.secretFromEnvironment(emptyMap())
        }

        // An agent that redacts nothing would write the child's raw bytes to the Output Plane.
        assertTrue(
            refusal.message!!.contains(AgentRendezvous.AGENT_SECRET_ENV),
            "the refusal does not name the variable that was missing: ${refusal.message}",
        )
    }

    @Test
    fun `an environment with an empty secret is refused`() {
        assertThrows(IllegalStateException::class.java) {
            AgentRendezvous.secretFromEnvironment(
                mapOf(AgentRendezvous.AGENT_SECRET_ENV to ""),
            )
        }
    }

    @Test
    fun `the control dir's root is the level above the control dir`() {
        val root = AgentRendezvous.controlDirRootOf(Path.of("/var/lib/pipelinek/run-42/op-7"))

        // Exactly ONE level up. The control dir is <root>/<opId>, not <root>/<runId>/<opId>.
        // An earlier implementation walked one level too high, and the agent and the reader
        // ended up on two different stores: every "committed N bytes / reader saw 0" row was
        // silent.
        assertEquals(Path.of("/var/lib/pipelinek/run-42"), root, "the root is not one level up")
    }

    @Test
    fun `a control dir with no level above it is refused`() {
        assertThrows(IllegalStateException::class.java) {
            AgentRendezvous.controlDirRootOf(Path.of("/"))
        }
    }

    @Test
    fun `a rendezvous that never attaches releases the warm-up descriptors and kills the agent`() {
        val events = mutableListOf<String>()
        val stdout = RecordingDescriptor("stdout", events)
        val stderr = RecordingDescriptor("stderr", events)
        val agent = FakeAgentProcess(events)
        val rendezvous = AgentRendezvous(
            opId = "op-7",
            config = DurableShConfig(),
            host = FakeRendezvousHost(
                warmup = listOf(stdout, stderr),
                agent = agent,
                failAt = FakeRendezvousHost.Stage.READY,
            ),
        )

        val refusal = assertThrows(IllegalStateException::class.java) {
            rendezvous.rendezvous(protocol)
        }

        // The failure that caused the teardown is the one reported: the teardown does not
        // replace the verdict with its own.
        assertTrue(
            refusal.message!!.contains("readyMarker missing"),
            "the failure that caused the teardown is not the one reported: ${refusal.message}",
        )
        assertEquals(1, stdout.closeCalls, "the stdout warm-up descriptor was not released")
        assertEquals(1, stderr.closeCalls, "the stderr warm-up descriptor was not released")
        assertEquals(1, agent.destroyForciblyCalls, "the agent was left running")
    }

    @Test
    fun `the warm-up descriptors are released before the agent is killed`() {
        val events = mutableListOf<String>()
        val stdout = RecordingDescriptor("stdout", events)
        val stderr = RecordingDescriptor("stderr", events)
        val agent = FakeAgentProcess(events)
        val rendezvous = AgentRendezvous(
            opId = "op-7",
            config = DurableShConfig(),
            host = FakeRendezvousHost(
                warmup = listOf(stdout, stderr),
                agent = agent,
                failAt = FakeRendezvousHost.Stage.READY,
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            rendezvous.rendezvous(protocol)
        }

        // Closing the descriptors is what lets the agent see EOF, so the order is part of the
        // guarantee and not a preference: a kill first would leave the agent holding read ends
        // that nothing would ever close.
        val closedAt = events.withIndex().filter { it.value.startsWith("close:") }.map { it.index }
        val killedAt = events.indexOf("kill")
        assertEquals(2, closedAt.size, "the warm-up descriptors were not both released: $events")
        assertTrue(killedAt >= 0, "the agent was not killed: $events")
        assertTrue(
            closedAt.all { it < killedAt },
            "the descriptors must be released before the agent is killed: $events",
        )
    }

    @Test
    fun `a rendezvous whose agent never starts releases the warm-up descriptors`() {
        val events = mutableListOf<String>()
        val stdout = RecordingDescriptor("stdout", events)
        val stderr = RecordingDescriptor("stderr", events)
        val rendezvous = AgentRendezvous(
            opId = "op-7",
            config = DurableShConfig(),
            host = FakeRendezvousHost(
                warmup = listOf(stdout, stderr),
                agent = FakeAgentProcess(events),
                failAt = FakeRendezvousHost.Stage.LAUNCH,
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            rendezvous.rendezvous(protocol)
        }

        assertEquals(1, stdout.closeCalls, "the stdout warm-up descriptor was not released")
        assertEquals(1, stderr.closeCalls, "the stderr warm-up descriptor was not released")
    }

    @Test
    fun `a descriptor that refuses to close does not keep the agent alive`() {
        val events = mutableListOf<String>()
        val stubborn = RecordingDescriptor("stdout", events, refusesToClose = true)
        val stderr = RecordingDescriptor("stderr", events)
        val agent = FakeAgentProcess(events)
        val rendezvous = AgentRendezvous(
            opId = "op-7",
            config = DurableShConfig(),
            host = FakeRendezvousHost(
                warmup = listOf(stubborn, stderr),
                agent = agent,
                failAt = FakeRendezvousHost.Stage.READY,
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            rendezvous.rendezvous(protocol)
        }

        // One descriptor that refuses to close is not a reason to leave the others held, and not
        // a reason to leave the agent running.
        assertEquals(1, stubborn.closeCalls, "the stubborn descriptor was not even attempted")
        assertEquals(1, stderr.closeCalls, "the other descriptor was left open")
        assertEquals(1, agent.destroyForciblyCalls, "the agent was left running")
    }

    @Test
    fun `a rendezvous that attaches hands the warm-up descriptors back open`() {
        val events = mutableListOf<String>()
        val stdout = RecordingDescriptor("stdout", events)
        val stderr = RecordingDescriptor("stderr", events)
        val agent = FakeAgentProcess(events)
        val rendezvous = AgentRendezvous(
            opId = "op-7",
            config = DurableShConfig(),
            host = FakeRendezvousHost(
                warmup = listOf(stdout, stderr),
                agent = agent,
                failAt = FakeRendezvousHost.Stage.NONE,
            ),
        )

        val handed = rendezvous.rendezvous(protocol)

        // The descriptors are the runtime's to hold while the child runs: they are the FIFOs'
        // only writers besides the child, and closing them is what ends the agent's stream.
        assertEquals(listOf(stdout, stderr), handed, "the warm-up descriptors were not handed back")
        assertEquals(0, stdout.closeCalls, "the stdout warm-up descriptor was closed too early")
        assertEquals(0, stderr.closeCalls, "the stderr warm-up descriptor was closed too early")
        assertEquals(0, agent.destroyForciblyCalls, "a live agent was killed")
    }
}

/**
 * The host's effects, faked.
 *
 * The rendezvous's POLICY is what the guarantee is about, and the policy is driven through this
 * seam so the instrument needs no FIFO, no agent JVM, no clock and no sleep.
 */
private class FakeRendezvousHost(
    private val warmup: List<Closeable>,
    private val agent: Process,
    private val failAt: Stage,
) : RendezvousHost {

    /** The stage at which the fake host fails. */
    enum class Stage {
        /** Nothing fails. */
        NONE,

        /** The agent cannot be launched. */
        LAUNCH,

        /** The agent never attaches. */
        READY,
    }

    override fun materialiseFifos(protocol: ShellProtocolLayout): Path = Path.of("/fifos")

    override fun openWarmup(protocol: ShellProtocolLayout): List<Closeable> = warmup

    override fun launchAgent(protocol: ShellProtocolLayout, fifoDir: Path): Process {
        if (failAt == Stage.LAUNCH) {
            error("the ingest agent could not be launched")
        }
        return agent
    }

    override fun awaitReady(protocol: ShellProtocolLayout, agent: Process) {
        if (failAt == Stage.READY) {
            error("ingest agent failed to attach: readyMarker missing")
        }
    }
}

/**
 * A descriptor that records its own release, and can refuse it.
 */
private class RecordingDescriptor(
    private val name: String,
    private val events: MutableList<String>,
    private val refusesToClose: Boolean = false,
) : Closeable {

    /** How many times the release was attempted. */
    var closeCalls: Int = 0
        private set

    override fun close() {
        closeCalls++
        events.add("close:$name")
        if (refusesToClose) {
            error("this descriptor refuses to close")
        }
    }
}

/**
 * The agent, as a process that records whether it was killed.
 *
 * A real agent JVM would need a real FIFO, a real classpath and a real clock; the policy only
 * needs to know whether the kill was issued, so the fake records it.
 */
private class FakeAgentProcess(
    private val events: MutableList<String>,
) : Process() {

    /** How many times the forced kill was issued. */
    var destroyForciblyCalls: Int = 0
        private set

    /** Whether the process is still alive. */
    private var alive: Boolean = true

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() {
        alive = false
    }

    override fun destroyForcibly(): Process {
        destroyForciblyCalls++
        events.add("kill")
        alive = false
        return this
    }

    override fun isAlive(): Boolean = alive

    override fun pid(): Long = 4242L
}
