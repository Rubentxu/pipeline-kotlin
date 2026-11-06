package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The environment the ingest agent's PROCESS is launched with.
 *
 * **The guarantee.** The agent's process is launched with an environment the runtime COMPOSED:
 * the variables the protocol needs, and the agent's own secret. Nothing else reaches it — in
 * particular, nothing the runtime's own environment holds, and so no credential the controller
 * owns.
 *
 * **The observable capacity it serves.** The controller resolved a passphrase to open the
 * credential store, and the agent is a process the controller launched. A child that inherits the
 * controller's environment can decrypt every secret in that store: it holds strictly more
 * authority than the pipeline asked for, and more than any single binding grants. The agent
 * exists to REDACT the child's bytes, and it would be reading the controller's own credentials
 * while doing it.
 *
 * **Why this instrument and not another.** The guarantee is about a PROCESS's environment, and the
 * cheapest instrument that can fail is the builder that process would be launched with: it holds
 * the argv and the environment, and both can be read WITHOUT starting anything. A real agent JVM
 * would need a real FIFO, a real classpath and a real clock, and the unit layer may not start a
 * subprocess at all.
 *
 * **What the oracle is.** The names that pass are written out HERE, and not read from the
 * product's own list: a test that asserted the product's list against itself would pass with any
 * list at all, including one that carried a secret.
 *
 * **What is not frozen.** The VALUES of the variables that pass: they are the host's, and the
 * host owns them. What is frozen is WHICH variables pass, that the ones that pass keep the
 * host's value, and that the secret travels under the name the agent reads.
 */
class AgentProcessEnvironmentTest {

    /**
     * The environment the runtime holds, with the credentials it owns among it.
     *
     * The controller's own passphrase is the one the product names; the other credential is an
     * ordinary secret that happens to sit in the environment, and it is there so that the row
     * that refuses it is not a row about one particular name.
     */
    private val ambient = mapOf(
        "PATH" to "/usr/bin:/bin",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "LC_CTYPE" to "C.UTF-8",
        "HOME" to "/home/op",
        "JAVA_HOME" to "/opt/jdk",
        "TMPDIR" to "/tmp/op",
        "PIPELINE_STORE_PASSPHRASE" to "the-store-key",
        "AWS_SECRET_ACCESS_KEY" to "another-controller-credential",
        "PIPELINEK_AGENT_SECRET" to "the-parent-copy",
    )

    /** The runtime's configuration: the agent path is on, with the runtime's own secret. */
    private val config = DurableShConfig(
        pumpedViaAgent = true,
        agentSecret = "the-runtime-secret",
        runId = "run-42",
    )

    /** The control directory's layout. Its paths are never touched: nothing is started. */
    private val protocol = ShellProtocolLayout(Path.of("/var/lib/pipelinek/run-42/op-7"))

    @Test
    fun `the agent's process receives only the variables the protocol needs`() {
        val environment = builder().environment()

        // The set is CLOSED: a variable that passes and is not named here is a variable the
        // product decided to hand to a child process without saying so.
        assertEquals(
            setOf("PATH", "LANG", "LC_ALL", "LC_CTYPE", AgentRendezvous.AGENT_SECRET_ENV),
            environment.keys,
            "the agent's process received variables the protocol does not need",
        )
    }

    @Test
    fun `the variables that pass keep the host's value`() {
        val environment = builder().environment()

        assertEquals("/usr/bin:/bin", environment["PATH"], "the PATH the protocol needs was changed")
        assertEquals("C.UTF-8", environment["LANG"], "the locale the protocol needs was changed")
    }

    @Test
    fun `the controller's passphrase does not reach the agent's process`() {
        val environment = builder().environment()

        assertFalse(
            environment.containsKey("PIPELINE_STORE_PASSPHRASE"),
            "the passphrase that opens the credential store reached the agent's process",
        )
    }

    @Test
    fun `a credential the controller owns does not reach the agent's process`() {
        val environment = builder().environment()

        // Not by name: the guarantee is about the CATEGORY, so a row that only refused one
        // name would pass with any other name in the environment.
        assertFalse(
            environment.containsKey("AWS_SECRET_ACCESS_KEY"),
            "a credential the controller owns reached the agent's process",
        )
    }

    @Test
    fun `the parent's copy of the agent's secret does not survive`() {
        val environment = builder().environment()

        // The secret the agent redacts is the RUNTIME's. A copy that came in with the
        // environment is somebody else's value and must not be what the agent is told.
        assertEquals(
            "the-runtime-secret",
            environment[AgentRendezvous.AGENT_SECRET_ENV],
            "the agent's secret is not the runtime's",
        )
    }

    @Test
    fun `the secret does not travel in the argv`() {
        val command = builder().command()

        // The argv is readable through /proc/<pid>/cmdline by EVERY user on the host.
        assertFalse(
            command.any { it.contains("the-runtime-secret") },
            "the secret rides in the argv, where every user on the host can read it",
        )
        assertFalse(
            command.any { it.contains("the-parent-copy") },
            "the parent's copy of the secret rides in the argv",
        )
    }

    /**
     * The builder the agent's process would be launched with, over the given environment.
     *
     * Nothing is started: the builder holds the argv and the environment, and both are what the
     * guarantee is about.
     */
    private fun builder(ambient: Map<String, String> = this.ambient): ProcessBuilder =
        ProcessRendezvousHost(opId = "op-7", config = config).agentProcessBuilder(
            protocol = protocol,
            fifoDir = Path.of("/var/lib/pipelinek/run-42/op-7/fifos"),
            ambient = ambient,
        )

    @Test
    fun `an environment without the variables the protocol needs still launches with what it has`() {
        val environment = builder(ambient = mapOf("PATH" to "/usr/bin:/bin")).environment()

        // The plan does not INVENT a variable: a host that declares no locale does not get one,
        // and the secret is there because the runtime holds it.
        assertEquals(
            setOf("PATH", AgentRendezvous.AGENT_SECRET_ENV),
            environment.keys,
            "the agent's process received variables the host does not have",
        )
        assertTrue(
            environment.containsKey(AgentRendezvous.AGENT_SECRET_ENV),
            "the agent's own secret is missing from its process",
        )
    }
}
