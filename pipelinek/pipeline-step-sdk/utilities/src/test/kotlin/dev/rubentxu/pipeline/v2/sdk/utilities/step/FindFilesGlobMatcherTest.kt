package dev.rubentxu.pipeline.v2.sdk.utilities.step

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The glob compiler that decides WHICH files `core-utils.findFiles` reports.
 * `buildMatcher` is the Jenkins compatibility seam of the step. Java NIO's `glob:` syntax
 * requires `**` to consume at least one path segment, so a two-stars-slash in front of a
 * suffix refuses a file sitting directly under the base — while Jenkins' `findFiles(glob: ...)`
 * matches it. That gap
 * is not academic and it is not self-announcing: a pipeline asking for every `.txt` gets a
 * shorter list, the step exits 0, and nothing above it can tell "fewer files matched" from
 * "the matcher is wrong".
 * The step's own guarantee is therefore not observable from the E2E layer. `findFiles` returns
 * `Unit`, so `examples/steps/26-find-files.pipeline.kts` can only read the process exit
 * code — and a wrong matcher and a right matcher both produce 0. The scenario cannot be
 * strengthened into an assertion about the result count without a new value out of the DSL.
 * What this file covers is the pure decision underneath: `String` in, a [java.nio.file.PathMatcher]
 * out, evaluated against in-memory [java.nio.file.Path] values. No filesystem, no step, no
 * subprocess, no clock — which is what AGENTS.md section 2 means by a unit test. What is NOT
 * covered is the walk, the exclude pass, or the 100_000 cap, which are filesystem behaviour by
 * design and stay with the executable layer.
 * The matcher is applied to `candidate.relativeTo(base)`, so every path here is workspace-
 * relative with no leading `./` and no leading separator. That is the coordinate system the
 * production call site uses, and a path written any other way here would test a shape the
 * product never evaluates.
*/
class FindFilesGlobMatcherTest {

    private fun matches(glob: String, path: String): Boolean =
        CoreUtilsFindFilesStepDefinition.buildMatcher(glob).matches(Paths.get(path))

    // --- the guarantee: a leading ** means "any depth, INCLUDING ZERO" --------------------

    @Test
    fun `a leading double star matches a file directly under the base`() {
        // THE ROW. Java NIO answers `false` to this one on its own -- the stripped
        // matcher is what makes it true. Delete the `|| secondary.matches(path)` from
        // buildMatcher and this row is the only thing in the file that goes red.
        assertTrue(
            matches("**/*.txt", "a.txt"),
            "**/*.txt stopped matching a file at the base root; the zero-segment case is lost",
        )
    }

    @Test
    fun `a leading double star still matches files below the base`() {
        // The other half. Stripping the prefix unconditionally would satisfy the row above and
        // break this one, which is why the assertion is stated in both directions rather than
        // only as "the new behaviour works".
        assertTrue(matches("**/*.txt", "sub/b.txt"))
        assertTrue(matches("**/*.txt", "sub/deep/deeper/c.txt"))
    }

    @Test
    fun `a leading double star matches at every depth at once`() {
        // The guarantee as a single statement: the same glob, zero through three segments.
        assertTrue(matches("**/target/classes", "target/classes"))
        assertTrue(matches("**/target/classes", "a/target/classes"))
        assertTrue(matches("**/target/classes", "a/b/target/classes"))
    }

    // --- the refusal: zero segments is not "match anything" -------------------------------

    @Test
    fun `a leading double star still refuses what the glob does not name`() {
        // Stated as a REFUSAL on purpose. An OR of two matchers is exactly the shape that can
        // degenerate into a constant-true predicate, and a test file containing only positive
        // assertions would stay green through that.
        assertFalse(matches("**/*.txt", "sub/b.md"), "**/*.txt matched a .md file")
        assertFalse(matches("**/*.txt", "a.txt.bak"), "**/*.txt matched a .txt.bak file")
        assertFalse(matches("**/*.txt", "txt/a.md"))
    }

    @Test
    fun `a leading double star keeps the rest of the pattern intact`() {
        // Both matchers apply the WHOLE stripped pattern, not a prefix of it. If the strip had
        // been implemented as "any path under any directory", these would match.
        assertFalse(matches("**/src/main/*.kt", "src/other/main/a.kt"))
        assertTrue(matches("**/src/main/*.kt", "src/main/a.kt"))
        assertTrue(matches("**/src/main/*.kt", "a/b/src/main/a.kt"))
    }

    // --- a glob without the prefix is untouched -------------------------------------------

    @Test
    fun `a glob without a leading double star keeps NIO's own semantics`() {
        // The `stripped == glob` branch returns the primary matcher unwrapped. Without this row
        // a "simplification" that stripped nothing and instead OR-ed a bare `**` would pass every
        // other test in this file while making `*.txt` match files it never named.
        assertTrue(matches("*.txt", "a.txt"))
        assertFalse(matches("*.txt", "sub/b.txt"), "a single star crossed a path separator")
        assertTrue(matches("src/*.kt", "src/a.kt"))
        assertFalse(matches("src/*.kt", "src/deep/a.kt"))
    }

    @Test
    fun `a leading double star is not confused by a double star in the middle`() {
        // Only the PREFIX is stripped. `**/build/**` has a second `**` that must keep meaning
        // "everything below", including the empty remainder.
        assertTrue(matches("**/build/**", "build/a.txt"))
        assertTrue(matches("**/build/**", "build/deep/a.txt"))
        assertTrue(matches("**/build/**", "a/b/build/deep.txt"))
        assertFalse(matches("**/build/**", "buildx/a.txt"))
    }

    // --- the excludes path uses the same function -----------------------------------------

    @Test
    fun `the matcher used for excludes answers the same way`() {
        // `buildMatcher` is called twice per execution, once for the include glob and once for
        // the excludes glob, from the same call site. There is no second implementation to keep
        // in sync here -- which is the point of stating it: if a future change ever splits them,
        // this row is the one that notices.
        assertTrue(matches("**/target", "target"))
        assertTrue(matches("**/target", "a/target"))
        assertFalse(matches("**/target", "targets"))
        assertFalse(matches("**/target", "a/targetx"))
    }
}
