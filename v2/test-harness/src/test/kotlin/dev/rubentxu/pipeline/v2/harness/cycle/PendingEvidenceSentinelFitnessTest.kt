package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v21 (typed marker, 2026-10-17): the executor
 * previously used a string sentinel
 * `ProbeExecutor.PENDING_EVIDENCE_REF = "<pending>"` as
 * the protocol between [ProbeExecutor.runHF0] (the
 * emitter) and [ProbeExecutor.execute] (the parser,
 * via the `isPendingOutcome` predicate). The executor
 * emitted `Verified("<pending>")` and the parser
 * matched with a string `==`. A typo on either side
 * (`"<Pending>"` capitalised, or a future refactor
 * that forgets to thread the sentinel) silently
 * misroutes the outcome: a `Verified` with a real
 * evidence path would skip `persist`, or a
 * `Verified("<pending>")` with a typo would skip
 * `persist` and leave the cycle with no evidence
 * file. The BQ4 audit named this defect class;
 * the BQ4 v3 fix introduced a sealed
 * `ProbeExecutionResult` ADT but kept the sentinel
 * as the outcome payload. BQ4 v21 closed the
 * residual by making [ProbeExecutionResult.NeedsPersist]
 * a `data object` and dropping the
 * `outcome: ProbeOutcome` argument from
 * [ProbeExecutor.persist]; the sentinel is gone.
 *
 * The fitness is the regression guard: any future
 * change that re-introduces a `PENDING_EVIDENCE_REF`
 * constant or a `Verified("<pending>")` / `Verified
 * ("<Pending>")` literal in the test-harness main
 * source fails CI rather than silently re-open the
 * dual emitter/parser window. The scan is
 * structural: it walks the test-harness main source
 * and greps for the forbidden token shapes. Comments
 * are stripped BEFORE the scan so KDoc blocks that
 * document the historical defect (and contain the
 * literal `PENDING_EVIDENCE_REF` / `<pending>` for
 * narrative purposes) do not match.
 */
class PendingEvidenceSentinelFitnessTest {

    private val harnessMainRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        // SCOPED to the cycle package. The defect class is the
        // executor's persist protocol; the retirement package
        // also uses the literal "pending" (a wire-protocol
        // status value the external certifier writes), and
        // scanning the whole test-harness would over-match
        // that legitimate use.
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/cycle")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(harnessMainRoot)) {
            error("test-harness main source not found at $harnessMainRoot")
        }
        return Files.walk(harnessMainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoPendingSentinel(sources: List<Path>) {
        // The defect class is the literal `<pending>` as a
        // string value in code: `PENDING_EVIDENCE_REF = "<pending>"`,
        // `Verified("<pending>")`, `equals(PENDING_EVIDENCE_REF)`,
        // etc. The fitness is permissive on the EXACT
        // casing of the string literal so a typo that
        // reintroduces a case-variant is also caught (the
        // previous dual-emitter/parser class included
        // uppercase variants).
        //
        // The pattern was WRONG until 2026-10-17 and did not
        // guard the shape its own KDoc names. It read
        // `["<]([Pp]ending|PENDING)["<]`, which requires a
        // delimiter on BOTH sides of the word; in the canonical
        // literal `"<pending>"` the delimiters are `"` and `<`,
        // which are adjacent, so the pattern demanded
        // `<pending<` and matched nothing. Measured: `"<pending>"`
        // -> no match, `"<Pending>"` -> no match, while
        // `<pending"` and `"pending"` did match. An inline
        // reintroduction — `Verified("<pending>")` with no
        // constant — would have sailed past this guard while
        // only the constant-name alternative caught the
        // declaration. Anchoring on the token itself is what the
        // intent was; do not restore the delimiter-pair form.
        //
        // Over-match check on the current package: 13 files,
        // 0 matches under the corrected pattern (identical to
        // the old one), so the strengthening costs nothing.
        val forbidden = Regex(
            """(?i)<pending>|["']pending["']|PENDING_EVIDENCE_REF|isPendingOutcome""",
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
                val lineNo = stripped.lineSequence()
                    .takeWhile { m.range.first > (stripped.indexOf(it) + it.length) }
                    .count() + 1
                offenders += "${path.fileName}:$lineNo: ${m.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v21 fitness: NO `<pending>` sentinel in the test-harness main source. " +
                "The previous shape used a string sentinel `PENDING_EVIDENCE_REF` / " +
                "`Verified(\"<pending>\")` between [ProbeExecutor.runHF0] and " +
                "[ProbeExecutor.execute]; a typo on either side silently misroutes the " +
                "outcome. The new shape is a `data object NeedsPersist` and a " +
                "`persist(probe)` signature that takes only the probe. Offending " +
                "call sites: $offenders",
        )
    }

    @Test
    fun `no PENDING_EVIDENCE_REF sentinel in the test-harness main source`() {
        assertNoPendingSentinel(kotlinSources())
    }
}
