package dev.rubentxu.pipeline.v2.artefacts.local

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What an Ant glob means.
 *
 * `AntStyleGlob` promised Ant and ran Spring's dialect, and the two disagree on the two
 * constructs an Ant user writes most. Both disagreements were measured on Spring 6.2.19, the
 * version this project pinned, before anything was written — and the matcher is now this
 * project's own, so the dialect below is the one the code implements:
 *
 * - `build/**/*.{jar,war}` matched `build/secret.pem`. Spring reads a colon-free `{...}` as a
 *   wildcard, so "a jar OR a war" was "every file under the directory". An operator who wrote
 *   that pattern to archive two extensions got everything, including what sat next to them.
 * - `[a-c].txt` matched a file literally named `[a-c].txt` and nothing else. An operator who
 *   wrote a character range got an empty archive, which `allowEmptyArchive` reports as a success.
 *
 * So these are the semantics of the translated pattern, stated as a table of truth. The seam is
 * pure — no filesystem, no clock, no I/O — which is what AGENTS.md section 2 allows a unit test
 * to touch. The file WALK stays in `AntStyleGlob.match`, where it belongs.
 */
class AntGlobPatternTest {

    // --- the defect: an alternation is an alternation, not a wildcard --------------------

    @Test
    fun `an alternation matches the alternatives and nothing else`() {
        // THE DEFECT. With the old behaviour this pattern matched `build/secret.pem` and
        // `build/app.zip` too, because Spring read `{jar,war}` as "anything".
        val pattern = AntGlobPattern("build/**/*.{jar,war}")

        assertTrue(pattern.matches("build/app.jar"))
        assertTrue(pattern.matches("build/app.war"))
        assertFalse(pattern.matches("build/secret.pem"))
        assertFalse(pattern.matches("build/app.zip"))
    }

    @Test
    fun `an alternation with one alternative is that literal`() {
        // Ant: `{file}` is the text "file". Spring read `{file}` as "any one segment", so the
        // pattern matched `src/other.txt` too.
        val pattern = AntGlobPattern("src/{file}.txt")

        assertTrue(pattern.matches("src/file.txt"))
        assertFalse(pattern.matches("src/other.txt"))
    }

    @Test
    fun `an alternation holds only its own alternatives`() {
        // The group must not swallow the text around it: `ajarb` is the whole path.
        val pattern = AntGlobPattern("a{jar,war}b")

        assertTrue(pattern.matches("ajarb"))
        assertTrue(pattern.matches("awarb"))
        assertFalse(pattern.matches("ajar"))
        assertFalse(pattern.matches("warb"))
    }

    @Test
    fun `an alternative is matched literally`() {
        // The star inside an alternative is a star in the FILENAME. Read as a regex, `*.txt`
        // would not even compile.
        val pattern = AntGlobPattern("{*.txt,*.md}")

        assertTrue(pattern.matches("*.txt"))
        assertTrue(pattern.matches("*.md"))
        assertFalse(pattern.matches("a.txt"))
    }

    // --- the defect: a character class is a class, not literal text ----------------------

    @Test
    fun `a character class matches the members of its range`() {
        // THE DEFECT. With the old behaviour this pattern matched only a file literally named
        // `[a-c].txt`, and none of these.
        val pattern = AntGlobPattern("logs/[a-c].log")

        assertTrue(pattern.matches("logs/a.log"))
        assertTrue(pattern.matches("logs/b.log"))
        assertTrue(pattern.matches("logs/c.log"))
        assertFalse(pattern.matches("logs/d.log"))
    }

    @Test
    fun `a negated character class matches everything else`() {
        // Ant writes negation with `!`; a regex writes it with `^`. The translation has to
        // carry that across, or `!` would be just another member of the class.
        val pattern = AntGlobPattern("logs/[!a].log")

        assertFalse(pattern.matches("logs/a.log"))
        assertTrue(pattern.matches("logs/b.log"))
    }

    @Test
    fun `a character class keeps a comma as one of its members`() {
        // A comma inside a class is a member, not a separator — the same rule the list splitter
        // follows, one level down.
        val pattern = AntGlobPattern("data/[a,b].txt")

        assertTrue(pattern.matches("data/a.txt"))
        assertTrue(pattern.matches("data/b.txt"))
        assertTrue(pattern.matches("data/,.txt"))
        assertFalse(pattern.matches("data/c.txt"))
    }

    // --- the escape hatch: Spring's own dialect, kept verbatim ---------------------------

    @Test
    fun `a spring regex group is kept verbatim`() {
        // `{name:regex}` is not Ant. It is the way to express what Ant cannot, and a pattern
        // that uses it must keep working exactly as it did.
        val pattern = AntGlobPattern("release-{v:[0-9]+}.txt")

        assertTrue(pattern.matches("release-42.txt"))
        assertFalse(pattern.matches("release-x.txt"))
    }

    @Test
    fun `a spring regex group with a comma inside it is kept verbatim`() {
        // The rule that decides between the two dialects is the COLON, not the comma. A regex
        // that contains a comma is common (`{1,3}`); an alternation that contains a colon is not.
        val pattern = AntGlobPattern("{v:[0-9]{1,3}}")

        assertTrue(pattern.matches("42"))
        assertFalse(pattern.matches("1234"))
    }

    // --- what did not change --------------------------------------------------------------

    @Test
    fun `the wildcards keep their ant meaning`() {
        val single = AntGlobPattern("a?.txt")
        val deep = AntGlobPattern("build/**/x.txt")

        assertTrue(single.matches("ab.txt"))
        assertFalse(single.matches("abc.txt"))
        assertTrue(deep.matches("build/x.txt"))
        assertTrue(deep.matches("build/a/b/x.txt"))
        assertFalse(deep.matches("build/a/b/y.txt"))
    }

    @Test
    fun `a comma outside a group is part of the name`() {
        // A legal POSIX filename, and one the glob must not read as two patterns.
        val pattern = AntGlobPattern("data/a,b.txt")

        assertTrue(pattern.matches("data/a,b.txt"))
    }

    @Test
    fun `an unterminated group stays literal`() {
        // There is no group to translate, so the text is left alone rather than guessed at.
        assertTrue(AntGlobPattern("a{b").matches("a{b"))
        assertTrue(AntGlobPattern("a[bc").matches("a[bc"))
    }

    @Test
    fun `the pattern is kept as it was written`() {
        // The source is what error messages quote, so it is never rewritten.
        assertTrue(AntGlobPattern("build/**/*.{jar,war}").source == "build/**/*.{jar,war}")
    }
}
