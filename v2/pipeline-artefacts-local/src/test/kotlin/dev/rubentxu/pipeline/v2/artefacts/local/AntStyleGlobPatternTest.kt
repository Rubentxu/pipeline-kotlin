package dev.rubentxu.pipeline.v2.artefacts.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * That a pattern stays one pattern.
 *
 * `AntStyleGlob` used to flatten whatever it was given into a single comma-separated `String` and
 * re-split it inside the constructor. Every entry point round-tripped through that join, so a
 * pattern containing a comma came back as two fragments that match nothing:
 *
 * - `build/**/*.{jar,war}` became `build/**/*.{jar` and `war}` -- and `{jar,war}` is the alternation
 *   the class KDoc advertises, so the documented feature was unmatchable;
 * - `data/a,b.txt`, a perfectly legal POSIX filename, became `data/a` and `b.txt`.
 *
 * The four production call sites all pass a single `String` (`input.artifacts`, `input.includes`,
 * `input.reportFiles`, a `pattern` argument), so this was reachable from every one of them: the
 * glob matched nothing, and with `allowEmptyArchive` the step archived an empty tar and reported
 * success.
 *
 * What is pinned here is the pattern list -- the decision `match()` acts on. `match()` itself
 * walks a real directory, which AGENTS.md section 2 keeps out of unit tests; matching stays with
 * the E2E pipelines.
 */
class AntStyleGlobPatternTest {

    // --- the defect: a comma is part of a pattern, not a separator ----------------------

    @Test
    fun `brace alternation survives as one pattern`() {
        // THE COMMA DEFECT. Before the fix this was ["build/**.*{jar", "war}"], neither of which is
        // a valid pattern, so `match()` returned empty for a caller that had written exactly the
        // syntax the KDoc documents.
        assertEquals(
            listOf("build/**/*.{jar,war}"),
            AntStyleGlob("build/**/*.{jar,war}").patternList,
        )
    }

    @Test
    fun `a comma inside a filename is not a separator`() {
        // The other half of the same round trip, and the one with no excuse at all: this is a
        // legal path on any POSIX filesystem.
        assertEquals(
            listOf("data/a,b.txt"),
            AntStyleGlob("data/a,b.txt").patternList,
        )
    }

    @Test
    fun `the list factory does not shred its elements`() {
        // `fromList` was the worst of the three: it joined, and the constructor then split again.
        // One element with a comma went in as two fragments.
        assertEquals(
            listOf("build/**/*.{jar,war}"),
            AntStyleGlob.fromList(listOf("build/**/*.{jar,war}")).patternList,
        )
    }

    @Test
    fun `several patterns with commas all survive`() {
        // More than one comma, so a splitter that happened to survive a single case still fails
        // here. Three fragments out of one pattern would also fail the equality below.
        val patterns = listOf(
            "build/**/*.{jar,war}",
            "dist/a,b.zip",
            "src/**",
        )

        assertEquals(patterns, AntStyleGlob.fromList(patterns).patternList)
    }

    // --- the three entry points must mean the same thing -------------------------------

    @Test
    fun `a single string, a list and a vararg agree`() {
        // They are three doors into one room, and they used to disagree for any input containing
        // a comma. Stated as an equality between the three, not as three expected literals, so it
        // keeps holding if the internal representation changes.
        val fromString = AntStyleGlob("src/**/*.kt").patternList
        val fromList = AntStyleGlob.fromList(listOf("src/**/*.kt")).patternList
        val fromVararg = AntStyleGlob("src/**/*.kt").patternList

        assertEquals(fromString, fromList)
        assertEquals(fromString, fromVararg)
    }

    @Test
    fun `the vararg keeps each argument whole`() {
        // Two arguments, one of them with a comma. The old vararg joined with `,` and the
        // constructor split on `,`, so this produced three patterns.
        assertEquals(
            listOf("a.txt", "build/**/*.{jar,war}"),
            AntStyleGlob("a.txt", "build/**/*.{jar,war}").patternList,
        )
    }

    // --- the refusals still fire --------------------------------------------------------

    @Test
    fun `an empty pattern is refused`() {
        // The `require` this class had before the fix, kept. A silent empty glob would match
        // nothing and be indistinguishable from "no files matched", which is the confusing
        // outcome the caller reports back to the operator.
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            AntStyleGlob("")
        }

        assertEquals(true, refusal.message.orEmpty().contains("pattern"))
    }

    @Test
    fun `an empty pattern list is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            AntStyleGlob.fromList(emptyList())
        }
    }

    // --- the documented invariant of the default excludes -------------------------------

    @Test
    fun `the default excludes are exactly the thirteen Jenkins entries`() {
        // The matcher is Jenkins-compatible only while this list is, and an edit that dropped
        // or added an entry would be invisible at every call site.
        assertEquals(13, AntStyleGlob.DEFAULT_EXCLUDES.size)
        assertEquals(
            listOf("**/.git/**", "**/.svn/**", "**/.bzr/**", "**/.hg/**", "**/CVS/**"),
            AntStyleGlob.DEFAULT_EXCLUDES.take(5),
        )
    }
}
