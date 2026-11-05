package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The boundary a CONFINED run really gets, observed from inside the run.
 *
 * ## The guarantee
 *
 * A step that asked for a boundary runs inside one: it writes in its workspace,
 * it cannot write anywhere else, it holds no capability, its network carries
 * nothing but the loopback, and its user, process and network namespaces are
 * not the host's.
 *
 * ## Why this instrument launches a child
 *
 * The other instruments in this module are pure: they read the plan, the
 * wrapper's content and the provider's rules. None of them can say whether the
 * HOST honours the plan — and the plan being right is not the same as the
 * boundary being in force. This one launches the child and asks it what it
 * sees from inside.
 *
 * ## Why it can be skipped
 *
 * The boundary needs a host that can apply it. A host without the tool cannot
 * observe the guarantee, and the row says so instead of pretending: the
 * guarantee is then UNPROVEN on that host, which is a fact the caller can see
 * (the row is reported as skipped) and not a fact the test hides.
 */
class ConfinedLaunchTest {

    /** The script the confined step runs. It asks the boundary what it is. */
    private val script = """
        #!/bin/bash
        echo "CAPS=$(grep ^CapEff: /proc/self/status)"
        echo "NETDEV=$(tail -n +3 /proc/net/dev | cut -d: -f1 | tr -d ' ' | tr '\n' ' ')"
        echo "NSUSER=$(readlink /proc/self/ns/user)"
        echo "NSPID=$(readlink /proc/self/ns/pid)"
        echo "NSNET=$(readlink /proc/self/ns/net)"
        if touch inside-ok.txt 2>/dev/null; then echo "WRITE_INSIDE=OK"; else echo "WRITE_INSIDE=FAIL"; fi
        if touch /pipelinek-should-not-exist 2>/dev/null; then echo "WRITE_OUTSIDE=OK"; else echo "WRITE_OUTSIDE=REFUSED"; fi
        if touch "${'$'}(dirname "${'$'}0")/forged.txt" 2>/dev/null; then echo "WRITE_CONTROL=OK"; else echo "WRITE_CONTROL=REFUSED"; fi
    """.trimIndent()

    @Test
    fun `the confined step runs inside the boundary it asked for`() {
        assumeTrue(
            SandboxConfinement.locate() != null,
            "this host has no '${SandboxConfinement.TOOL}', so a confined run cannot be observed here",
        )

        val controlDir = Files.createTempDirectory("pipelinek-confined-control")
        val workspace = Files.createTempDirectory("pipelinek-confined-workspace")

        val launch = ShellProcessLauncher("op-confined", DurableShConfig()).launch(
            controlDir = controlDir,
            scriptContent = script,
            request = DurableShellExecutionRequest(
                timeoutMs = TimeUnit.SECONDS.toMillis(30),
                env = emptyMap(),
                sandbox = SandboxConfig(SandboxProfile.CONFINED),
                workspaceRoot = workspace,
                captureStdout = true,
                outputProjection = DurableShellOutputProjection.CAPTURE_FILE,
                timeoutCompletion = DurableShellTimeoutCompletion.KILL_RESULT,
            ),
            transcriptRedactor = null,
            channelSinks = emptyMap(),
            attachRendezvous = { emptyList() },
        )

        launch.handle.onExit().get(30, TimeUnit.SECONDS)
        val output = Files.readString(ShellProtocolLayout(controlDir).outputFile)

        assertTrue(
            output.contains("WRITE_INSIDE=OK"),
            "the step could not write in its own workspace: $output",
        )
        assertTrue(
            output.contains("WRITE_OUTSIDE=REFUSED"),
            "the step reached the rest of the system: $output",
        )
        assertTrue(
            output.contains("WRITE_CONTROL=REFUSED"),
            "the step can write in the directory of control, where its own verdict is " +
                "published: $output",
        )
        assertTrue(
            Files.exists(workspace.resolve("inside-ok.txt")),
            "the write that succeeded did not land in the workspace",
        )
        assertTrue(
            output.contains("CapEff:") && output.contains("0000000000000000"),
            "the step holds a capability it should not hold: $output",
        )
        assertTrue(
            output.contains("NETDEV=lo"),
            "the step's network carries something else: $output",
        )

        val hostUserNamespace = namespaceLink("user")
        val hostNetworkNamespace = namespaceLink("net")
        val hostProcessNamespace = namespaceLink("pid")
        assertTrue(
            output.contains("NSUSER=") && !output.contains("NSUSER=$hostUserNamespace"),
            "the step runs in the host's user namespace: $output",
        )
        assertTrue(
            output.contains("NSNET=") && !output.contains("NSNET=$hostNetworkNamespace"),
            "the step runs in the host's network namespace: $output",
        )
        assertTrue(
            output.contains("NSPID=") && !output.contains("NSPID=$hostProcessNamespace"),
            "the step runs in the host's process namespace: $output",
        )
        assertEquals(
            0,
            launch.exitCode.read(),
            "the confined step did not finish cleanly: $output",
        )
    }

    /** The namespace this process is in, as the kernel names it. */
    private fun namespaceLink(name: String): String =
        Files.readSymbolicLink(Path.of("/proc/self/ns/$name")).toString()
}
