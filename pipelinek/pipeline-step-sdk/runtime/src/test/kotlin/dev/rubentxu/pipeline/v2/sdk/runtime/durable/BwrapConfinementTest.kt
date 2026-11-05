package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The confinement plan, stated as rules.
 *
 * **The guarantee.** A run that says it is confined is put inside a boundary whose
 * shape is fixed: the root is read-only, the workspace is the only place of the
 * operator's that can be written, the run has namespaces of its own, and the
 * command it runs is separated from the flags that build the boundary.
 *
 * **Why the order is part of the guarantee.** The mounts are applied in the order
 * they are written, and the order was MEASURED on the host this was written
 * against: a private `/tmp` mounted AFTER the workspace bind HIDES a workspace
 * that lives under it, and the run then has no workspace at all ("Can't chdir
 * to ..."). A plan that only asserted the presence of the flags would let that
 * regression through, because the flags would still all be there.
 *
 * **Why this instrument and not another.** The plan is a value, so the cheapest
 * instrument that can fail is a call that reads it. A real child would prove the
 * boundary WORKS, and the end-to-end scenario does that; what it cannot say is
 * WHICH rule of the plan broke, which is what a rule-by-rule check can.
 */
class BwrapConfinementTest {

    private val workspace = Path.of("/tmp/pipelinek-workspace")

    private val confinement = SandboxConfinement(
        binary = Path.of("/usr/bin/bwrap"),
        workspace = workspace,
        readablePaths = listOf(Path.of("/tmp/pipelinek-control")),
    )

    private val argv = confinement.argv(listOf("/tmp/control/script.sh"))

    @Test
    fun `the tool is named by its absolute path`() {
        assertEquals(
            "/usr/bin/bwrap",
            argv.first(),
            "the child resolves the tool itself, and its PATH is normalised: a bare name would not be found",
        )
    }

    @Test
    fun `the root is bound read-only`() {
        assertTrue(
            argv.windowed(size = 3).any { it == listOf("--ro-bind", "/", "/") },
            "the whole system is visible and none of it can be written: $argv",
        )
    }

    @Test
    fun `the workspace is the only writable place of the operator's`() {
        assertTrue(
            argv.windowed(size = 3).any {
                it == listOf("--bind", workspace.toString(), workspace.toString())
            },
            "the workspace is bound read-write, at the same path inside: $argv",
        )
    }

    @Test
    fun `the private tmpfs is mounted before the workspace bind`() {
        val tmpfs = argv.indexOf("--tmpfs")
        val bind = argv.indexOf("--bind")

        assertTrue(
            tmpfs >= 0 && bind >= 0 && tmpfs < bind,
            "a tmpfs mounted after the workspace bind hides a workspace under it (measured): $argv",
        )
    }

    @Test
    fun `the paths the run must read are bound read-only`() {
        assertTrue(
            argv.windowed(size = 3).any {
                it == listOf("--ro-bind", "/tmp/pipelinek-control", "/tmp/pipelinek-control")
            },
            "the control directory is visible and read-only: the run reads its own program " +
                "there and writes nothing there: $argv",
        )
    }

    @Test
    fun `the paths the run must read are mounted after the tmpfs that would hide them`() {
        val tmpfs = argv.indexOf("--tmpfs")
        val readable = indexOfReadOnlyBind()

        assertTrue(
            tmpfs >= 0 && readable > tmpfs,
            "a tmpfs mounted after the readable bind hides the control directory, and a run " +
                "that cannot see its own script does not run at all (measured): $argv",
        )
    }

    @Test
    fun `the workspace is bound after the read-only binds`() {
        val readable = indexOfReadOnlyBind()
        val bind = argv.indexOf("--bind")

        assertTrue(
            readable >= 0 && bind > readable,
            "a read-only bind of a directory that CONTAINS the workspace leaves the workspace " +
                "read-only too, and the run would have nowhere to write (measured): $argv",
        )
    }

    /**
     * Where the read-only bind of the control directory sits in the argv.
     *
     * The argv holds the flag and its two paths as three separate words, so the
     * bind is found by the PATH it binds and the word in front of it.
     */
    private fun indexOfReadOnlyBind(): Int {
        val path = argv.indexOf("/tmp/pipelinek-control")
        if (path <= 0 || argv[path - 1] != "--ro-bind") return -1
        return path - 1
    }

    @Test
    fun `the run gets its own namespaces`() {
        val namespaces = listOf(
            "--unshare-user",
            "--unshare-pid",
            "--unshare-net",
            "--unshare-ipc",
            "--unshare-uts",
        )

        assertTrue(
            namespaces.all { it in argv },
            "the boundary is the namespaces, not the mounts alone: $argv",
        )
    }

    @Test
    fun `the command runs after the separator`() {
        val separator = argv.indexOf("--")

        assertTrue(separator >= 0, "the flags and the command are separated: $argv")
        assertEquals(
            listOf("/tmp/control/script.sh"),
            argv.drop(separator + 1),
            "the command is the last thing in the argv: $argv",
        )
    }
}
