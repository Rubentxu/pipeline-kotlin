package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The artifact-integrity guarantee, observed where it is decided.
 *
 * The runtime composes over plugin artifacts, and every run with a plugin on the classpath goes
 * through this decision. Three facts have to hold for that composition to be trustworthy, and each
 * one is a way the runtime could have been wrong:
 *
 *  - an artifact whose bytes cannot be read stops the run instead of being skipped;
 *  - two paths that hold the same bytes are one artifact, loaded once;
 *  - the bytes that were read before admission are the bytes that get loaded.
 *
 * The decision is exercised over values in memory, so a regression in any of the three is visible
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

    @Test
    @DisplayName("bytes that did not change are not a change")
    fun bytesThatDidNotChangeAreNotAChange() {
        val before = listOf(measured(first, digest('a')), measured(second, digest('b')))

        val changed = PluginArtifactIntegrity.changed(
            before = before,
            after = mapOf(first to digest('a'), second to digest('b')),
        )

        assertNull(changed)
    }

    @Test
    @DisplayName("bytes that changed are refused, naming the artifact and both digests")
    fun bytesThatChangedAreRefusedWithBothDigests() {
        val before = listOf(measured(first, digest('a')), measured(second, digest('b')))

        val changed = PluginArtifactIntegrity.changed(
            before = before,
            after = mapOf(first to digest('a'), second to digest('f')),
        )

        // The payload, not just "it is not null": a report that named the artifact but not the two
        // digests would leave an operator unable to tell which file to quarantine.
        assertEquals(
            PluginArtifactIntegrity.Changed(second, digest('b'), digest('f')),
            changed,
        )
    }

    @Test
    @DisplayName("an artifact that can no longer be read is a change, not an absence of one")
    fun anArtifactThatBecameUnreadableIsAChange() {
        val before = listOf(measured(first, digest('a')))

        val changed = PluginArtifactIntegrity.changed(
            before = before,
            after = mapOf(first to null),
        )

        assertEquals(
            PluginArtifactIntegrity.Changed(first, digest('a'), null),
            changed,
        )
    }

    @Test
    @DisplayName("a digest written with different hex case is not a change")
    fun aDigestInDifferentCaseIsNotAChange() {
        val before = listOf(measured(first, Digest("sha256:" + "a".repeat(64))))

        val changed = PluginArtifactIntegrity.changed(
            before = before,
            after = mapOf(first to Digest("sha256:" + "A".repeat(64))),
        )

        assertNull(changed)
    }
}
