package dev.rubentxu.pipeline.v2.harness.cycle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v22 (no-evidence carrier accepts blank, 2026-10-17):
 * the [Cycle] decider previously substituted a string
 * sentinel (`<refused:other-commit>`, `<refused:HF0>`, etc.)
 * into [CycleState.Refused.ProbeOtherCommit.evidenceRef]
 * or [CycleState.Refused.ProbeUnsupportedFidelity.evidenceRef]
 * when the corresponding [ProbeOutcome.evidenceRef] was
 * blank. The substitution was a workaround for the
 * `require(evidenceRef.isNotBlank())` guard on the carrier:
 * the decider was passing through a real blank, the
 * carrier was over-strict, and the substitution was the
 * glue. The substitution was a fabrication — a
 * `<refused:...>` value carries no signal that a parser
 * can use, and the typed `Refused` variant is the
 * discriminant the audit and the [ExitVerdict] projection
 * already read.
 *
 * The fix removes both halves: the carriers now accept
 * blank `evidenceRef` (a refused fidelity check may
 * produce no path), and the decider passes the value
 * through verbatim. The fitness is the regression guard:
 * any future change that re-introduces a `<refused:*>`
 * literal in the cycle package's main source fails CI
 * rather than silently re-glue the decider and the
 * carrier with a string placeholder.
 *
 * The scan is structural: it walks the cycle package
 * and greps for the forbidden token shapes. Comments
 * are stripped BEFORE the scan so KDoc blocks that
 * document the historical defect (and contain the
 * literal `<refused:*>` for narrative purposes) do not
 * match.
 */
class RefusedEvidenceSentinelFitnessTest {

    private val cyclePackageRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/cycle")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(cyclePackageRoot)) {
            error("cycle package source not found at $cyclePackageRoot")
        }
        return Files.walk(cyclePackageRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoRefusedSentinel(sources: List<Path>) {
        // The defect class is the literal `<refused:...>` in
        // code, including the template form
        // `<refused:${...}>` (string interpolation). The
        // string `<no-evidence>` is also a fabrication with
        // no parser; a `reason` getter that constructs it
        // would re-introduce the same defect class.
        val forbidden = Regex(
            """["<]refused[:<]|<refused[:$]|["<]no-evidence["<]""",
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
            "BQ4 v22 fitness: NO `<refused:*>` (or `<no-evidence>`) sentinel in the cycle " +
                "package's main source. The previous shape substituted a string sentinel " +
                "to satisfy the `require(evidenceRef.isNotBlank())` guard on the carrier. " +
                "The new shape is a self-protected carrier that accepts blank `evidenceRef` " +
                "(a refused fidelity check may have produced no path) and a decider that " +
                "passes the value through verbatim. The typed `Refused` variant is the " +
                "discriminant. Offending call sites: $offenders",
        )
    }

    @Test
    fun `no refused-evidence sentinel in the cycle package main source`() {
        assertNoRefusedSentinel(kotlinSources())
    }
}
