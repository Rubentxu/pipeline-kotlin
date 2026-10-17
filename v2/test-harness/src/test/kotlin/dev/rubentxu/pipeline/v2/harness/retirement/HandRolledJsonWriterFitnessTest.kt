package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v26 (typed JSON output, 2026-10-17):
 * `InventoryClassifierMain` previously hand-rolled
 * the JSON output via
 * `buildString { append("\"total\": ...") }` plus a
 * hand-rolled `jsonKey` escape function that only
 * escaped `"`. The cost was the same defect class
 * as the closed `parseFlatObject` (in `1358b771`):
 * a custom string-emitter where a proper library
 * is the right answer. The new shape uses
 * `kotlinx.serialization.json` + a typed
 * [ClassifierOutput] carrier; the library does
 * proper escaping of all JSON special characters
 * (the hand-rolled `jsonKey` only escaped `"`; a
 * key with `\` or a control character would have
 * produced invalid JSON).
 *
 * The fitness is the regression guard. It walks
 * the retirement package's main source and greps
 * for the hand-rolled JSON writer signature:
 *   - `buildString {` followed by `append("\"`
 *     (a JSON key or value written by string
 *     concatenation), or
 *   - a private function whose body contains
 *     `replace("\"", "\\\"")` (the only escape
 *     the previous `jsonKey` performed).
 *
 * The scan is structural and tight. False positives
 * are minimised: the `append("\"` pattern is the
 * exact shape the previous writer used, and
 * `replace("\"", "\\\"")` is the exact escape the
 * previous `jsonKey` performed. Comments are
 * stripped BEFORE the scan so KDoc blocks that
 * document the historical defect (and include
 * these literal patterns for narrative purposes)
 * do not match.
 *
 * If the fitness ever fires, the regression is
 * a re-introduction of the hand-rolled JSON
 * writer pattern in the retirement package. The
 * fix is to use `kotlinx.serialization.json` and
 * a typed `@Serializable` carrier, exactly as
 * `InventoryClassifierMain` does today.
 */
class HandRolledJsonWriterFitnessTest {

    private val retirementMainRoot: Path by lazy {
        val cwd = Path.of("").toAbsolutePath()
        cwd.resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/harness/retirement")
    }

    private fun kotlinSources(): List<Path> {
        if (!Files.isDirectory(retirementMainRoot)) {
            error("retirement package source not found at $retirementMainRoot")
        }
        return Files.walk(retirementMainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertNoHandRolledJsonWriter(sources: List<Path>) {
        // The defect class is a hand-rolled JSON writer.
        // The two patterns that catch it:
        //   1. `buildString {` followed by `append("\"` —
        //      the exact signature the previous
        //      `InventoryClassifierMain` used.
        //   2. `replace("\"", "\\\"")` — the only escape
        //      the previous `jsonKey` function performed.
        val buildStringWriter = Regex("""buildString\s*\{[^}]*append\("""")
        val jsonKeyStyleEscape = Regex("""\.replace\(\s*"\\""\s*,\s*"\\\\\\""""")
        val blockComment = Regex("""/\*[\s\S]*?\*/""")
        val lineComment = Regex("""//[^\n]*""")
        val labels = listOf(
            buildStringWriter to "buildString append(\"...)",
            jsonKeyStyleEscape to "replace(\", \\\")",
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
            buildString {
                append("BQ4 v26 fitness: NO hand-rolled JSON writer in the retirement package's main source. ")
                append("Offending call sites: ")
                append(offenders)
            },
        )
    }

    @Test
    fun `no hand-rolled JSON writer in the retirement package main source`() {
        assertNoHandRolledJsonWriter(kotlinSources())
    }
}
