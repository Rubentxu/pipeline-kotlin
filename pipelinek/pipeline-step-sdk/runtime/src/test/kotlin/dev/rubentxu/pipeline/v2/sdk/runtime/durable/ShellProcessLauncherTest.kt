package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The launch's two rules are a routing table and an argv, and both are values.
 *
 * **The guarantee.** The child's bytes land where the execution's mode requires them to. A
 * captured stdout is the typed value and NOTHING else reaches it — not the stderr, not the
 * transcript's bytes; the stderr is a separate channel all the way to the kernel descriptor, so
 * a reader can be told which channel it is looking at; the transcript has exactly ONE writer;
 * and when the ingest agent is the one pumping, both channels belong to its FIFOs.
 *
 * **The observable capacity it serves.** A durable `sh` step returns a typed value that is not
 * polluted by the step's own diagnostics, and a reader can stream the transcript and be told
 * whether it is looking at stdout or stderr. Nothing in the tree exercised the routing before
 * this: the only way to observe it was to launch a child, let it write, and read the files it
 * left behind — an instrument that says WHICH rule broke only by accident.
 *
 * **Why this instrument and not another.** The routing is a pure function of four facts, so the
 * cheapest instrument that can fail is a call to it. A process, a thread, a clock or a real
 * filesystem would add cost and flakiness without adding a single reachable way for the routing
 * to be wrong, and the end-to-end scenarios already run real children: they say whether the
 * bytes arrived, and this says WHICH rule sent them.
 *
 * **What this does not freeze.** The launch itself — the argv's expansion on the host, the
 * environment the child inherits, the order of the pumps — is not asserted here. Those are
 * observed elsewhere; what is checked here is the decision the launch applies, which is the part
 * that can be wrong without anybody noticing.
 */
class ShellProcessLauncherTest {

    private val protocol = ShellProtocolLayout(Path.of("/tmp/pipelinek-shell-launcher/op-42"))

    @Test
    fun `the argv is the wrapper and nothing else`() {
        val argv = ShellProcessLauncher.shellLaunchArgv(protocol.wrapperFile)

        // The argv names the wrapper and the interpreter that runs it. The user's script is NOT
        // here: an argument is readable through /proc/<pid>/cmdline by every user on the host, so
        // a script that reached this list would be readable by all of them.
        assertEquals(
            listOf("setsid", "bash", protocol.wrapperFile.toString()),
            argv,
            "the argv is not the wrapper alone",
        )
        assertEquals(3, argv.size, "the argv carries more than the wrapper needs")
    }

    @Test
    fun `the composition pumps the child's bytes when it has a redactor`() {
        assertTrue(
            ShellProcessLauncher.pumpsThroughJvm(hasRedactor = true, channelSinkCount = 0),
            "a redactor alone did not make the composition pump",
        )
    }

    @Test
    fun `the composition pumps the child's bytes when it has a channel destination`() {
        assertTrue(
            ShellProcessLauncher.pumpsThroughJvm(hasRedactor = false, channelSinkCount = 1),
            "a channel destination did not make the composition pump",
        )
    }

    @Test
    fun `the composition does not pump the child's bytes when it has neither`() {
        assertFalse(
            ShellProcessLauncher.pumpsThroughJvm(hasRedactor = false, channelSinkCount = 0),
            "the composition pumped without a destination",
        )
    }

    @Test
    fun `the agent owns the descriptors when it is the one pumping`() {
        assertTrue(
            ShellProcessLauncher.agentOwnsDescriptors(pumpedViaAgent = true, pumpedThroughJvm = true),
            "the agent did not own the descriptors it is pumping",
        )
    }

    @Test
    fun `the agent does not own the descriptors when the composition does not pump`() {
        assertFalse(
            ShellProcessLauncher.agentOwnsDescriptors(pumpedViaAgent = true, pumpedThroughJvm = false),
            "the agent owns descriptors it has no bytes for",
        )
    }

    @Test
    fun `the agent does not own the descriptors when the composition does not use the agent`() {
        assertFalse(
            ShellProcessLauncher.agentOwnsDescriptors(pumpedViaAgent = false, pumpedThroughJvm = true),
            "the agent owns descriptors it was not asked for",
        )
    }

    @Test
    fun `the captured stdout goes to the value file and the stderr to a pipe`() {
        val plan = ShellProcessLauncher.planChildDescriptors(
            protocol = protocol,
            captureStdout = true,
            pumpedThroughJvm = true,
            agentOwns = false,
        )

        // The typed value carries the stdout and NOTHING else: the stderr leaves through its own
        // descriptor, so the value cannot be polluted by the step's diagnostics.
        assertEquals(
            ChildDescriptorTarget.ValueFile(protocol.outputFile),
            plan.stdout,
            "the captured stdout does not go to the value file",
        )
        assertEquals(
            ChildDescriptorTarget.Pipe,
            plan.stderr,
            "the stderr is not a separate channel",
        )
    }

    @Test
    fun `the captured stdout goes to the value file and the stderr to the transcript`() {
        val plan = ShellProcessLauncher.planChildDescriptors(
            protocol = protocol,
            captureStdout = true,
            pumpedThroughJvm = false,
            agentOwns = false,
        )

        assertEquals(
            ChildDescriptorTarget.ValueFile(protocol.outputFile),
            plan.stdout,
            "the captured stdout does not go to the value file",
        )
        assertEquals(
            ChildDescriptorTarget.TranscriptFile(protocol.consoleLog),
            plan.stderr,
            "the stderr does not go to the transcript",
        )
    }

    @Test
    fun `both channels are piped when the stdout is not captured`() {
        val plan = ShellProcessLauncher.planChildDescriptors(
            protocol = protocol,
            captureStdout = false,
            pumpedThroughJvm = true,
            agentOwns = false,
        )

        // Two independent pipes: fusing them would make the channel distinction unrecoverable at
        // the kernel boundary, which is what made "--channel stderr" unanswerable.
        assertEquals(ChildDescriptorTarget.Pipe, plan.stdout, "the stdout is not piped")
        assertEquals(ChildDescriptorTarget.Pipe, plan.stderr, "the stderr is not piped")
    }

    @Test
    fun `both channels go to the transcript when nothing is pumped`() {
        val plan = ShellProcessLauncher.planChildDescriptors(
            protocol = protocol,
            captureStdout = false,
            pumpedThroughJvm = false,
            agentOwns = false,
        )

        // Nothing can attribute the channels, so a single file is honest about the fact that this
        // composition does not separate them.
        assertEquals(
            ChildDescriptorTarget.TranscriptFile(protocol.consoleLog),
            plan.stdout,
            "the stdout does not go to the transcript",
        )
        assertEquals(
            ChildDescriptorTarget.TranscriptFile(protocol.consoleLog),
            plan.stderr,
            "the stderr does not go to the transcript",
        )
    }

    @Test
    fun `the agent owns both channels when it is the one pumping`() {
        val plan = ShellProcessLauncher.planChildDescriptors(
            protocol = protocol,
            captureStdout = true,
            pumpedThroughJvm = true,
            agentOwns = true,
        )

        // The agent is the single durable authority for the transcript under this path, so it
        // owns BOTH channels: the captured stdout goes to the agent's FIFO and not to the value
        // file, because the agent is the one that writes it.
        assertEquals(
            ChildDescriptorTarget.AgentFifo(protocol.stdoutFifo),
            plan.stdout,
            "the agent does not own the stdout",
        )
        assertEquals(
            ChildDescriptorTarget.AgentFifo(protocol.stderrFifo),
            plan.stderr,
            "the agent does not own the stderr",
        )
    }
}
