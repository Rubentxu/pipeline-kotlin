package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * **Why this instrument and not another.** The contract is a value with a writer and a
 * reader, so the cheapest instrument that can fail is to write it and read it back. A real
 * FIFO, a real agent JVM and a real child would exercise the whole rendezvous, and the agent
 * path has no executable scenario at all; a unit test that does not need one is the only
 * instrument available here, and it is the one that names WHICH half broke.
 *
 * **What this does not freeze.** The FIFO type predicate is deliberately NOT asserted here:
 * its behaviour is unchanged by this cut — it moved to the shared contract with the same
 * body — and asserting that a path is or is not a named pipe needs a real file, which a unit
 * test must not create. The FIFO's type is checked in production, at the moment the FIFO is
 * created and at the moment it is opened.
 */
class AgentRendezvousTest {

    private val secret = "GHS_DBG_SECRET"

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
}
