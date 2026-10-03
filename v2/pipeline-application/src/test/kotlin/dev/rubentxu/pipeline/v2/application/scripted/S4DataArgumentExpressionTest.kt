package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallKind
import dev.rubentxu.pipeline.v2.scripting.ScriptedSource
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * S4-DATA — the scripted front end carries the author's argument EXPRESSION.
 *
 * ## What is being pinned
 *
 * `readFile`/`fileExists` used to be payload-free kinds, so the lowering had
 * nothing to put in the call and wrote a literal `""`. Two defects came from that
 * one gap: the path was dropped, and — with the span hardcoded to the length of
 * the empty-argument form — the argument's own text survived *past* the rewritten
 * call, so `readFile("config.yaml")` generated Kotlin that did not compile.
 *
 * The fix carries the expression as PSI text from the same `KtCallExpression`.
 * That is what makes the non-literal forms work at all, so this suite is mostly
 * about the forms a literal-only implementation would fail:
 *
 * ```kotlin
 * val file = "foo.txt"; readFile(file)
 * readFile("$dir/config.yaml")
 * readFile(path.resolve("x").toString())
 * sh(command(), returnStdout = true)
 * ```
 *
 * ## Why the generated source is compared as TEXT and not compiled
 *
 * These assertions compare the generated body, which is what proves the
 * expression survived. Compiling it is a separate, slower question: the
 * generated `execute(steps)` body references the surrounding locals, so it is
 * only well-formed in the scope the author wrote it in. The installed-distribution
 * run is what proves compilation; this suite is what proves the rewrite is
 * faithful. Asserting only "it compiled" would let an implementation that dropped
 * the argument and happened to emit valid Kotlin pass.
 */
class S4DataArgumentExpressionTest {

    private fun lower(body: String): LoweringResult.Generated {
        val result = ScriptedSourceLowering.lower(
            sourceId = ScriptedSourceId("s4data"),
            sourceText = body,
            mapper = KotlinScriptedSourceMapper(),
            facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
        )
        assertTrue(
            result is LoweringResult.Generated,
            "the mapper must accept this body: $body\nbut reported $result",
        )
        return result as LoweringResult.Generated
    }

    private fun body(generated: LoweringResult.Generated): String =
        generated.source
            .substringAfter("override suspend fun execute(steps: ScriptedStepFacade) {")
            .trim()
            .removeSuffix("}")
            .trim()
            .removeSuffix("}")
            .trim()

    /**
     * Simple kind names. A data-class `toString()` would make each assertion depend
     * on the payload text; the payload is asserted separately where it matters.
     */
    private fun kinds(generated: LoweringResult.Generated): List<String> =
        generated.mappedCalls.map { call ->
            when (val kind = call.kind) {
                is ScriptedCallKind.Shell -> "Shell:${kind.returnMode}"
                is ScriptedCallKind.ReadFile -> "ReadFile"
                is ScriptedCallKind.FileExists -> "FileExists"
                is ScriptedCallKind.Pwd -> "Pwd(tmp=${kind.tmp})"
                else -> kind.toString()
            }
        }

    // ==================================================================
    // D3 — expressions that are NOT literals
    // A literal-only implementation cannot produce any of these.
    // ==================================================================

    companion object {
        @JvmStatic
        fun nonLiteralArguments(): List<Arguments> = listOf(
            // A bare variable reference: the generated call must re-scope it, not
            // inline a value. If this emitted "", the call would compile and read the
            // empty path — the failure mode that is harder to see than a syntax error.
            Arguments.of(
                """val c = readFile(file)""",
                """val c = steps.readFile(ScriptedCallSiteId("s4data:1:9:readFile"), file)""",
            ),
            // String interpolation: the expression carries a template, so the path is
            // only known at run time. `${'$'}` keeps THIS test from interpolating the
            // `$dir` the pipeline under test is supposed to contain.
            Arguments.of(
                """val c = readFile("${'$'}dir/config.yaml")""",
                """val c = steps.readFile(ScriptedCallSiteId("s4data:1:9:readFile"), "${'$'}dir/config.yaml")""",
            ),
            // A method call: the most nested form, and the one a text-surgery lowering
            // is most likely to truncate mid-token.
            Arguments.of(
                """val e = fileExists(path.resolve("x").toString())""",
                """val e = steps.fileExists(ScriptedCallSiteId("s4data:1:9:fileExists"), path.resolve("x").toString())""",
            ),
            // Named argument form: `file = …` is ordinary Kotlin and must be accepted.
            Arguments.of(
                """val c = readFile(file = "a.txt")""",
                """val c = steps.readFile(ScriptedCallSiteId("s4data:1:9:readFile"), "a.txt")""",
            ),
        )
    }

    @ParameterizedTest(name = "a non-literal path expression survives the rewrite")
    @MethodSource("nonLiteralArguments")
    fun `D3 - the authored argument expression is re-scoped verbatim`(
        source: String,
        expected: String,
    ) {
        assertEquals(expected, body(lower(source)), "the rewrite must preserve the expression itself")
    }

    @Test
    fun `D3 - a script expression survives, so sh is not literal-only either`() {
        val generated = lower("""val out = sh(command(), returnStdout = true)""")
        assertEquals(
            """val out = steps.sh(ScriptedCallSiteId("s4data:1:11:sh:ro"), command(), ReturnStdout, null, null)""",
            body(generated),
            "the same expression-not-value rule applies to sh's script",
        )
    }

    // ==================================================================
    // D4 — nesting and ordering, where offset rewrites collide
    // ==================================================================

    @Test
    fun `D4 - a readFile nested inside an if is rewritten, and the guard keeps its own call`() {
        val generated = lower(
            """
            if (fileExists(path)) {
                val text = readFile(path)
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf("FileExists", "ReadFile"),
            kinds(generated),
            "both calls must be mapped; the inner one is not a different construct",
        )
        val text = body(generated)
        assertTrue(
            text.contains("""steps.fileExists(ScriptedCallSiteId("s4data:1:5:fileExists"), path)"""),
            "the guard's call must be rewritten: $text",
        )
        assertTrue(
            text.contains("""steps.readFile(ScriptedCallSiteId("s4data:2:16:readFile"), path)"""),
            "the nested call must be rewritten too: $text",
        )
        assertFalse(
            text.contains(")nfig"),
            "no fragment of any argument may survive a rewrite: $text",
        )
    }

    @Test
    fun `D4 - two calls on one line are both rewritten and neither corrupts the other`() {
        // Offset-locked rewrites are the mechanism most likely to break here: the
        // replacement for the FIRST call changes the offsets the second is resolved
        // against. The lowering walks calls in descending position order for exactly
        // this reason, and this pins that the order is still right after the
        // expression payload widened each replacement.
        val generated = lower("""val r = readFile(a) + fileExists(b)""")
        assertEquals(
            listOf("ReadFile", "FileExists"),
            kinds(generated),
        )
        assertEquals(
            """val r = steps.readFile(ScriptedCallSiteId("s4data:1:9:readFile"), a) + """ +
                """steps.fileExists(ScriptedCallSiteId("s4data:1:23:fileExists"), b)""",
            body(generated),
        )
    }

    @Test
    fun `D4 - a call inside a loop is rewritten once, and the runtime ordinal still distinguishes iterations`() {
        // The ordinal is a property of the RUNTIME, not of the lowering: one call
        // site, three iterations, ordinals 0/1/2. This pins that carrying the
        // expression did not change the call-site identity that feeds the ordinal.
        val generated = lower(
            """
            for (x in items) {
                val t = readFile(x)
            }
            """.trimIndent(),
        )
        val call = generated.mappedCalls.single()
        assertEquals(ScriptedCallKind.ReadFile("x"), call.kind)
        assertEquals(
            "s4data:2:13:readFile",
            call.location.readFileCallSite().value,
            "identity stays sourceId:line:column, with no loop ordinal folded in",
        )
    }

    // ==================================================================
    // Fail-closed: an argument shape this runtime cannot map is REJECTED
    // ==================================================================

    @Test
    fun `a readFile with no argument is rejected, not left to fail as Unresolved reference`() {
        val mapper = KotlinScriptedSourceMapper()
        val result = mapper.map(ScriptedSource(ScriptedSourceId("s4data"), """val c = readFile()"""))

        assertTrue(
            result is dev.rubentxu.pipeline.v2.scripting.ScriptedSourceMapping.InvalidSyntax,
            "an argument-less readFile is a call the author wrote, so it must be rejected " +
                "here rather than survive into the generated Kotlin and fail to compile. Got $result",
        )
    }

    @Test
    fun `a readFile with two arguments is rejected rather than silently dropping one`() {
        val mapper = KotlinScriptedSourceMapper()
        val result = mapper.map(ScriptedSource(ScriptedSourceId("s4data"), """val c = readFile("a", "b")"""))

        assertTrue(
            result is dev.rubentxu.pipeline.v2.scripting.ScriptedSourceMapping.InvalidSyntax,
            "the façade call takes one path, so a second argument would be dropped in the " +
                "generated call — a silent semantic drop. Got $result",
        )
    }
}
