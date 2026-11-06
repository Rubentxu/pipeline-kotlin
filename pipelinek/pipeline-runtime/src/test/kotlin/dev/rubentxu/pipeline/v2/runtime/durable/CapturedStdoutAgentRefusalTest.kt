package dev.rubentxu.pipeline.v2.runtime.durable

import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode
import dev.rubentxu.pipeline.v2.domain.classifyShellTerminal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What the consumer of a captured stdout receives when the capture asks for the ingest agent.
 *
 * **The guarantee.** The refusal reaches the consumer as a FAILURE that names the combination it
 * refused — not as an empty stdout. The empty string is the answer to "the program printed
 * nothing", and this combination never ran a program at all: the capture and the agent cannot
 * both hold the stdout, so the request is refused before the child exists.
 *
 * **The observable capacity it serves.** A step that asks for the stdout gets a value or a
 * refusal. It never gets an empty string for a program that printed something, and it never gets
 * that string without having been told the combination is not supported.
 *
 * **Why this row lives HERE and not in the substrate's lane.** The substrate's lane owns the
 * refusal; this lane owns the CONSUMER of it. The projection from the substrate's terminal to
 * the value the step publishes is the domain's published contract, and the consumer is what the
 * refusal is for. A witness in the substrate's lane would certify that a terminal exists; this
 * one certifies that the terminal arrives where it has to arrive, as a failure.
 *
 * **What is faked, and what is not.** Nothing is faked: the execution is refused before the
 * child starts, so there is no child, no FIFO, no agent JVM and no clock. The refusal is the
 * product's own, and the projection is the product's own.
 */
class CapturedStdoutAgentRefusalTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the refusal of a capture with the agent reaches the consumer as a failure`() {
        val terminal = DurableShellExecutor().executeTerminal(
            controlDir = temp.resolve("op-1"),
            scriptContent = "echo hello-from-the-child",
            opId = "op-1",
            shOptions = ShOptions(
                workspaceRoot = temp,
                captureStdout = true,
                timeoutMs = null,
                env = emptyMap(),
            ),
            config = DurableShConfig(
                pumpedViaAgent = true,
                agentSecret = "the-agent-secret",
                runId = "run-1",
            ),
            // The composition pumps the bytes through the JVM, which is what hands the child's
            // descriptors to the agent.
            transcriptRedactor = { stream -> stream },
        )

        val result = classifyShellTerminal(terminal, ShellReturnMode.STDOUT)

        val failed = assertInstanceOf(
            ShellInvocationResult.Failed::class.java,
            result,
            "the consumer received a value for a combination that was refused",
        )
        val durable = requireNotNull(failed.durableFailure) {
            "the failure carries no durable record, so the code is lost"
        }
        assertEquals(
            "CAPTURE_AGENT_MODE_REFUSED",
            durable.code,
            "the failure does not name the combination it refused",
        )
    }
}
