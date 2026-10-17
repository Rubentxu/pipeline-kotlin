package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 P0 (HITO 1 "Selección y evidencia vinculadas",
 * 2026-10-17): the previous shape of
 * [EvidenceReader.suitesWithEvidence] used
 * `name.substringAfterLast('.')` to extract the simple
 * class name from the JUnit XML's `<testsuite name="...">`
 * attribute, and used that simple name as the evidence
 * map key. The audit's "Debilidades observadas" list
 * names this as item (4) — two modules with a
 * same-named suite (e.g., `FooTest` in `:pipeline-application`
 * and `:test-harness`) silently collapsed into a single
 * evidence row.
 *
 * The new shape uses the FULL `name` attribute (the
 * FQCN) as the map key. The fitness is the regression
 * guard: a future change that re-introduces
 * `substringAfterLast('.')` in the `suitesWithEvidence`
 * code path is rejected at CI. Comments are stripped
 * before the scan so KDoc blocks documenting the
 * historical defect (and containing the literal
 * substring for narrative purposes) do not match.
 */
class FqcnIdentityFitnessTest {

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
    private fun assertNoSimpleNameKey(sources: List<Path>) {
        // The defect class is the simple-name key
        // computation: `name.substringAfterLast('.')`
        // (or any equivalent that drops the package
        // prefix). The pattern matches the specific
        // substring call the previous shape used.
        val simpleNameKey = Regex("""substringAfterLast\(\s*'\.'\s*\)""")
        val blockComment = Regex("""/\*[\s\S]*?\*/""")
        val lineComment = Regex("""//[^\n]*""")
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val original = path.readText()
            val stripped = blockComment.replace(original, " ")
                .let { lineComment.replace(it, " ") }
            val matches = simpleNameKey.findAll(stripped).toList()
            for (m in matches) {
                val lineNo = stripped.lineSequence()
                    .takeWhile { m.range.first > (stripped.indexOf(it) + it.length) }
                    .count() + 1
                offenders += "${path.fileName}:$lineNo: ${m.value}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 P0 HITO 1 fitness: NO `substringAfterLast('.')` simple-name " +
                "key in the plan package's main source. The previous shape " +
                "extracted the simple class name from the JUnit XML's " +
                "`<testsuite name=\"...\">` attribute and used it as the " +
                "evidence map key; two modules with a same-named suite " +
                "(e.g., `FooTest` in `:pipeline-application` and `:test-harness`) " +
                "silently collapsed into a single row. The new shape uses " +
                "the FULL `name` attribute (the FQCN, e.g. `com.example.FooTest`) " +
                "as the key — the closed-world identity per JVM. A regression to " +
                "the simple-name key re-opens the silent-collision defect class. " +
                "Offending call sites: $offenders",
        )
    }

    @Test
    fun `no simple-name evidence-map key in the plan package main source`() {
        assertNoSimpleNameKey(kotlinSources())
    }
}
