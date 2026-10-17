package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v24 (typed proposal pair, 2026-10-17):
 * [ValidationPlan.SelectionRefusal.UnclassifiedPath] previously
 * carried the operator's starting-point proposal as two
 * parallel `String? = null` fields
 * (`proposedCapability`, `proposedOwner`). The two fields
 * encoded a single concept (the proposal) as two
 * independent sentinels. The producer
 * [dev.rubentxu.pipeline.v2.harness.plan.ImpactResolver.resolve]
 * always sets both together, but a future consumer that
 * read one without the other had no compile-time signal.
 *
 * The fix introduces a [ValidationPlan.Proposal] carrier:
 * the proposal is either present (typed, atomic) or
 * absent (`null` on the carrier). A consumer either reads
 * the pair atomically or does not read it at all.
 *
 * The fitness is the regression guard: any future change
 * that re-introduces a parallel `proposedCapability: String?`
 * + `proposedOwner: String?` pair on a `SelectionRefusal`
 * variant fails CI rather than silently re-open the
 * parallel-nullable window. The scan is structural: it
 * walks the plan package's main source and greps for
 * the forbidden shape — both names on the same data
 * class declaration (or as constructor parameters in a
 * single `data class` body), at the same indentation
 * level, on the same line or within a few lines of
 * each other.
 *
 * The pattern is tight on the shape: the two field
 * names appear as `val proposedCapability` and
 * `val proposedOwner` (in any order, with optional
 * intermediate lines like `: String?` or
 * `= null`). A regression that re-introduces the
 * parallel-nullable pair is caught; a regression that
 * introduces a different parallel-nullable pair (e.g.
 * `proposedAction` + `proposedTarget`) is NOT caught by
 * THIS test — that would be a new pattern, not a
 * regression of the historical defect.
 *
 * Comments are stripped BEFORE the scan so KDoc
 * blocks that document the historical defect (and
 * name the fields for narrative purposes) do not
 * match.
 */
class ParallelNullableProposalFitnessTest {

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
    private fun assertNoParallelNullableProposal(sources: List<Path>) {
        // The defect class is the two field names appearing
        // in the same file at a small distance (within 5
        // lines of each other). The regex looks for
        // `proposedCapability` and `proposedOwner` on the
        // same stripped line OR separated by a few
        // non-`val`-declaration lines. The pattern is
        // permissive on whitespace and indentation but
        // tight on the field names.
        val forbidden = Regex(
            """val\s+proposedCapability\b[\s\S]{0,200}val\s+proposedOwner\b|val\s+proposedOwner\b[\s\S]{0,200}val\s+proposedCapability\b""",
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
                offenders += "${path.fileName}:$lineNo: ${m.value.take(80)}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v24 fitness: NO parallel `proposedCapability` + `proposedOwner` " +
                "fields on a plan-package carrier. The previous shape carried them as " +
                "two independent `String? = null` sentinels on " +
                "[SelectionRefusal.UnclassifiedPath] — a parallel-nullable defect class. " +
                "The new shape is a single [Proposal] carrier: a proposal is either " +
                "present (typed, atomic) or absent (`null`). A consumer reads the pair " +
                "atomically or does not read it at all. Offending call sites: $offenders",
        )
    }

    @Test
    fun `no parallel proposedCapability and proposedOwner fields in the plan package main source`() {
        assertNoParallelNullableProposal(kotlinSources())
    }
}
