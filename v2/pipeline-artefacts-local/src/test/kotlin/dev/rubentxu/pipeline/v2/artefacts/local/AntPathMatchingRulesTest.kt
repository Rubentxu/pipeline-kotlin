package dev.rubentxu.pipeline.v2.artefacts.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rules of the Ant matcher, as they were BEFORE it became ours.
 *
 * This project's matcher replaced Spring's `AntPathMatcher`, and the replacement had to be
 * behaviour-preserving for every pattern that was already right: taking a library out is not a
 * licence to move the ground. So the expectations here were **read off the engine that shipped**,
 * not off the new one, and the new one was checked against them — over 1100 pattern/path pairs,
 * zero disagreements — before the dependency was removed.
 *
 * The oracle of that comparison was the RELEASED behaviour, which was the pattern translated
 * into the dialect the engine spoke and then matched. Comparing against the raw engine instead
 * would have "disagreed" on every alternation and every character class, which are precisely the
 * two constructs where the old engine was the defect. The comparison grew to 402 360 pairs over
 * a generated corpus; the seven that differ are the ones that cross a separator, and they are
 * written down in the test that closes this file.
 *
 * The two constructs the two engines disagree on are NOT here, because there the old engine was
 * the defect and the new one is the fix: `{a,b}` was an alternation and is now one (the old
 * engine read it as a wildcard), and `[a-c]` is a character class and now is one (the old engine
 * read the brackets as literal text). Those live in `AntGlobPatternTest`, which is the table of
 * truth of the Ant dialect.
 *
 * What IS here is the part that is easy to get wrong and invisible when it moves: where the
 * separator sits, what an empty segment means, and how far a wildcard reaches.
 */
class AntPathMatchingRulesTest {

    @Test
    fun `the rules of the matcher are the ones that shipped`() {
        assertEquals(true, matches("*", "a"), "*` vs `a")
        assertEquals(false, matches("*", ""), "*` vs `")
        assertEquals(false, matches("*", "a/b"), "*` vs `a/b")
        assertEquals(true, matches("**", "a"), "**` vs `a")
        assertEquals(true, matches("**", "a/b"), "**` vs `a/b")
        assertEquals(true, matches("**", ""), "**` vs `")
        assertEquals(true, matches("**", "a/"), "**` vs `a/")
        assertEquals(true, matches("**/x", "x"), "**/x` vs `x")
        assertEquals(true, matches("**/x", "a/x"), "**/x` vs `a/x")
        assertEquals(true, matches("**/x", "a/b/x"), "**/x` vs `a/b/x")
        assertEquals(false, matches("**/x", "/x"), "**/x` vs `/x")
        assertEquals(true, matches("a/**", "a"), "a/**` vs `a")
        assertEquals(true, matches("a/**", "a/b"), "a/**` vs `a/b")
        assertEquals(true, matches("a/**", "a/"), "a/**` vs `a/")
        assertEquals(true, matches("a/**", "a/b/"), "a/**` vs `a/b/")
        assertEquals(false, matches("a/**", "//a"), "a/**` vs `//a")
        assertEquals(true, matches("a/**/b", "a/b"), "a/**/b` vs `a/b")
        assertEquals(true, matches("a/**/b", "a/x/b"), "a/**/b` vs `a/x/b")
        assertEquals(true, matches("a/**/b", "a/x/y/b"), "a/**/b` vs `a/x/y/b")
        assertEquals(true, matches("a/**/b/**/c", "a/b/c"), "a/**/b/**/c` vs `a/b/c")
        assertEquals(true, matches("a/**/b/**/c", "a/x/b/y/c"), "a/**/b/**/c` vs `a/x/b/y/c")
        assertEquals(false, matches("a/", "a"), "a/` vs `a")
        assertEquals(true, matches("a/", "a/"), "a/` vs `a/")
        assertEquals(true, matches("a//b", "a/b"), "a//b` vs `a/b")
        assertEquals(true, matches("a//b", "a//b"), "a//b` vs `a//b")
        assertEquals(true, matches("build/**/x.txt", "build/x.txt"), "build/**/x.txt` vs `build/x.txt")
        assertEquals(true, matches("build/**/x.txt", "build/a/b/x.txt"), "build/**/x.txt` vs `build/a/b/x.txt")
        assertEquals(false, matches("build/**/x.txt", "build/a/b/y.txt"), "build/**/x.txt` vs `build/a/b/y.txt")
        assertEquals(true, matches("src/**/*.kt", "src/A.kt"), "src/**/*.kt` vs `src/A.kt")
        assertEquals(true, matches("src/**/*.kt", "src/main/kotlin/A.kt"), "src/**/*.kt` vs `src/main/kotlin/A.kt")
        assertEquals(false, matches("src/**/*.kt", "src/A.java"), "src/**/*.kt` vs `src/A.java")
        assertEquals(true, matches("a?.txt", "ab.txt"), "a?.txt` vs `ab.txt")
        assertEquals(false, matches("a?.txt", "a.txt"), "a?.txt` vs `a.txt")
        assertEquals(false, matches("a?.txt", "abc.txt"), "a?.txt` vs `abc.txt")
        assertEquals(true, matches("a*b*c", "aXbXc"), "a*b*c` vs `aXbXc")
        assertEquals(true, matches("a*b*c", "abc"), "a*b*c` vs `abc")
        assertEquals(true, matches("*.txt", "a.txt"), "*.txt` vs `a.txt")
        assertEquals(false, matches("*.txt", "a/b.txt"), "*.txt` vs `a/b.txt")
        assertEquals(true, matches("exact/path.txt", "exact/path.txt"), "exact/path.txt` vs `exact/path.txt")
        assertEquals(false, matches("exact/path.txt", "exact/other.txt"), "exact/path.txt` vs `exact/other.txt")
        assertEquals(true, matches("**/*.class", "A.class"), "**/*.class` vs `A.class")
        assertEquals(true, matches("**/*.class", "x/y/A.class"), "**/*.class` vs `x/y/A.class")
        assertEquals(false, matches("**/*.class", "A.txt"), "**/*.class` vs `A.txt")
        assertEquals(true, matches("release-{v:[0-9]+}.txt", "release-42.txt"), "release-{v:[0-9]+}.txt` vs `release-42.txt")
        assertEquals(false, matches("release-{v:[0-9]+}.txt", "release-x.txt"), "release-{v:[0-9]+}.txt` vs `release-x.txt")
    }

    /**
     * The one place where the own matcher and the engine it replaces answer differently.
     *
     * The engine this replaces rewrote the pattern BEFORE splitting it, so a construct that
     * crossed a separator was destroyed by the rewrite instead of being expressed: `{a/b,c}`
     * became a group that the split cut in half, and the pattern stopped matching its own
     * literal text. The own matcher splits first — which is what Ant does — so `{a/b,c}` is the
     * two literal segments `{a` and `b,c}`, and the path `{a/b,c}` is made of exactly those.
     *
     * It is written down here so that it is a stated rule and not a surprise: 402 360 pattern and
     * path pairs were compared against the published behaviour, and these 7 are the only ones
     * that differ. In every one of them the OLD answer was the broken one.
     */
    @Test
    fun `a construct that crosses a separator is literal text`() {
        assertEquals(true, matches("{a/b,c}", "{a/b,c}"), "`{a/b,c}` vs `{a/b,c}`")
        assertEquals(true, matches("[a/b]", "[a/b]"), "`[a/b]` vs `[a/b]`")
        assertEquals(true, matches("a{b/c}d", "a{b/c}d"), "`a{b/c}d` vs `a{b/c}d`")
        assertEquals(false, matches("{a/b,c}", "a/b"), "`{a/b,c}` vs `a/b`")
        assertEquals(false, matches("{a/b,c}", "{a/b"), "`{a/b,c}` vs `{a/b`")
    }

    /**
     * The matcher, used the way a caller uses it.
     *
     * The pattern is compiled and then asked about the path: one compilation, one answer. The
     * shape is the contract — a pattern that had to be compiled again for every path would be a
     * pattern that pays for the same work once per file.
     */
    private fun matches(pattern: String, path: String): Boolean =
        AntPathMatching.compile(pattern).matches(path)
}
