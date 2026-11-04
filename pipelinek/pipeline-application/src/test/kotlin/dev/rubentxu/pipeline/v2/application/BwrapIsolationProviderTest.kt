package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The rules that turn what the probe READ into what the product PROVES.
 *
 * ## The guarantee
 *
 * A capability appears in the proven list only when the facts that bear on it say
 * so: the root is read-only AND the workspace is read-write AND a write inside
 * the workspace succeeded AND a write outside it was refused because the
 * filesystem is read-only. Anything less is not a filesystem confinement, and a
 * capability that is not proven is not offered.
 *
 * ## Why the facts are a fixture here
 *
 * The facts this instrument reads are this host's, and a row that passed on one
 * host and failed on another would be a row about the machine. The fixture is
 * the facts of a run that WAS confined, and the rows change ONE of them at a
 * time: that is what makes each rule the only difference between a proof and a
 * claim.
 *
 * ## What this instrument does not freeze
 *
 * The wording of the evidence. What is frozen is that the evidence says what was
 * measured, because an evidence string nobody can check is not evidence.
 */
class BwrapIsolationProviderTest {

    /** The network namespace of the process that runs this test, which is the host's. */
    private val hostNetworkNamespace: String =
        Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString()

    /** The facts of a run that WAS confined. */
    private fun confinedFacts(
        rootMount: String = "ro,nosuid,nodev",
        workspaceMount: String = "rw,nosuid,nodev",
        writeInside: String = "ok",
        writeOutside: String = "denied: Read-only file system",
        writeControl: String = "denied",
        networkDevices: String = "lo ",
        networkNamespace: String = "net:[999999997]",
    ): BwrapIsolationProvider.Facts = BwrapIsolationProvider.Facts(
        capabilities = "CapEff:0000000000000000 CapBnd:0000000000000000 ",
        uid = "1000",
        userNamespace = "user:[999999996]",
        pidNamespace = "pid:[999999995]",
        netNamespace = networkNamespace,
        networkDevices = networkDevices,
        rootMount = rootMount,
        workspaceMount = workspaceMount,
        writeInside = writeInside,
        writeOutside = writeOutside,
        writeControl = writeControl,
    )

    @Test
    fun `a confined run proves the capabilities it measured`() {
        val proven = confinedFacts().proven().map { it.capability }

        assertEquals(
            setOf(
                IsolationCapability.FilesystemConfinement,
                IsolationCapability.NetworkConfinement,
                IsolationCapability.UserNamespace,
                IsolationCapability.ProcessConfinement,
            ),
            proven.toSet(),
        )
    }

    @Test
    fun `a writable root does not prove a confined filesystem`() {
        val proven = confinedFacts(rootMount = "rw,nosuid,nodev").proven()

        assertTrue(
            IsolationCapability.FilesystemConfinement !in proven.map { it.capability },
            "a root that can be written is not a confinement",
        )
    }

    @Test
    fun `a write that succeeded outside does not prove a confined filesystem`() {
        val proven = confinedFacts(writeOutside = "ok").proven()

        assertTrue(
            IsolationCapability.FilesystemConfinement !in proven.map { it.capability },
            "a write that reached the system says the boundary was not in force",
        )
    }

    @Test
    fun `a workspace that cannot be written does not prove a confined filesystem`() {
        val proven = confinedFacts(workspaceMount = "ro,nosuid,nodev", writeInside = "denied").proven()

        assertTrue(
            IsolationCapability.FilesystemConfinement !in proven.map { it.capability },
            "a workspace that cannot be written confines nothing",
        )
    }

    @Test
    fun `a writable control directory does not prove a confined filesystem`() {
        val proven = confinedFacts(writeControl = "ok").proven()

        assertTrue(
            IsolationCapability.FilesystemConfinement !in proven.map { it.capability },
            "a control directory the run can write is where it could publish its own verdict",
        )
    }

    @Test
    fun `the host's own network namespace does not prove a confined network`() {
        val proven = confinedFacts(networkNamespace = hostNetworkNamespace).proven()

        assertTrue(
            IsolationCapability.NetworkConfinement !in proven.map { it.capability },
            "the host's namespace is not a boundary",
        )
    }

    @Test
    fun `an interface besides the loopback does not prove a confined network`() {
        val proven = confinedFacts(networkDevices = "lo eth0 ").proven()

        assertTrue(
            IsolationCapability.NetworkConfinement !in proven.map { it.capability },
            "a network that carries an interface is not confined",
        )
    }

    @Test
    fun `the evidence names what was measured`() {
        val evidence = confinedFacts().proven()
            .first { it.capability == IsolationCapability.FilesystemConfinement }
            .evidence

        assertTrue(
            "Read-only file system" in evidence && "rw" in evidence,
            "the evidence says what the boundary did: $evidence",
        )
    }

    @Test
    fun `an output without the facts the decision needs proves nothing`() {
        assertNull(
            BwrapIsolationProvider.Facts.parse("CAPS|CapEff:0000000000000000"),
            "a fact that is missing is a fact that cannot be reasoned about",
        )
    }
}
