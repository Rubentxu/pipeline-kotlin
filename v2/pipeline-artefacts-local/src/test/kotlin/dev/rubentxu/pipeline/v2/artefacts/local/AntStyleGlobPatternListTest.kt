package dev.rubentxu.pipeline.v2.artefacts.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How a list of patterns is read.
 *
 * Several fields carry "a list of Ant patterns" in one `String`, separated by commas. The comma
 * is also Ant's own separator inside an alternation, and the two used to be read as the same
 * thing: `build/**/*.{jar,war}` was split into `build/**/*.{jar` and `war}`, and neither
 * fragment matches anything. The caller then reported "matched no files" — or, with
 * `allowEmptyArchive`, archived an empty tar and reported success.
 *
 * So a comma separates patterns only at the top level. Not inside `{...}`, not inside `[...]`.
 *
 * The rule lives next to the dialect it belongs to, so both adapters that read such a field
 * (archive and stash) read it the same way instead of carrying a copy each.
 */
class AntStyleGlobPatternListTest {

    @Test
    fun `a plain list splits on its commas`() {
        assertEquals(
            listOf("src/**", "docs/*.md"),
            AntStyleGlob.splitPatternList("src/**, docs/*.md"),
        )
    }

    @Test
    fun `a comma inside a brace group is not a separator`() {
        // THE DEFECT. Split on every comma, this pattern came back as two broken fragments that
        // match nothing.
        assertEquals(
            listOf("build/**/*.{jar,war}"),
            AntStyleGlob.splitPatternList("build/**/*.{jar,war}"),
        )
    }

    @Test
    fun `a list keeps its groups and still splits around them`() {
        // The comma inside the group does not split, and the commas outside it still do.
        assertEquals(
            listOf("src/**", "build/**/*.{jar,war}", "docs/*.md"),
            AntStyleGlob.splitPatternList("src/**, build/**/*.{jar,war}, docs/*.md"),
        )
    }

    @Test
    fun `a comma inside a character class is not a separator`() {
        // The same rule one level down: inside `[...]` a comma is a member of the class.
        assertEquals(
            listOf("data/[a,b].txt"),
            AntStyleGlob.splitPatternList("data/[a,b].txt"),
        )
    }

    @Test
    fun `entries are trimmed and the empty ones are dropped`() {
        assertEquals(
            listOf("a.txt", "b.txt"),
            AntStyleGlob.splitPatternList("  a.txt , , b.txt  ,"),
        )
    }

    @Test
    fun `an empty list is an empty list`() {
        assertEquals(emptyList<String>(), AntStyleGlob.splitPatternList(""))
    }
}
