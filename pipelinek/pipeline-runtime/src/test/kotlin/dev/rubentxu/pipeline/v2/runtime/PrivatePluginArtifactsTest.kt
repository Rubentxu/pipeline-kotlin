package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.step.AdmittedPlugin
import dev.rubentxu.pipeline.v2.domain.step.ArtifactOrigin
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.MeasuredArtifactIdentity
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The private copy of a plugin artifact, and the guarantee that the copy is what runs.
 *
 * ## The guarantee
 *
 * The runtime measures an artifact's bytes, admits it on that measurement, and then loads it.
 * Between the measurement and the load there is a window in which the file on disk can be
 * replaced, and a second measurement did not close it: the classloader reads the file AFTER
 * the last measurement, so the bytes that run were never the bytes that were verified.
 *
 * The copy closes the window. The artifact is copied into a directory only the operator can
 * read, the COPY is measured and compared with the digest that was admitted, and the COPY is
 * what the composition is given. What was verified is what runs.
 *
 * ## Why this instrument
 *
 * The copy is a decision over bytes and a filesystem, and the cheapest instrument that can
 * fail is to make the copy and read what came back. There is no classloader here and no
 * process: the guarantee under test is about WHICH BYTES are loaded, and the copy is where
 * that is decided.
 *
 * ## What this does not freeze
 *
 * The name of the copy, the order of the copies, and the wording of the refusal. The rows
 * check that the copy is INSIDE the private directory, that it is not the file the operator
 * named, and that its bytes are the bytes that were admitted.
 *
 * ## What this instrument cannot see
 *
 * That the composition hands the COPY to the classloader. That is the wiring between this
 * decision and the loader, and it is audited where it is written.
 */
class PrivatePluginArtifactsTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the copy lives in a directory only the operator can read`() {
        val artifact = artifact("plugin.jar", "the plugin's bytes")
        val digest = measuredDigestOf(artifact)
        val copies = root.resolve("copies")

        val outcome = PrivatePluginArtifacts.copyAll(
            admitted = listOf(admittedArtifact(artifact, digest)),
            measured = mapOf(artifact to digest),
            into = copies,
        )

        assertInstanceOf(PrivatePluginArtifacts.CopyOutcome.Copied::class.java, outcome, "the copy failed")
        val permissions = Files.getPosixFilePermissions(copies)
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            permissions,
            "the copies are in a directory other users of the machine can reach",
        )
    }

    @Test
    fun `the artifact the composition is given is the copy and not the file the operator named`() {
        val artifact = artifact("plugin.jar", "the plugin's bytes")
        val digest = measuredDigestOf(artifact)
        val copies = root.resolve("copies")

        val outcome = PrivatePluginArtifacts.copyAll(
            admitted = listOf(admittedArtifact(artifact, digest)),
            measured = mapOf(artifact to digest),
            into = copies,
        )

        val copied = assertInstanceOf(
            PrivatePluginArtifacts.CopyOutcome.Copied::class.java,
            outcome,
            "the copy failed",
        ).artifacts.single()
        assertNotEquals(artifact, copied.path, "the composition was given the operator's own file")
        assertTrue(
            copied.path.startsWith(copies),
            "the copy is not inside the private directory: ${copied.path}",
        )
        assertEquals(
            digest.value,
            PluginManifestResourceReader.digestOfArtifactAt(copied.path)?.value,
            "the copy does not hold the bytes that were admitted",
        )
    }

    @Test
    fun `an artifact that changed between the measurement and the copy is refused`() {
        val artifact = artifact("plugin.jar", "the plugin's bytes")
        val digest = measuredDigestOf(artifact)
        // The file is replaced AFTER the runtime measured it and BEFORE the copy is made.
        Files.writeString(artifact, "different bytes entirely")

        val outcome = PrivatePluginArtifacts.copyAll(
            admitted = listOf(admittedArtifact(artifact, digest)),
            measured = mapOf(artifact to digest),
            into = root.resolve("copies"),
        )

        val refused = assertInstanceOf(
            PrivatePluginArtifacts.CopyOutcome.Refused::class.java,
            outcome,
            "an artifact whose bytes are not the bytes that were admitted must not be loaded",
        )
        assertEquals(artifact, refused.artifact, "the refusal names the artifact that changed")
        assertInstanceOf(
            PluginManifestRejection.ArtifactChangedDuringAdmission::class.java,
            refused.rejection,
            "the refusal says the bytes changed between the measurement and the load",
        )
    }

    /** An artifact on disk, with the bytes it holds. */
    private fun artifact(name: String, content: String): Path {
        val path = root.resolve(name)
        Files.writeString(path, content)
        return path
    }

    private fun measuredDigestOf(artifact: Path): Digest =
        requireNotNull(PluginManifestResourceReader.digestOfArtifactAt(artifact)) {
            "the artifact's bytes could not be measured, so nothing could be decided about them"
        }

    /** An admitted artifact, as the admission hands it to the composition. */
    private fun admittedArtifact(artifact: Path, digest: Digest): AdmittedArtifact {
        val manifest = OperatorPluginFixture.manifest()
        return AdmittedArtifact(
            path = artifact,
            plugin = AdmittedPlugin(
                manifest = manifest,
                identity = MeasuredArtifactIdentity(
                    declaredDigest = manifest.release.digest,
                    measuredDigest = digest,
                    origin = ArtifactOrigin.LocalClasspathEntry(artifact.toString()),
                ),
            ),
        )
    }
}
