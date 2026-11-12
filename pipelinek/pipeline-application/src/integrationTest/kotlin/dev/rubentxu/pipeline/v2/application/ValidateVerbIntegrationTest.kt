package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.scripting.CompiledScriptCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `validate` tells the operator WHY it refused a script, in the text document and in the JSON one.
 *
 * ## The guarantee
 *
 * A script that compiles and then throws while it is evaluated is refused with exit 2, and the
 * reason it threw is printed once, with its position, before the one-line verdict. Before, the
 * verdict said "1 error" and nothing printed which one: the reason lived in the compilation result,
 * and the document only renders the closing event of the compilation.
 *
 * ## The instrument
 *
 * Each row calls [validateScript], the function `main` hands the verb to, with the parser's own
 * flags and the published protocol, and captures the two channels apart. The compiled-script cache
 * is off: every row compiles its script, and none reads or writes the cache of the machine. Under that protocol the
 * document goes to `out` and the verdict to `err`, so the reason is read on `out`, next to the
 * compiler's errors, and never repeated on `err`. The rows that read the JSON document parse it and
 * look for the diagnostic inside the closing event; a substring of the document would also match a
 * message printed anywhere else.
 *
 * ## Why each row is here
 *
 * - The thrown body, in text and in JSON: the case the verb used to count without naming.
 * - The discarded carrier: the refusal the DSL raises while the pipeline is built, whose text is
 *   the only thing that tells the author which carrier they dropped.
 * - The compile error is the control: it already reached the document, and it must still reach it
 *   exactly once, with no evaluation error added to it.
 * - The warning is a real one from the compiler, on a script that validates. The summary used to
 *   count it and print nothing under it, and to count a note the product's own compiler options
 *   raise on every script. The row reads both documents: the text one prints the warning with its
 *   position and counts nothing else, and the JSON one carries the same single diagnostic.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ValidateVerbIntegrationTest {

    @TempDir
    lateinit var dir: Path

    private data class Outcome(val exit: CliExit, val out: String, val err: String)

    private fun validate(name: String, script: String, vararg flags: String): Outcome {
        val path = dir.resolve(name)
        Files.writeString(path, script)
        val config = (
            CliExecutionParser.parse(arrayOf("validate", *flags, path.toString())) as CliParseResult.Parsed
            ).flags
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val exit = validateScript(
            config = config,
            scriptPath = path,
            secretPatternRegistry = SecretPatternRegistry(),
            compiledQuery = (compileQuery(config.query) as SelectorCompileResult.Ok).value,
            outputProtocol = OutputProtocol.Published,
            cache = CompiledScriptCache.Disabled,
            out = PrintStream(outBytes, true, Charsets.UTF_8.name()),
            err = PrintStream(errBytes, true, Charsets.UTF_8.name()),
        )
        return Outcome(exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }

    /** The diagnostics of the closing event of the compilation, read from the JSON document. */
    private fun diagnosticsOf(document: String): List<JsonObject> {
        val finished = Json.parseToJsonElement(document).jsonArray
            .map { it.jsonObject }
            .single { it["kind"]?.jsonPrimitive?.content == "CompilationFinished" }
        return (finished["diagnostics"] as JsonArray).map { it as JsonObject }
    }

    /** The messages of the ERROR diagnostics of the closing event, read from the JSON document. */
    private fun errorMessagesOf(document: String): List<String> = diagnosticsOf(document)
        .filter { it["severity"]?.jsonPrimitive?.content == "ERROR" }
        .map { it["message"]!!.jsonPrimitive.content }

    private fun occurrences(text: String, needle: String): Int = text.windowed(needle.length).count { it == needle }

    @Test
    fun `a body that throws is refused with its reason and line in the document, and one verdict`() {
        val (exit, out, err) = validate("thrown.pipeline.kts", THROWN)

        assertEquals(CliExit.INVALID_ARGUMENT, exit, out + err)
        assertEquals(1, occurrences(out, THROWN_REASON), "the document did not print the reason once:\n$out")
        val position = "${dir.resolve("thrown.pipeline.kts")}:2:0: $THROWN_REASON"
        assertTrue(position in out, "the reason does not carry the line that threw:\n$out")
        assertEquals("VALIDATION FAILED: 1 error", err.trim(), "the verdict is not one line, or repeats the reason")
    }

    @Test
    fun `a body that throws is an ERROR diagnostic of the closing event in the JSON document`() {
        val (exit, out, err) = validate("thrown.pipeline.kts", THROWN, "--format", "json")

        assertEquals(CliExit.INVALID_ARGUMENT, exit, out + err)
        assertEquals(listOf(THROWN_REASON), errorMessagesOf(out), out)
        assertEquals("VALIDATION FAILED: 1 error", err.trim())
    }

    @Test
    fun `a stage that discards a carrier is refused with the consumption gate's text`() {
        val text = validate("unconsumed.pipeline.kts", UNCONSUMED)
        val json = validate("unconsumed.pipeline.kts", UNCONSUMED, "--format", "json")

        assertEquals(CliExit.INVALID_ARGUMENT, text.exit, text.out + text.err)
        assertEquals(CliExit.INVALID_ARGUMENT, json.exit, json.out + json.err)
        assertEquals(1, occurrences(text.out, GATE_PREFIX), "the text document did not name the refusal once:\n${text.out}")
        val messages = errorMessagesOf(json.out)
        assertEquals(1, messages.size, json.out)
        assertTrue(messages.single().startsWith(GATE_PREFIX), "the JSON document lost the refusal's text:\n${json.out}")
    }

    @Test
    fun `a compile error is printed once and nothing is added to it`() {
        val text = validate("broken.pipeline.kts", BROKEN)
        val json = validate("broken.pipeline.kts", BROKEN, "--format", "json")

        assertEquals(CliExit.INVALID_ARGUMENT, text.exit, text.out + text.err)
        assertEquals(1, occurrences(text.out, "Initializer type mismatch"), "the compile error is not printed once:\n${text.out}")
        assertEquals("VALIDATION FAILED: 1 error", text.err.trim())
        assertEquals(1, errorMessagesOf(json.out).size, json.out)
    }

    @Test
    fun `a warning is printed with its position, counted alone, and the script still validates`() {
        val text = validate("warn.pipeline.kts", WARNED, "--compile-only")
        val json = validate("warn.pipeline.kts", WARNED, "--compile-only", "--format", "json")

        assertEquals(CliExit.SUCCESS, text.exit, text.out + text.err)
        assertEquals(CliExit.SUCCESS, json.exit, json.out + json.err)
        val position = "${dir.resolve("warn.pipeline.kts")}:2:11: warning: $TO_INT"
        assertEquals(1, occurrences(text.out, position), "the warning is not printed once with its position:\n${text.out}")
        assertTrue(
            "Compilation finished with 1 warning (compiled)" in text.out,
            "the summary counts something it does not print:\n${text.out}",
        )
        val diagnostics = diagnosticsOf(json.out).map {
            it["severity"]!!.jsonPrimitive.content to it["message"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("WARNING" to TO_INT), diagnostics, "the JSON document and the text one disagree:\n${json.out}")
    }

    @Test
    fun `a script that builds its pipeline still validates`() {
        val (exit, out, err) = validate("ok.pipeline.kts", PIPELINE_ONLY)

        assertEquals(CliExit.SUCCESS, exit, out + err)
        assertEquals("VALIDATION SUCCESSFUL", err.trim())
    }

    private companion object {
        const val THROWN_REASON = "boom: evaluation failed here"
        const val GATE_PREFIX = "Pure builder consumption gate: "

        val PIPELINE_ONLY = """
            pipeline {
                stages {
                    stage("build") {
                        sh("echo hello")
                    }
                }
            }
        """.trimIndent()

        val THROWN = "// The body throws before it declares a pipeline.\n" +
            "error(\"$THROWN_REASON\")\n\n" + PIPELINE_ONLY

        val UNCONSUMED = """
            pipeline {
                stages {
                    stage("build") {
                        val unused = scmGit(url = "https://example.invalid/r.git")
                        sh("echo hello")
                    }
                }
            }
        """.trimIndent()

        val BROKEN = "val count: Int = \"not a number\"\n\n$PIPELINE_ONLY"

        const val TO_INT = "Use 'toInt' to give the type explicitly."

        val WARNED = "// The cast is redundant, and the compiler says so.\nval n = 1 as Int\n\n$PIPELINE_ONLY"
    }
}
