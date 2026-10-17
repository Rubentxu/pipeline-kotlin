package dev.rubentxu.pipeline.v2.harness.lanes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v27 (typed JSON output, 2026-10-17):
 * `LanePlannerMain` previously hand-rolled the JSON
 * output via
 * `buildString { append("\"total_specs\": ${specs.size},\n") }`
 * with `${a.contractId}` and `${a.reason}` interpolated
 * as raw strings. The cost was the same defect class
 * as the closed `parseFlatObject` (in `1358b771`) and
 * the closed `InventoryClassifierMain` writer (in
 * `19d592e5`): a custom string-emitter where a proper
 * library is the right answer. The new shape uses
 * `kotlinx.serialization.json` + a typed [LaneReport]
 * carrier; the library does proper escaping of all
 * JSON special characters (the hand-rolled writer did
 * NOT escape `"` or `\`; a contractId or reason
 * containing one would have produced invalid JSON).
 *
 * The fitness is the regression guard. It walks
 * the lanes package's main source and greps for the
 * hand-rolled JSON writer signature:
 *   - `buildString {` followed by `append("\"`
 *     (a JSON key or value written by string
 *     concatenation), or
 *   - a `joinToString` of raw string values that
 *     looks like a JSON array (the previous shape
 *     used `ids.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }`
 *     to fabricate a JSON array).
 *
 * The scan is structural and tight. Comments are
 * stripped BEFORE the scan so KDoc blocks that
 * document the historical defect (and include these
 * literal patterns for narrative purposes) do not
 * match. The kotlinx.serialization.json library's
 * typed `JsonObject` and `JsonPrimitive` references
 * are allowed — the library's API is the right
 * answer; the defect class is the manual
 * character-by-character emitter, not the use of a
 * JSON library.
 *
 * If the fitness ever fires, the regression is a
 * re-introduction of the hand-rolled JSON writer
 * pattern in the lanes package. The fix is to use
 * `kotlinx.serialization.json` and a typed
 * `@Serializable` carrier, exactly as
 * `LanePlannerMain` does today.
 */
class HandRolledJsonWriterFitnessTest {

    private val lanesMainRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/lanes")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(lanesMainRoot)) {
            error("lanes package source not found at $lanesMainRoot")
        }
        return Files.walk(lanesMainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoHandRolledJsonWriter(sources: List<Path>) {
        val buildStringWriter = Regex("""buildString\s*\{[^}]*append\("""")
        val joinToStringJsonArray = Regex("""joinToString\(\s*prefix\s*=\s*"\["[^)]*\)\s*\{[^}]*"\\""""")
        val blockComment = Regex("""/\*[\s\S]*?\*/""")
        val lineComment = Regex("""//[^\n]*""")
        val labels = listOf(
            buildStringWriter to "buildString + append(\"...)",
            joinToStringJsonArray to "joinToString prefix=[ ... { \\\"\$it\\\" }",
        )
        val offenders = mutableListOf<String>()
        for (path in sources) {
            val original = path.readText()
            val stripped = blockComment.replace(original, " ")
                .let { lineComment.replace(it, " ") }
            for (pattern in labels) {
                val matches = pattern.first.findAll(stripped).toList()
                for (m in matches) {
                    offenders += "${path.fileName}: ${pattern.second}: ${m.value.take(80)}"
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "BQ4 v27 fitness: NO hand-rolled JSON writer in the lanes package's main " +
                "source. The previous shape used `buildString { append(...) }` plus a " +
                "`joinToString(prefix = \"[\", postfix = \"]\") { ... }` for the " +
                "contract-id arrays in `LanePlannerMain` — a brittle custom emitter " +
                "where the typed `kotlinx.serialization.json` + `@Serializable` carrier " +
                "is the right answer. The new shape uses " +
                "`Json.encodeToString<LaneReport>(report)`; the library does proper " +
                "escaping of all JSON special characters, and the typed carrier makes " +
                "the field order, map iteration order, and snake_case wire format " +
                "explicit. A regression to a hand-rolled writer reintroduces the same " +
                "defect class as the previous `parseFlatObject` (closed in `1358b771`) " +
                "and the previous `InventoryClassifierMain` writer (closed in " +
                "`19d592e5`). Offending call sites: " + offenders,
        )
    }

    @Test
    fun `no hand-rolled JSON writer in the lanes package main source`() {
        assertNoHandRolledJsonWriter(kotlinSources())
    }
}
