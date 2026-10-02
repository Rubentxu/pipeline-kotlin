package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * LFC-2 / RP6-C / WU-093 G3.5 — `core.httpRequest` wire-authority guard
 * (SPEC_WU093_HTTP.md).
 *
 * ## Law under test
 *
 * ```
 * DslCompiledPipelineCompiler
 *     may know:    StepSpec.HttpRequest, CoreHttpInput, CoreHttpWireCodec
 *     must NOT know: the http wire vocabulary
 *                    ("requestBody", "validResponseCodes", "customHeaders", …)
 *                    or any hand-written http wire JSON
 * ```
 *
 * `CoreHttpWireCodec` is THE single authority for the `core.httpRequest` wire
 * format, exactly as `CoreLockWireCodec` and `CoreInputWireCodec` are for their
 * own steps. `core.sh` is the counter-example frozen by WU-091: its compiler
 * producer and its codec self-encoding drifted (`kind=sh|shell`, `command|script`,
 * `returnStdout|returnMode`), and every alias is a compatibility shim added
 * after the divergence. This guard refuses to manufacture that debt again.
 *
 * ## Why `url` is asserted by COUNT and not forbidden outright
 *
 * The obvious guard — "the compiler must never write `put(\"url\"`" — would be a
 * LIE about this repository. `core.checkout` legitimately writes `put("url", …)`
 * at DslCompiledPipelineCompiler.kt:887, which is pre-existing inline wire
 * authorship classified as `DEBT-WIRE-AUTHORITY` (bl-bl-01M3YGH3FE000387X13PG607G0,
 * P2, Triaged) and deliberately out of RP6-C's scope. Forbidding the literal
 * would make the fitness red on day one for a debt this train does not own, and
 * a permanently-red guard stops being read.
 *
 * So the `url` key is pinned by COUNT: exactly one author today, and a second one
 * is exactly what a hand-written http payload would look like. The other http
 * keys have no such collision and are forbidden outright.
 *
 * The scan is comment-blind, like the rest of the Lfc2 source-scan fitness
 * family: a forbidden literal inside a comment does not trip it, and a permitted
 * symbol only named in comments does not satisfy the allowed-knowledge assertion.
 */
class Lfc2HttpWireAuthorityFitnessTest {

    private val compilerSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
    )
    private val wireCodecSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreHttpWireCodec.kt",
    )

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Comment-blind view of a source file. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun `compiler knows the http domain decision and the codec symbol`() {
        val code = codeOnly(read(compilerSource))
        assertTrue(
            code.contains("StepSpec.HttpRequest"),
            "DslCompiledPipelineCompiler must route StepSpec.HttpRequest; if this fails the " +
                "routing case was removed and httpRequest falls into the generic else branch, " +
                "which would encode it as a legacy core.<name> opaque step.",
        )
        assertTrue(
            code.contains("CoreHttpInput"),
            "DslCompiledPipelineCompiler must know CoreHttpInput (the StepSpec.HttpRequest -> " +
                "CoreHttpInput domain transformation, G3.3).",
        )
        assertTrue(
            code.contains("CoreHttpWireCodec"),
            "DslCompiledPipelineCompiler must encode the http payload through " +
                "CoreHttpWireCodec (the single wire authority, G3.4).",
        )
    }

    @Test
    fun `compiler never writes the http wire vocabulary`() {
        val code = codeOnly(read(compilerSource))
        // Forbidden knowledge — WIRE-JSON AUTHORITY. The domain transformation
        // (StepSpec.HttpRequest -> CoreHttpInput with named arguments) is allowed:
        // those are the fields of a typed domain value checked by the Kotlin
        // compiler. What the compiler must never do is hand-write the http wire
        // JSON — the JsonObject keys and string-embedded spellings of that format.
        // `url` is deliberately absent here; see the class KDoc for why.
        val forbidden = listOf(
            "put(\"method\"",
            "put(\"customHeaders\"",
            "put(\"requestBody\"",
            "put(\"contentType\"",
            "put(\"acceptType\"",
            "put(\"validResponseCodes\"",
            "put(\"timeoutSeconds\"",
            "put(\"authentication\"",
            "put(\"kind\", \"httpRequest\"",
            "\\\"requestBody\\\"",
            "\\\"validResponseCodes\\\"",
            "\\\"customHeaders\\\"",
        )
        val found = forbidden.filter { code.contains(it) }
        assertTrue(
            found.isEmpty(),
            "DslCompiledPipelineCompiler hand-writes http wire JSON ($found). The compiler must " +
                "lower StepSpec.HttpRequest through CoreHttpWireCodec.encode, never author the " +
                "wire format (G3.4, SPEC_WU093_HTTP.md; the core.sh dialect split is the frozen " +
                "counter-example).",
        )
    }

    @Test
    fun `the url key has exactly one author and it is not the http path`() {
        val code = codeOnly(read(compilerSource))
        val authors = code.lines().filter { it.contains("put(\"url\"") }
        assertEquals(
            1,
            authors.size,
            "The compiler must write the `url` wire key exactly once — today that author is " +
                "core.checkout (DslCompiledPipelineCompiler.kt), and the second author is what a " +
                "hand-written http payload looks like. Found: $authors",
        )
    }

    @Test
    fun `http wire vocabulary lives in the wire authority`() {
        val code = codeOnly(read(wireCodecSource))
        val expected = listOf(
            "url",
            "method",
            "customHeaders",
            "requestBody",
            "contentType",
            "acceptType",
            "validResponseCodes",
            "timeoutSeconds",
            "authentication",
        )
        val missing = expected.filter { !code.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "CoreHttpWireCodec no longer spells $missing; the guard tracks a moved or renamed " +
                "wire authority. Update the path(s) this fitness scans, not the law.",
        )
    }
}
