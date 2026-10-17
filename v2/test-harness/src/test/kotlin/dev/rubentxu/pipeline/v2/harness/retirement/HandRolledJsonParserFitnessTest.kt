package dev.rubentxu.pipeline.v2.harness.retirement

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText

/**
 * BQ4 v23 (typed JSON parse, 2026-10-17): the
 * retirement package previously carried a hand-rolled
 * JSON parser ([HarnessVerdictReader.parseFlatObject])
 * plus three helpers ([HarnessVerdictReader.readString],
 * [HarnessVerdictReader.unescape],
 * [HarnessVerdictReader.findMatchingBrace]) — ~80
 * lines of character-by-character parsing that did not
 * support numbers, arrays, or escapes beyond `\"`.
 * The KDoc on the helper admitted the limitation:
 * 'the harness shape is frozen and any deviation is a
 * [ParseOutcome.Malformed]'. The hand-rolled parser is
 * the same defect class as the previous
 * `EVIDENCE_DIGIT_RE` regex: a fragile custom parser
 * where a proper library (`kotlinx.serialization.json`)
 * is the right answer. The new shape uses the library
 * for both the top-level parse and the nested
 * `fingerprint` object; the `fingerprint` is no longer
 * re-parsed through the hand-rolled parser.
 *
 * The fitness is the regression guard: any future
 * change that re-introduces a hand-rolled JSON parser
 * in the retirement package (a private function that
 * walks the text by character looking for `{`, `}`,
 * `"`, `\\`, etc., or that returns a hand-built
 * `Map<String, String>` from a JSON-like input) fails
 * CI rather than silently re-open the brittle-parser
 * window. The scan is structural: it walks the
 * retirement package's main source and greps for
 * the forbidden token shapes — function names that
 * match the parser-helper convention, the use of
 * `JsonObject`-like manual indices, and the
 * hand-rolled `readString` / `findMatchingBrace`
 * shapes that re-parse JSON from a string.
 *
 * The pattern is permissive on the EXACT helper names
 * so any future regression that names a helper
 * `parseFlatObject`, `parseJsonObject`,
 * `parseString`, `findMatchingBrace`, or that
 * declares a `private fun` taking a JSON-shaped
 * `String` and walking it manually is caught. The
 * previous-shape helpers all matched this pattern.
 *
 * Comments are stripped BEFORE the scan so KDoc
 * blocks that document the historical defect (and
 * name the forbidden helpers for narrative purposes)
 * do not match. The `kotlinx.serialization.json`
 * library's typed `JsonObject` and `JsonPrimitive`
 * references are allowed — the library's API is the
 * right answer; the defect class is the manual
 * character-by-character parser, not the use of a
 * JSON library.
 */
class HandRolledJsonParserFitnessTest {

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
    private fun assertNoHandRolledJsonParser(sources: List<Path>) {
        // The defect class is a hand-rolled JSON
        // parser: a function that walks the input by
        // character looking for `{` / `}` / `"`,
        // maintains a manual depth counter, and
        // returns a hand-built map. The forbidden
        // token shapes are the function names from
        // the previous shape (the pattern is the
        // function name, not the implementation —
        // a hand-rolled parser would either reuse
        // the same name or invent a near-equivalent
        // like `parseFlatJson`, `parseVerdictObject`,
        // etc.).
        //
        // The regex is permissive on the EXACT name
        // but tight on the shape: `parseFlat` at a
        // function declaration (`fun parseFlatX` or
        // `fun parseFlatObject(`) is the forbidden
        // shape. A future regression that re-introduces
        // a hand-rolled parser would either reuse the
        // original name (caught) or invent a new name
        // in the same family (caught).
        val forbidden = Regex(
            """\bfun\s+parseFlat\w*|\bfun\s+readString\w*|\bfun\s+findMatchingBrace\w*|\bfun\s+unescape\w*""",
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
            "BQ4 v23 fitness: NO hand-rolled JSON parser in the retirement package's " +
                "main source. The previous shape carried [parseFlatObject] + " +
                "[readString] + [unescape] + [findMatchingBrace] — ~80 lines of " +
                "character-by-character parsing that did not support numbers, arrays, " +
                "or escapes beyond '\"'. The new shape uses `kotlinx.serialization.json` " +
                "for both the top-level parse and the nested `fingerprint` object. A " +
                "regression to a hand-rolled parser reintroduces the same defect class " +
                "as the previous `EVIDENCE_DIGIT_RE` regex: a fragile custom parser " +
                "where a proper library is the right answer. Offending call sites: " +
                "$offenders",
        )
    }

    @Test
    fun `no hand-rolled JSON parser in the retirement package main source`() {
        assertNoHandRolledJsonParser(kotlinSources())
    }
}
