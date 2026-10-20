package dev.rubentxu.pipeline.v2.sdk.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The Ant pattern compiler that decides WHICH files `cleanWs` deletes.
 *
 * `cleanWs` is destructive: it removes real files from a real workspace. Its safety net is this
 * pure String -> String function, and the net had a hole in it.
 *
 * The defect this file exists for was an off-by-one that consumed a literal. `**build/**`
 * compiled to `^(.*/)*\Qu\E\Qi\E\Ql\E\Qd\E/.*$` — it matched `uild/x.txt` and refused
 * `build/x.txt`. A pipeline asking to clean its build directory deleted a directory named
 * `uild` instead, deleted nothing it asked for, returned 0 (or some other plausible count), and
 * raised no error. Every layer above this function was reporting truthfully about the wrong
 * answer.
 *
 * Nothing here touches a filesystem. The class KDoc states the contract these tests pin:
 * "`**` matches any path segment (including zero)", "`*` matches within a single path segment",
 * "`?` matches a single character". What is NOT covered is the deletion itself, which AGENTS.md
 * section 2 keeps out of unit tests by design.
 */
class AntPatternToRegexTest {

    // `antPatternToRegex` is a pure compiler, but it lives on a class whose constructor takes a
    // workspace resolver. That resolver is never called by anything exercised here, so this
    // lambda is the whole fake: it names a path that is never opened, which keeps the test
    // inside AGENTS.md section 2's ban on filesystem use without pretending the class is static.
    private fun compile(pattern: String): String =
        CleanWsExecutor(workspaceResolver = { _, _ -> Paths.get("/nonexistent") })
            .antPatternToRegex(pattern)

    private fun matches(pattern: String, path: String): Boolean =
        Regex(compile(pattern)).matches(path)

    // --- the defect: a literal after ** was swallowed ------------------------------------

    @Test
    fun `a double star in front of a literal does not consume the literal`() {
        // THE OFF-BY-ONE. The old branch advanced three positions past `**` plus the character
        // that followed it, and that character was never written to the regex. `b` vanished.
        assertTrue(matches("**build/**", "build/x.txt"), "**build/** stopped matching build/x.txt")
    }

    @Test
    fun `a double star before a literal matches at the root and below`() {
        // Both of these are the same single assertion about the emitted regex: the literal has
        // to be present, and `**` has to keep meaning "zero or more segments" in front of it.
        assertTrue(matches("**build/**", "build/deep/nested.txt"))
        assertTrue(matches("**build/**", "a/b/build/deep.txt"))
    }

    @Test
    fun `a double star before a literal does not shift which strings match`() {
        // The same pattern, stated as a REFUSAL. If the literal were dropped from the regex,
        // `uild` would match -- so a test that only checked the positive case would still be
        // green if the compiler had quietly translated `**build/**` into `**uild/**`.
        assertFalse(matches("**build/**", "uild/x.txt"), "**build/** is matching uild/, the literal is lost")
        assertFalse(matches("**build/**", "buildx/y.txt"))
    }

    // --- the shape that masked the defect -----------------------------------------------

    @Test
    fun `a trailing double star followed by a slash is unaffected`() {
        // `**/` is why the off-by-one went unnoticed: the character it consumed was a `/`, and
        // `(.*/)*` needs exactly that `/`. Pinned so the ordinary form is stated before the odd
        // one, and so a future edit that "fixes" `**/` breaks a test that says it worked.
        assertTrue(matches("**/target/**", "target/debug/x.txt"))
        assertTrue(matches("**/target/**", "a/target/debug/x.txt"))
        assertFalse(matches("**/target/**", "arget/debug/x.txt"))
    }

    // --- the ordinary forms, so a regression in them is also a RED ----------------------

    @Test
    fun `a single star stays inside one segment`() {
        assertTrue(matches("*.txt", "a.txt"))
        assertTrue(matches("src/*.txt", "src/a.txt"))
        assertFalse(matches("*.txt", "src/a.txt"), "a single star crossed a separator")
        assertFalse(matches("src/*.txt", "src/deep/a.txt"))
    }

    @Test
    fun `a question mark is exactly one character`() {
        assertTrue(matches("a?.txt", "ab.txt"))
        assertFalse(matches("a?.txt", "a.txt"))
        assertFalse(matches("a?.txt", "abc.txt"))
    }

    @Test
    fun `a trailing double star matches everything below it`() {
        assertTrue(matches("build/**", "build/a.txt"))
        assertTrue(matches("build/**", "build/deep/a.txt"))
        assertFalse(matches("build/**", "src/a.txt"))
        // Zero segments: `**` at the end is "everything including separators".
        assertTrue(matches("**", "a.txt"))
        assertTrue(matches("**", "a/deep/b.txt"))
    }

    @Test
    fun `three stars compile as a double star plus a single star`() {
        // The surplus star used to have its own branch. It no longer needs one: after the fix
        // `***` is `**` followed by `*`, and the ordinary `*` rule handles the rest. Stated so
        // the removal of that branch is a decision on record rather than an omission.
        assertTrue(matches("**build/**", "build/x.txt"))
        assertTrue(matches("***build/**", "build/x.txt"))
    }

    // --- the emitted regex is anchored ---------------------------------------------------

    @Test
    fun `a pattern is anchored at both ends`() {
        // Not anchored at the end, `*.txt` would also match `a.txt.bak` -- and a cleanup step
        // that deletes more than it was told to is the failure this whole function guards.
        assertFalse(matches("*.txt", "a.txt.bak"))
        assertFalse(matches("build/**", "xbuild/a.txt"))
    }

    // --- literal characters are not regex ------------------------------------------------

    @Test
    fun `regex metacharacters in a literal are quoted`() {
        // A path containing `.` or `+` is ordinary. Unquoted, `.` would match any character and
        // the pattern would select files the operator never named.
        assertTrue(matches("a+b.txt", "a+b.txt"))
        assertFalse(matches("a+b.txt", "axb.txt"))
        assertTrue(matches("v1.2.3.txt", "v1.2.3.txt"))
        assertFalse(matches("v1.2.3.txt", "v1x2y3.txt"))
    }

    @Test
    fun `the compiled pattern is a single anchored expression`() {
        // Structure, not values: whatever the literals are, the result must start with `^` and
        // end with `$`, or the anchoring assertions above are passing for another reason.
        val compiled = compile("src/**/target/*.jar")

        assertEquals(true, compiled.startsWith("^"), "compiled pattern is not anchored at the start: $compiled")
        assertEquals(true, compiled.endsWith("$"), "compiled pattern is not anchored at the end: $compiled")
    }
}
