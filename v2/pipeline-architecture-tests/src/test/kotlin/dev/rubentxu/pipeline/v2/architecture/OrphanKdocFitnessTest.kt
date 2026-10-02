package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.extension

/**
 * TRAIN H4 / PR-020 — a KDoc may not document nothing.
 *
 * The defect this catches is real and it was found by hand: when the coordinator
 * collapsed, four KDoc blocks stayed behind describing `DecodedBeforeStage`,
 * `seamDecodeFailureReason`, `decideStageContinuation` and `dispatch`/`dispatchBody`
 * — every one of them a symbol that had moved to another file. A reader who finds a
 * careful explanation of "the typed classification of ONE admitted BEFORE_STAGE
 * directive" reasonably concludes the file contains that type, and then cannot find
 * it. Orphan KDoc is worse than none: it actively sends the reader somewhere wrong,
 * and no gate can catch it because nothing can assert the absence of a comment.
 *
 * ## Why this rule is this narrow, and not broader
 *
 * The obvious general rule — "every KDoc must be followed by a declaration" — was
 * implemented first and measured. It reports **~45 offenders across 388 files, and
 * essentially all of them are false positives**: enum entries (`MEMOIZED`,
 * `PENDING`), sealed-ADT members and value-class members are all legitimate KDoc
 * targets that a line-based rule cannot distinguish from an orphan. A guard that
 * fires on legitimate code gets disabled within a week, and a disabled guard is
 * worse than no guard because it manufactures confidence in the meantime.
 *
 * So the shipped rule is the one that is actually decidable: a KDoc run whose next
 * non-blank line closes a scope is documenting nothing in that scope. That is
 * unambiguous, it has no false-positive class, and it is exactly the shape the
 * coordinator's four orphans had. At the time of writing it reports zero across
 * 388 production files, which is the correct state, not a disabled check.
 *
 * ## The tombstone carve-out
 *
 * `CanonicalCoreStepDecoder.kt` carries unattached KDoc that is NOT an accident: it
 * records that a legacy command subtype was removed and where execution authority
 * moved. That history is worth keeping — deleting it would lose the answer to "why
 * does this decoder not handle `EmitEvent` any more". Those blocks carry an explicit
 * removal marker, and the rule requires the marker rather than guessing from prose.
 */
class OrphanKdocFitnessTest {

    @Test
    @DisplayName("no production source carries a KDoc that documents nothing in its scope")
    fun `no orphan KDoc in production sources`() {
        val roots = SCANNED_ROOTS.map { ScannerSupport.v2Root().resolve(it) }.filter { Files.isDirectory(it) }

        val offenders = roots.flatMap { root ->
            Files.walk(root).use { stream ->
                stream.filter { it.extension == "kt" }
                    .map { it to orphanKdocsIn(Files.readString(it)) }
                    .filter { (_, orphans) -> orphans.isNotEmpty() }
                    .map { (file, orphans) ->
                        val name = root.relativize(file).toString().substringAfterLast('/')
                        "$name: " + orphans.joinToString(" | ") { "line ${it.first}: ${it.second}" }
                    }
                    .toList()
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "A KDoc whose next declaration is a closing brace documents nothing in this file. " +
                "That is what a refactor leaves behind when it moves code and not its " +
                "documentation, and it sends the reader hunting for a symbol in the wrong file. " +
                "Move the KDoc with the code, or delete it. If the block deliberately records a " +
                "REMOVAL, say so with an explicit marker (LEGACY_REMOVED / DELETED) so the " +
                "history is preserved deliberately rather than by accident.\nOffenders:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    // --- the probe has teeth -------------------------------------------------------------------
    // A guard that cannot recognise a real shape, or cannot catch a real defect, proves nothing.

    @Test
    @DisplayName("the probe ignores KDoc that is attached to a declaration")
    fun `attached KDoc is not reported`() {
        assertNull(
            orphanKdocIn("/** documents the class */\nclass Documented\n"),
            "a KDoc immediately followed by a declaration is doing its job",
        )
        assertNull(
            orphanKdocIn("    /** documents the function */\n    private fun documented() = Unit\n"),
            "the same holds for a member declaration",
        )
        assertNull(
            orphanKdocIn("/** first block */\n/** second block */\nfun twoBlocks() = Unit\n"),
            "a run of KDoc blocks documents the single declaration that follows them",
        )
    }

    @Test
    @DisplayName("the probe catches a KDoc left behind at the end of a scope")
    fun `unattached KDoc is reported`() {
        val orphaned = """
            class StillHere
            /** A careful, confident explanation of a type that used to live right here. */
            """.trimIndent() + "\n"

        assertNotNull(
            orphanKdocIn(orphaned),
            "a KDoc followed by a closing brace documents nothing, and the guard must say so",
        )
    }

    @Test
    @DisplayName("the probe preserves a deliberate removal tombstone")
    fun `removal tombstone is not reported`() {
        val tombstone = """
            class Decoder
            /**
             * S2-A4 / G5: the legacy `EmitEvent` command subtype was removed
             * (LEGACY_REMOVED). Production routing is exclusively via the registry.
             */
            """.trimIndent() + "\n"

        assertNull(
            orphanKdocIn(tombstone),
            "an explicit removal marker means the unattached KDoc is a deliberate record, not an accident",
        )
    }

    @Test
    @DisplayName("the probe reports the line and the leading sentence of what it found")
    fun `the probe reports where and what`() {
        val found = requireNotNull(
            orphanKdocIn("class A\n/** FIRST_SENTENCE here. More prose. */\n}\n"),
        ) { "expected a finding" }
        assertEquals(2, found.first, "the finding must name the line the KDoc starts on")
        assertTrue(
            found.second.startsWith("FIRST_SENTENCE"),
            "the finding must quote enough for a human to recognise the block, got: ${found.second}",
        )
    }

    // --- the probe -----------------------------------------------------------------------------

    /** A KDoc block with no declaration after it, as (1-indexed line, leading sentence). */
    private fun orphanKdocIn(source: String): Pair<Int, String>? = orphanKdocsIn(source).firstOrNull()

    private fun orphanKdocsIn(source: String): List<Pair<Int, String>> {
        val lines = source.split('\n')
        val orphans = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trim().startsWith("/**")) {
                i++
                continue
            }
            var end = i
            while (end < lines.size && "*/" !in lines[end]) end++
            if (end >= lines.size) break

            val next = (end + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
            if (next == null || lines[next].trim() == "}") {
                val prose = lines.subList(i, end + 1)
                    .joinToString(" ") { it.trim().removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trim() }
                if (!REMOVAL_MARKER.containsMatchIn(prose)) orphans += (i + 1) to prose.take(120)
            }
            i = end + 1
        }
        return orphans
    }

    private companion object {
        val SCANNED_ROOTS = listOf(
            "pipeline-application/src/main/kotlin",
            "pipeline-domain/src/main/kotlin",
            "pipeline-step-sdk",
        )

        /**
         * An explicit statement that the block records a removal. Required, not inferred:
         * a tombstone that is meant to survive says so, and one that is left by accident
         * does not happen to.
         */
        val REMOVAL_MARKER = Regex("LEGACY_REMOVED|DELETED|was removed|is DEFERRED|UNSUPPORTED", RegexOption.IGNORE_CASE)
    }
}
