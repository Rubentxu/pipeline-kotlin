package dev.rubentxu.pipeline.v2.harness

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v20 (case-sensitive digest comparison, 2026-10-17):
 * three sites in the test-harness main source used
 * `String.equals(other, ignoreCase = true)` to compare
 * SHA-1 / SHA-256 digests:
 *
 *   - `CycleRunner.validateEvidenceDigest` (recorded
 *     `evidenceDigest` from a persisted evidence file vs.
 *     recomputed body digest)
 *   - `PromotionEvidenceVerifier.verifyPermanent`
 *     (`actualDigest` from `sha256HexOfFile` vs.
 *     `PermanentContract.contentDigest`)
 *   - `ProbeExecutor` HF0 path (`actualSha` from
 *     `sha256HexOfFile` vs. `ProbeEntry.contentSha256`)
 *
 * In every case both sides are guaranteed lowercase by
 * construction: the carrier's `init` strict-validates
 * lowercase hex via [dev.rubentxu.pipeline.v2.harness.util
 * .DigestFormat] (or the equivalent inline check) and
 * `sha256Hex*` always returns lowercase. The
 * `ignoreCase = true` was a defensive default that became a
 * silent acceptance of a tampered or upper-cased record:
 * a digest of the same 64 bytes but typed in uppercase
 * would have been compared equal to the lowercase form
 * and accepted.
 *
 * The fix replaces each `equals(..., ignoreCase = true)`
 * with a plain `!=`. This fitness is the regression
 * guard: any future change that re-introduces a
 * case-insensitive digest comparison fails CI rather than
 * silently misroute a tampered or upper-cased record.
 *
 * The fitness is hermetic (no I/O, no clock, no network)
 * and runs in milliseconds: it walks the test-harness
 * main source and greps for `equals(...ignoreCase = true)`.
 * The pattern is permissive enough to match the three
 * known offenders (different argument names) without
 * flagging legitimate uses outside the test-harness
 * (e.g. filesystem path comparisons that genuinely
 * tolerate case differences on case-insensitive
 * filesystems).
 */
class CaseInsensitiveDigestComparisonFitnessTest {

    private val harnessMainRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(harnessMainRoot)) {
            error("test-harness main source not found at $harnessMainRoot")
        }
        return Files.walk(harnessMainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    /**
     * The defect: a String digest comparison
     * (`a.equals(b, ignoreCase = true)` or
     * `!a.equals(b, ignoreCase = true)`) where the values
     * are 40-/64-char lowercase hex SHA-1/SHA-256 digests.
     * Both sides are guaranteed lowercase by the carrier's
     * `init` and the [dev.rubentxu.pipeline.v2.harness.util
     * .DigestFormat] helper, so the case-insensitive
     * comparison is unnecessary AND a silent acceptance of a
     * mixed-case record.
     *
     * The scan is structural: any line that contains
     * `equals(`, `ignoreCase`, and `true` on the same call
     * expression is a candidate. The current main source has
     * exactly zero such calls after BQ4 v20; a regression
     * that re-introduces one fails this test.
     *
     * Whitespace tolerance: the regex accepts any whitespace
     * between the `equals(`, the second argument, and the
     * `, ignoreCase = true` token, so the three known
     * historical shapes
     * (`!a.equals(b, ignoreCase = true)` and the
     * same-line or multi-line variants) all fail.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun assertNoIgnoreCaseEquals(sources: List<Path>) {
        // The token shape: `equals(`, then any number of
        // non-`)` chars, then `ignoreCase`, then optional
        // whitespace, then `=`, then optional whitespace,
        // then `true`. Single-line, no nested parens — every
        // known historical call fits the single-line shape
        // and a multi-line `equals(\n  ...,\n  ignoreCase = true,\n)`
        // is also matched because `[\s\S]` accepts newlines.
        //
        // Comments are stripped BEFORE the scan: a KDoc
        // block that describes the historical defect (and
        // contains the literal `equals(..., ignoreCase = true)`
        // inside ` ` for documentation) would otherwise match
        // the forbidden shape. Stripping is a single regex
        // pass per file: block comments are non-greedy
        // `/* ... */`; line comments are `//.*$` per line.
        // Nested block comments are not legal in Kotlin and
        // are not handled (Kotlin does not allow them).
        val forbidden = Regex(
            """\.equals\([^)]*ignoreCase\s*=\s*true""",
        )
        val blockComment = Regex("""/\*[\s\S]*?\*/""")
        val lineComment = Regex("""//[^\n]*""")
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val original = path.readText()
            val stripped = blockComment.replace(original, " ")
                .let { lineComment.replace(it, " ") }
            val matches = forbidden.findAll(stripped).toList()
            for (m in matches) {
                // Find the line of the match in the STRIPPED
                // text; the offset is identical to the
                // original because we replaced comments with
                // a single space.
                val lineNo = stripped.lineSequence()
                    .takeWhile { m.range.first > (stripped.indexOf(it) + it.length) }
                    .count() + 1
                offenders += "${path.fileName}:$lineNo: ${m.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v20 fitness: NO digest comparison in the test-harness main source " +
                "MAY use `String.equals(..., ignoreCase = true)`. Both sides of every " +
                "digest comparison are guaranteed lowercase by the carrier's init " +
                "(via DigestFormat.requireSha1/256) and by `sha256Hex*`; the case-" +
                "insensitive form silently accepts a tampered or upper-cased record. " +
                "Use a plain `==` (or `!=`). Offending call sites: $offenders",
        )
    }

    @Test
    fun `no digest comparison in the test-harness main source uses equals with ignoreCase true`() {
        assertNoIgnoreCaseEquals(kotlinSources())
    }
}
