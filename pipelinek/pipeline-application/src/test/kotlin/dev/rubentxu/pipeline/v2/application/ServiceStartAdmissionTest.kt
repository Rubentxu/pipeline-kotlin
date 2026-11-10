package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The names a client may use to ask for a run.
 *
 * ## The guarantee under trial
 *
 * That a client names a script and a workspace BY NAME, and that the name is
 * resolved inside the directory the operator declared and NOWHERE ELSE. The
 * boundary is the directory: a name that leaves it —because it is absolute,
 * because it walks up, or because it is a link that points out of it— is
 * refused, and it is refused as a NAME that does not resolve and not as an
 * exception.
 *
 * ## The capacity this serves
 *
 * An operator who points the service at a directory of scripts gets a socket
 * that can run THOSE scripts. A socket that could run any path on the machine
 * would not be a service with a boundary, it would be a remote shell with a
 * JSON hat.
 *
 * ## Why the resolution follows the links
 *
 * Because a name that resolves to a symbolic link IS the link's target: the
 * comparison is made on the real path of the candidate and on the real path
 * of the root, so a link that points out of the directory buys the client
 * nothing and is refused exactly like a name with `..` in it.
 *
 * ## What this does not freeze
 *
 * Whether the script COMPILES and whether the run succeeds: those are the
 * run's own facts, and they are reported by the run.
 */
class ServiceStartAdmissionTest {

    @Test
    fun `a name inside the directory is admitted`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.writeString(scripts.resolve("build.kts"), "print(1)")

        val outcome = ServiceStartAdmission(scripts, null).admit("build.kts", null)

        val admitted = assertInstanceOf(
            ServiceStartAdmissionOutcome.Admitted::class.java,
            outcome,
            "a name that names a file of the directory is admitted",
        )
        assertEquals(scripts.resolve("build.kts").toRealPath(), admitted.script)
        assertNull(admitted.workspace, "no workspace was named, so none is admitted")
    }

    @Test
    fun `a name with a directory inside it is admitted`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        val nested = Files.createDirectories(scripts.resolve("sub"))
        Files.writeString(nested.resolve("build.kts"), "print(1)")

        val outcome = ServiceStartAdmission(scripts, null).admit("sub/build.kts", null)

        val admitted = assertInstanceOf(ServiceStartAdmissionOutcome.Admitted::class.java, outcome)
        assertEquals(
            nested.resolve("build.kts").toRealPath(),
            admitted.script,
            "a name with a separator is a name too, as long as it stays inside the directory",
        )
    }

    @Test
    fun `a workspace inside the directory is admitted`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        val workspaces = Files.createDirectories(root.resolve("workspaces"))
        Files.writeString(scripts.resolve("build.kts"), "print(1)")
        Files.createDirectory(workspaces.resolve("scratch"))

        val outcome = ServiceStartAdmission(scripts, workspaces).admit("build.kts", "scratch")

        val admitted = assertInstanceOf(ServiceStartAdmissionOutcome.Admitted::class.java, outcome)
        assertEquals(workspaces.resolve("scratch").toRealPath(), admitted.workspace)
    }

    @Test
    fun `an absolute name is refused`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        val elsewhere = Files.writeString(root.resolve("other.kts"), "print(1)")

        val outcome = ServiceStartAdmission(scripts, null).admit(elsewhere.toString(), null)

        val refused = assertInstanceOf(ServiceStartAdmissionOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.ScriptNotAdmitted(elsewhere.toString()),
            refused.refusal,
            "an absolute name is not a name inside the directory",
        )
    }

    @Test
    fun `a name that walks up is refused`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.writeString(root.resolve("other.kts"), "print(1)")

        val outcome = ServiceStartAdmission(scripts, null).admit("../other.kts", null)

        val refused = assertInstanceOf(ServiceStartAdmissionOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.ScriptNotAdmitted("../other.kts"),
            refused.refusal,
            "a name that walks up leaves the directory, so it is refused",
        )
    }

    @Test
    fun `a name that is a link out of the directory is refused`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        val outside = Files.writeString(root.resolve("other.kts"), "print(1)")
        val link = scripts.resolve("escape.kts")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.io.IOException) {
            return
        }

        val outcome = ServiceStartAdmission(scripts, null).admit("escape.kts", null)

        assertInstanceOf(
            ServiceStartAdmissionOutcome.Refused::class.java,
            outcome,
            "a link that points out of the directory is refused like a name that leaves it",
        )
    }

    @Test
    fun `a name that names a directory is not a script`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        Files.createDirectory(scripts.resolve("build.kts"))

        val outcome = ServiceStartAdmission(scripts, null).admit("build.kts", null)

        assertInstanceOf(
            ServiceStartAdmissionOutcome.Refused::class.java,
            outcome,
            "a directory is not a script, and the kind of the file is what decides",
        )
    }

    @Test
    fun `a name that does not exist is refused`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))

        val outcome = ServiceStartAdmission(scripts, null).admit("nothing.kts", null)

        val refused = assertInstanceOf(ServiceStartAdmissionOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.ScriptNotAdmitted("nothing.kts"),
            refused.refusal,
            "a name that names nothing is refused by name",
        )
    }

    @Test
    fun `a workspace that names a file is refused`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))
        val workspaces = Files.createDirectories(root.resolve("workspaces"))
        Files.writeString(scripts.resolve("build.kts"), "print(1)")
        Files.writeString(workspaces.resolve("scratch"), "not a directory")

        val outcome = ServiceStartAdmission(scripts, workspaces).admit("build.kts", "scratch")

        val refused = assertInstanceOf(ServiceStartAdmissionOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.WorkspaceNotAdmitted("scratch"),
            refused.refusal,
            "a workspace is a directory, and the refusal says which name was wrong",
        )
    }

    @Test
    fun `a service without a directory of scripts starts no run`(@TempDir root: Path) {
        val outcome = ServiceStartAdmission(null, null).admit("build.kts", null)

        val refused = assertInstanceOf(ServiceStartAdmissionOutcome.Refused::class.java, outcome)
        assertEquals(
            ServiceRefusal.ScriptNotAdmitted("build.kts"),
            refused.refusal,
            "a service that was not given a directory of scripts admits no name",
        )
    }

    @Test
    fun `a blank name is refused`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))

        val outcome = ServiceStartAdmission(scripts, null).admit("   ", null)

        assertInstanceOf(
            ServiceStartAdmissionOutcome.Refused::class.java,
            outcome,
            "a blank name names nothing",
        )
    }

    @Test
    fun `the refusal of a name says which name was refused`(@TempDir root: Path) {
        val scripts = Files.createDirectories(root.resolve("scripts"))

        val outcome = ServiceStartAdmission(scripts, null).admit("missing.kts", null)

        val refused = assertInstanceOf(ServiceStartAdmissionOutcome.Refused::class.java, outcome)
        assertTrue(
            (refused.refusal as ServiceRefusal.ScriptNotAdmitted).script == "missing.kts",
            "the refusal carries the name, so a client can ask for another one",
        )
    }
}
