package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.step.Digest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The artifact-integrity guarantee, observed where it is decided.
 *
 * The runtime composes over plugin artifacts, and every run with a plugin on the classpath goes
 * through this decision. Two facts have to hold for that composition to be trustworthy, and each
 * one is a way the runtime could have been wrong:
 *
 *  - an artifact whose bytes cannot be read stops the run instead of being skipped;
 *  - two paths that hold the same bytes are one artifact, loaded once.
 *
 * The third fact — that the bytes read before admission are the bytes that get loaded — is not
 * decided here: it is decided by the private copy, because reading the artifact a second time
 * would still leave a window between the reading and the load. That copy is exercised where it
 * happens, over real files, in the test of the copy itself.
 *
 * The decision is exercised over values in memory, so a regression in either of the two is visible
 * without a plugin, a JAR, a classloader or a process. What this test cannot say is that the
 * composition root actually asks the question; that is what the executable examples are for, and
 * they ask it of the shipped binary.
 */
class PluginArtifactIntegrityTest {

    private val first = Path.of("/plugins/first.jar")
    private val second = Path.of("/plugins/second.jar")
    private val third = Path.of("/plugins/third.jar")

    private fun digest(seed: Char): Digest = Digest("sha256:" + seed.toString().repeat(64))

    private fun measured(path: Path, digest: Digest): PluginArtifactIntegrity.Measurement.Measured =
        PluginArtifactIntegrity.Measurement.Measured(path, digest)

    private fun unreadable(path: Path): PluginArtifactIntegrity.Measurement.Unreadable =
        PluginArtifactIntegrity.Measurement.Unreadable(path)

    @Test
    @DisplayName("an artifact whose bytes cannot be read is refused, and the refusal names it")
    fun anUnreadableArtifactIsRefusedByName() {
        val selection = PluginArtifactIntegrity.select(listOf(unreadable(first)))

        assertEquals(
            PluginArtifactIntegrity.Selection.NotMeasurable(first),
            selection,
        )
    }

    @Test
    @DisplayName("the first artifact that cannot be read stops the selection, and nothing is admitted")
    fun theFirstUnreadableArtifactStopsTheSelection() {
        val selection = PluginArtifactIntegrity.select(
            listOf(measured(first, digest('a')), unreadable(second), measured(third, digest('c'))),
        )

        // Not merely "the second one is named": the readable artifacts around it must not slip
        // through either. A selection that carried on past an unreadable artifact would compose
        // over two plugins and report success while one of them was never verified.
        assertEquals(
            PluginArtifactIntegrity.Selection.NotMeasurable(second),
            selection,
        )
    }

    @Test
    @DisplayName("two paths holding the same bytes are one artifact, and the first one offered wins")
    fun twoPathsWithTheSameBytesAreOneArtifact() {
        val selection = PluginArtifactIntegrity.select(
            listOf(measured(first, digest('a')), measured(second, digest('a'))),
        )

        assertEquals(
            PluginArtifactIntegrity.Selection.Admitted(listOf(measured(first, digest('a')))),
            selection,
        )
    }

    @Test
    @DisplayName("two paths holding different bytes are two artifacts, in the order they were offered")
    fun twoPathsWithDifferentBytesAreTwoArtifacts() {
        val selection = PluginArtifactIntegrity.select(
            listOf(measured(first, digest('a')), measured(second, digest('b'))),
        )

        assertEquals(
            PluginArtifactIntegrity.Selection.Admitted(
                listOf(measured(first, digest('a')), measured(second, digest('b'))),
            ),
            selection,
        )
    }

    @Test
    @DisplayName("the same bytes written with different hex case are still the same artifact")
    fun theSameBytesInDifferentCaseAreOneArtifact() {
        val selection = PluginArtifactIntegrity.select(
            listOf(
                measured(first, Digest("sha256:" + "a".repeat(64))),
                measured(second, Digest("sha256:" + "A".repeat(64))),
            ),
        )

        // Hex has no case, so the same bytes written differently are the same bytes. Treating them
        // as two artifacts would load the same plugin twice, under two names, from two paths.
        assertEquals(
            PluginArtifactIntegrity.Selection.Admitted(
                listOf(measured(first, Digest("sha256:" + "a".repeat(64)))),
            ),
            selection,
        )
    }

    @Test
    @DisplayName("an installation with no plugin artifacts admits nothing, and that is not a failure")
    fun noArtifactsAdmitNothing() {
        val selection = PluginArtifactIntegrity.select(emptyList())

        assertEquals(
            PluginArtifactIntegrity.Selection.Admitted(emptyList()),
            selection,
        )
    }
}
