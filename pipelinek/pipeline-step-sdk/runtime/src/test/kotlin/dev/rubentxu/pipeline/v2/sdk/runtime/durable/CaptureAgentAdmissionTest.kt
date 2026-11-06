package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.classifyShellTerminal
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskTerminal
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The capture and the ingest agent cannot both hold the stdout.
 *
 * **The guarantee.** A request that asks for the captured stdout AND for the ingest agent is
 * refused BEFORE the child exists, with a refusal that names the two modes and carries nothing
 * else. The two modes are not two ways of doing the same thing: the capture asks for the EXACT
 * bytes as a typed value, and the agent asks for the SANITIZED bytes as the durable transcript,
 * and the stdout cannot be both at once. While the agent owns the descriptors the typed value
 * file has no writer at all, so the value would be an absence — and an absence the published
 * projection turns into an empty string, which is the answer to "the program printed nothing".
 *
 * **The observable capacity it serves.** A step that asks for the stdout gets a value, or it
 * gets a refusal that says why. It never gets an empty string for a program that printed
 * something, and it never gets that string without having been told the combination is not
 * supported.
 *
 * **Why this instrument and not another.** The admission is a rule over three facts, and the
 * cheapest instrument that can fail is the door that applies it: one call, with no child, no
 * FIFO, no agent JVM and no clock. The agent path has no executable end-to-end scenario in this
 * tree — its own contract says so — so an end-to-end instrument could not be built without the
 * E2E layer this repository forbids. What this instrument observes instead is the thing the
 * end-to-end would have had to observe: that the request is refused before anything is started.
 *
 * **What is faked, and what is not.** The child is never faked, because it is never started:
 * the refusal happens before the launch, and the rows below assert that the artifacts the
 * launch WOULD have created are not there. The rest — the configuration, the options, the
 * layout — is the product's own.
 */
class CaptureAgentAdmissionTest {

    @TempDir
    lateinit var temp: Path

    /** The child's own words. They must not appear in the refusal. */
    private val script = "echo hello-from-the-child"

    /** The secret the agent would redact in memory. It must not appear in the refusal either. */
    private val agentSecret = "the-agent-secret"

    private val controlDir: Path get() = temp.resolve("op-1")

    private val protocol: ShellProtocolLayout get() = ShellProtocolLayout(controlDir)

    /**
     * The configuration of the agent path: the agent is on, it has its secret, and the run it
     * belongs to is named. These are the facts the configuration itself demands before it
     * accepts the agent mode at all.
     */
    private fun agentConfig(): DurableShConfig = DurableShConfig(
        pumpedViaAgent = true,
        agentSecret = agentSecret,
        runId = "run-1",
    )

    /**
     * The options of a step that asks for the stdout as a typed value.
     */
    private fun captureOptions(): ShOptions = ShOptions(
        workspaceRoot = temp,
        captureStdout = true,
        timeoutMs = null,
        env = emptyMap(),
    )

    /**
     * The execution under test: the capture and the agent, at the executor's only door.
     *
     * The redactor is present on purpose: a composition with a per-channel destination is what
     * makes the JVM pump the bytes, and pumping through the JVM is what hands the child's
     * descriptors to the agent. Without it the agent has no bytes to own, and the combination
     * would be a different fact.
     */
    private fun executeCaptureWithTheAgent(): DurableTaskTerminal =
        DurableShellExecutor().executeTerminal(
            controlDir = controlDir,
            scriptContent = script,
            opId = "op-1",
            shOptions = captureOptions(),
            config = agentConfig(),
            transcriptRedactor = { stream -> stream },
        )

    @Test
    fun `with the agent owning the stdout, the published value is an empty string nobody printed`() {
        // This row is the MEASUREMENT of what the combination produces, chained through the
        // three facts that produce it. It is the reason the refusal exists, and it stays true
        // after the refusal: the routing, the reading and the projection are facts about the
        // routing, and the refusal happens before any of them is reached.

        // The routing: with the agent owning the descriptors, the stdout goes to its FIFO, so
        // the capture's channel has no writer at all.
        val plan = ShellProcessLauncher.planChildDescriptors(
            protocol = protocol,
            captureStdout = true,
            pumpedThroughJvm = true,
            agentOwns = true,
        )
        assertInstanceOf(
            ChildDescriptorTarget.AgentFifo::class.java,
            plan.stdout,
            "the agent does not own the stdout, so the capture would have had a writer",
        )

        // The channel nobody wrote: an absence, which is not an empty value.
        val read = DurableShellExecutor().readOutputText(controlDir)
        assertInstanceOf(
            CapturedStdoutRead.Absent::class.java,
            read,
            "the capture channel holds something, so the value was not lost",
        )

        // The decision: an absence is an exit WITHOUT a value.
        val terminal = ShellTerminalDecision(opId = "op-1", controlDir = controlDir)
            .forPollOutcome(ShellPollOutcome.Published(0), read)
        val exited = assertInstanceOf(
            DurableTaskTerminal.Exited::class.java,
            terminal,
            "an absence is not an exit without a value",
        )
        assertNull(
            exited.output.capturedStdout,
            "the absence was published as a value",
        )

        // The published projection: the absence becomes the empty string. This is what the
        // consumer receives today — the answer to "the program printed nothing", for a program
        // whose stdout nobody ever captured.
        assertEquals(
            ShellInvocationResult.Stdout(""),
            classifyShellTerminal(exited, ShellReturnMode.STDOUT),
            "the lost value is not published as an empty string",
        )
    }

    @Test
    fun `a capture with the ingest agent is refused before the child starts`() {
        val terminal = executeCaptureWithTheAgent()

        val refused = assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "the capture and the agent were accepted, and the value was published as nothing",
        )
        assertEquals(
            "CAPTURE_AGENT_MODE_REFUSED",
            refused.failure.code,
            "the refusal does not name the combination it refused",
        )
        assertFalse(
            refused.failure.retryable,
            "the refusal is retryable, and the same request would be refused again",
        )

        // Nothing was started. The artifacts below are the CONTINENT of this absence: the
        // launch materialises the FIFOs of the rendezvous, the wrapper publishes the exit code
        // onto the result file, and the capture's writer is the value file. Their absence is
        // what says the launch never happened.
        assertFalse(
            Files.exists(protocol.fifoDir),
            "the rendezvous was materialised, so the agent was started",
        )
        assertFalse(
            Files.exists(protocol.resultFile),
            "a child was launched, so an exit code was published",
        )
        assertFalse(
            Files.exists(protocol.outputFile),
            "the capture channel was written, so the value was produced",
        )
    }

    @Test
    fun `the refusal carries neither the script nor the agent's secret`() {
        val terminal = executeCaptureWithTheAgent()

        val refused = assertInstanceOf(
            DurableTaskTerminal.LaunchFailed::class.java,
            terminal,
            "the capture and the agent were accepted",
        )
        // The refusal's IDENTITY is asserted here, and not only in the row above: a diagnostic
        // that carries nothing can be checked against anything, and this row would then pass
        // vacuously on a refusal that is not this one.
        assertEquals(
            "CAPTURE_AGENT_MODE_REFUSED",
            refused.failure.code,
            "the failure is not the refusal of the combination",
        )
        val diagnostic = refused.failure.message + refused.failure.details.toString()
        assertFalse(
            diagnostic.contains(script),
            "the refusal carries the script: $diagnostic",
        )
        assertFalse(
            diagnostic.contains(agentSecret),
            "the refusal carries the agent's secret: $diagnostic",
        )
    }

    @Test
    fun `a refused combination leaves nothing on the disk`() {
        executeCaptureWithTheAgent()

        // The refusal happens before the launch, so the control directory holds no file at all.
        // That is the evidence for the promise the agent mode makes about secrets: the mode
        // never persists the raw bytes, and the refusal does not start persisting them either.
        val written = if (Files.exists(controlDir)) {
            Files.walk(controlDir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.toList()
            }
        } else {
            emptyList()
        }
        assertTrue(
            written.isEmpty(),
            "the refusal wrote files: $written",
        )
    }
}
