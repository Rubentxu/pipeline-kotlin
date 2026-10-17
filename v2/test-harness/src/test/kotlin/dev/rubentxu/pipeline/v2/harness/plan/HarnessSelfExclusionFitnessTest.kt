package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 P0 (HITO 1 "selección y evidencia vinculadas",
 * 2026-10-17): the previous shape of
 * [ChangedInputClassifier.classify] checked
 * `path.startsWith("v2/test-harness/")` first and
 * returned [ChangedInput.TestHarnessInternal] (a
 * NonProduction variant) for any harness path. The
 * resolver then ignored the change, and the gate
 * discharged on the plan. A defect in the harness's
 * production code (any `.kt` under `src/main/`) was
 * therefore a SILENT GREEN: the operator saw the gate
 * discharge and assumed the change was non-product.
 *
 * The audit's RC4 follow-up named this the most
 * expensive defect class in this repository. The
 * exemption is removed; harness `src/main/**/*.kt`
 * falls through to the standard `SourceCode` rule
 * and is classified as Production.
 *
 * The fitness is the regression guard. It walks the
 * plan package's main source and greps for the
 * self-exclusion signature: a `startsWith("v2/test-harness/")`
 * check that returns a NonProduction variant. The
 * pattern is tight on the shape: a prefix check on
 * the harness's own path that bypasses the catalogue.
 *
 * Comments are stripped BEFORE the scan so KDoc
 * blocks documenting the historical defect (and
 * containing the literal `v2/test-harness/` for
 * narrative purposes) do not match. The
 * `realSpecsFromCatalogue()` function and the
 * `PERMANENT_FITNESS_PATHS` set still contain the
 * literal `v2/test-harness/` as a path argument;
 * those are excluded by requiring the prefix-check
 * shape, not the bare substring.
 */
class HarnessSelfExclusionFitnessTest {

    private val planMainRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(planMainRoot)) {
            error("plan package source not found at $planMainRoot")
        }
        return Files.walk(planMainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoHarnessSelfExclusion(sources: List<Path>) {
        // The defect class is a `startsWith("v2/test-harness/")`
        // check on a path in [ChangedInputClassifier.classify]
        // that returns a NonProduction variant. The pattern
        // is: literal `v2/test-harness/` as a string
        // argument to `startsWith`, in a context that
        // bypasses the catalogue.
        val harnessPrefixExclusion = Regex("""startsWith\(\s*"v2/test-harness/"""")
        val blockComment = Regex("""/\*[\s\S]*?\*/""")
        val lineComment = Regex("""//[^\n]*""")
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val original = path.readText()
            val stripped = blockComment.replace(original, " ")
                .let { lineComment.replace(it, " ") }
            val matches = harnessPrefixExclusion.findAll(stripped).toList()
            for (m in matches) {
                val lineNo = stripped.lineSequence()
                    .takeWhile { m.range.first > (stripped.indexOf(it) + it.length) }
                    .count() + 1
                offenders += "${path.fileName}:$lineNo: ${m.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 P0 fitness: NO `startsWith(\"v2/test-harness/\")` prefix check in " +
                "the plan package's main source. The previous shape bypassed the " +
                "catalogue for any harness path, returning a NonProduction variant " +
                "(TestHarnessInternal) and letting the resolver ignore the change. " +
                "A defect in the harness's production code was therefore a SILENT " +
                "GREEN: the operator saw the gate discharge on the plan and assumed " +
                "the change was non-product. The exemption is removed; harness " +
                "`src/main/**/*.kt` falls through to the standard SourceCode rule " +
                "and is classified as Production. The catalogue's contract lookup " +
                "is the only authority for 'is this path covered by a known " +
                "contract'. Offending call sites: $offenders",
        )
    }

    @Test
    fun `no harness self-exclusion in the plan package main source`() {
        assertNoHarnessSelfExclusion(kotlinSources())
    }
}
