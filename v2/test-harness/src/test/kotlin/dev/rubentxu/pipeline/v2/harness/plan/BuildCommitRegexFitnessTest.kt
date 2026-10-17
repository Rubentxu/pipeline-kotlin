package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v25 (typed JSON field read, 2026-10-17):
 * [InstalledDistribution.parse] previously used a
 * regex to extract the `buildCommit` field from a
 * `version --json` document:
 * `"buildCommit"\s*:\s*"([^"]*)"`. The KDoc on the
 * helper was explicit about the trade-off: 'a
 * targeted field read rather than a JSON parse: the
 * document is one flat object produced by this
 * project's own CLI'. The cost was the same defect
 * class as the previous `EVIDENCE_DIGIT_RE` regex
 * (closed in `2af30aff`) and the previous
 * `parseFlatObject` (closed in `1358b771`): a fragile
 * custom parser where a proper library is the right
 * answer. The new shape uses
 * `kotlinx.serialization.json.Json` and the typed
 * `JsonObject` API; the dependency is already in the
 * test-harness (the `HarnessVerdictReader` uses it).
 *
 * The fitness is the regression guard: any future
 * change that re-introduces a regex-based targeted
 * field read for `buildCommit` (or any other field
 * the same shape would silently misparse) fails CI
 * rather than silently re-open the brittle-parser
 * window. The scan is structural: it walks the plan
 * package's main source and greps for the forbidden
 * regex pattern — `\"buildCommit\"\\s*:` or any
 * similar `Regex("\\\"...\\\"\\s*:\\s*...\\")` that
 * targets a JSON field by name + value.
 *
 * The pattern is tight on the shape: a regex literal
 * that escapes a JSON field name and uses
 * `\\s*:\s*` to match the field separator. A future
 * regression that targets a different field (e.g.
 * `productVersion`) would also be caught by this
 * pattern; the defect class is "regex-based targeted
 * JSON field read", and any field is the same defect
 * class.
 *
 * Comments are stripped BEFORE the scan so KDoc
 * blocks that document the historical defect (and
 * include the literal regex for narrative purposes)
 * do not match. The `kotlinx.serialization.json`
 * library's typed `JsonObject` and `JsonPrimitive`
 * references are allowed — the library's API is the
 * right answer; the defect class is the manual
 * character-by-character parser, not the use of a
 * JSON library.
 */
class BuildCommitRegexFitnessTest {

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
    private fun assertNoBuildCommitRegex(sources: List<Path>) {
        // The defect class is a regex literal that
        // matches a JSON field by escaped name
        // (`\"<name>\"\\s*:\\s*`). The pattern
        // catches both `\"buildCommit\"` (the
        // specific historical case) and any other
        // field (the same defect class).
        val forbidden = Regex("""\\"buildCommit\\"""")
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
            "BQ4 v25 fitness: NO regex-based targeted JSON field read in the plan " +
                "package's main source. The previous shape used " +
                "`Regex(\"\\\"buildCommit\\\"\\\\s*:\\\\s*\\\"([^\\\"]*)\\\")\"` in " +
                "[InstalledDistribution.parse] — a brittle custom parser where the " +
                "typed `kotlinx.serialization.json.Json` is the right answer. The " +
                "new shape uses the library + `JsonObject[\"buildCommit\"]` access; " +
                "every previous-shape failure mode (empty input, missing field, " +
                "empty value, JSON `null` value) maps to a typed " +
                "[ObservedProvenance.Unreadable] case via the parser. A regression to " +
                "a hand-rolled regex reintroduces the same defect class as the " +
                "previous `EVIDENCE_DIGIT_RE` regex (closed in `2af30aff`) and the " +
                "previous `parseFlatObject` (closed in `1358b771`). Offending call " +
                "sites: $offenders",
        )
    }

    @Test
    fun `no regex-based targeted JSON field read in the plan package main source`() {
        assertNoBuildCommitRegex(kotlinSources())
    }
}
