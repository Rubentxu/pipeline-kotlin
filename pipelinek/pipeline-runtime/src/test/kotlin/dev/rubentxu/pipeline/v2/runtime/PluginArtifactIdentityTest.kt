package dev.rubentxu.pipeline.v2.runtime

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The identity of an artifact, as the admission sees it.
 *
 * ## The guarantee
 * Two artifacts with the same content are the same artifact, and they are admitted as one. The
 * composition refuses a second arrival of an identity whose bytes disagree with the first —
 * that refusal is the protection against two different artifacts answering to one name — and it
 * must not fire when the bytes agree.
 *
 * ## Why the artifact is a DIRECTORY here
 * An exploded build is measured twice: once when the runtime reads it, and once when the copy
 * it is about to load is verified. A digest that depends on where the artifact was read cannot
 * survive that, and the copy of a directory artifact is refused as "the artifact changed between
 * the measurement and the copy" — an accusation about an artifact that did not change.
 *
 * ## Why this instrument
 * The admission is a decision over paths and digests, and the cheapest instrument that can fail
 * is to lay two trees out and ask the runtime to admit them. No plugin code runs: the admission
 * happens BEFORE any class from the artifact is linked, which is the whole point of it.
 */
class PluginArtifactIdentityTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `two identical trees in different places are one artifact, and neither is refused`() {
        val first = OperatorPluginFixture.directory(root.resolve("one"))
        val second = OperatorPluginFixture.directory(root.resolve("two"))

        val measurements = listOf(first, second).map { candidate ->
            val digest = requireNotNull(PluginManifestResourceReader.digestOfArtifactAt(candidate)) {
                "the artifact at $candidate could not be measured, so nothing could be decided about it"
            }
            PluginArtifactIntegrity.Measurement.Measured(candidate, digest)
        }

        val selection = PluginArtifactIntegrity.select(measurements)
        val selected = assertInstanceOf(
            PluginArtifactIntegrity.Selection.Admitted::class.java,
            selection,
            "two artifacts with the same content were told apart",
        ).artifacts

        val admitted = PreLoadPluginAdmission.admitAll(
            artifacts = selected.map { it.path },
            measured = selected.associate { it.path to it.digest },
            runtimeVersion = OperatorPluginFixture.RUNTIME_VERSION,
        )

        assertInstanceOf(
            AdmittedArtifacts.Admitted::class.java,
            admitted,
            "the second arrival of an identity was refused even though its bytes are the same",
        )
        assertTrue(
            selected.size == 1,
            "the same artifact was admitted twice: ${selected.map { it.path }}",
        )
    }
}
