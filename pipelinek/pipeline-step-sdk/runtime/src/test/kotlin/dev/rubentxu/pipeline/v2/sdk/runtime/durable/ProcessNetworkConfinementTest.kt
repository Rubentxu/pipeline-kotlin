package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * What the run's network policy reaches, and what it does not.
 *
 * ## The guarantee
 *
 * Two different facts carry the word "network" in this product, and they are
 * enforced by two different mechanisms:
 *
 *  - the GATE refuses a plugin that asks for egress, by withholding the
 *    capability at admission. It reaches the plugin and nothing else;
 *  - the CONFINEMENT puts the child process in a network namespace of its own.
 *    It reaches the process and nothing else.
 *
 * A profile that asks for no confinement gets no confinement, so its child
 * process keeps the host's network. That is the trusted, local-first posture,
 * and the product does not pretend otherwise: what it must not do is announce
 * the gate as if it were the confinement.
 *
 * ## Why this instrument is pure
 *
 * The child process is the thing that would prove the fact by opening a
 * socket, and a unit test does not open sockets. The decision that decides
 * whether a confinement is installed is pure, and so is the argv that says
 * what the installed confinement does; those two are what this instrument
 * reads. The other side — that the host honours the argv — is measured where
 * a host can be asked, by `ConfinedLaunchTest`.
 */
class ProcessNetworkConfinementTest {

    @Test
    fun `a profile that asks for no confinement gets no confinement`() {
        assertNull(
            confinementFor(SandboxProfile.NONE),
            "the trusted profile installs a confinement, so the child keeps the host's network " +
                "inside a boundary the run never asked for",
        )
    }

    @Test
    fun `the local profile confines nothing either`() {
        assertNull(
            confinementFor(SandboxProfile.LOCAL),
            "the local profile installs a confinement, so the child keeps the host's network " +
                "inside a boundary the run never asked for",
        )
    }

    @Test
    fun `the confinement that is installed takes the network away from the child`() {
        val argv = SandboxConfinement.forRun(
            binary = Path.of("/usr/bin/bwrap"),
            workspace = Path.of("/tmp/pipelinek-workspace"),
            controlDir = Path.of("/tmp/pipelinek-control"),
        ).argv(listOf("/tmp/control/script.sh"))

        assertTrue(
            argv.contains("--unshare-net"),
            "the confinement does not take the network away from the child: $argv",
        )
    }

    /**
     * The confinement this profile would install, or null when it installs none.
     */
    private fun confinementFor(profile: SandboxProfile): SandboxConfinement? {
        val request = DurableShellExecutionRequest(
            timeoutMs = TimeUnit.SECONDS.toMillis(30),
            env = emptyMap(),
            sandbox = SandboxConfig(profile),
            workspaceRoot = Path.of("/tmp/pipelinek-workspace"),
            captureStdout = false,
            outputProjection = DurableShellOutputProjection.CAPTURE_FILE,
            timeoutCompletion = DurableShellTimeoutCompletion.KILL_RESULT,
        )
        return ShellProcessLauncher("op-test", DurableShConfig())
            .confinementFor(request, Path.of("/tmp/pipelinek-control"))
    }
}
