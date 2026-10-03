package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallKind
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S4-A0 — characterization of the scripted lowering, BEFORE S4-A1 replaces it
 * and S4-B2 rewrites it. Nothing here asserts DESIRED behaviour. Each test
 * asserts what the lowering actually does today, so the baseline S4-A1 starts
 * from is measured rather than assumed.
 *
 * Three tests are named `CHARACTERIZED DEFECT` and assert a BROKEN behaviour on
 * purpose. That is what makes them valuable: they pin the defect so it cannot
 * change silently, and they go red the day someone fixes it — at which point
 * the test is rewritten to assert the correct behaviour, which is exactly the
 * S4-B2 work. A characterization suite that only contains the working cases
 * would hide all three.
 *
 * ## What was measured
 *
 * The lowering is textual. `ScriptedSourceMapper` (PSI) reports a call LOCATION;
 * `ScriptedSourceLowering.rewriteRuntimeReturningCalls` turns that into a
 * line/column OFFSET and overwrites a **fixed character count** derived from a
 * hardcoded rewrite target:
 *
 * ```
 * readFile("")     -> 12 characters
 * fileExists("")   -> 14 characters
 * ```
 *
 * while the mapper accepts `readFile(anything)` and `fileExists(anything)`. The
 * span it overwrites is therefore unrelated to the call the author wrote, and
 * `offsetOfAt` does not compare the text at that offset — its own KDoc calls
 * itself "a permissive offset locator".
 */
class S4A0ScriptedLoweringCharacterizationTest {

    private val mapper = KotlinScriptedSourceMapper()

    private fun lower(body: String): LoweringResult.Generated {
        val result = ScriptedSourceLowering.lower(
            sourceId = ScriptedSourceId("s4a0"),
            sourceText = body,
            mapper = mapper,
            facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
        )
        assertTrue(
            result is LoweringResult.Generated,
            "the mapper should accept a syntactically valid body: $result",
        )
        return result as LoweringResult.Generated
    }

    private fun kinds(generated: LoweringResult.Generated): List<String> =
        generated.mappedCalls.map { it.kind.toString() }

    /**
     * The statements inside the generated `execute(steps)` body.
     *
     * The generated source nests the body in the function and then in the
     * object, so two closing braces follow it; both are stripped or they would
     * contaminate every assertion below.
     */
    private fun body(generated: LoweringResult.Generated): String =
        generated.source
            .substringAfter("override suspend fun execute(steps: ScriptedStepFacade) {")
            .trim()
            .removeSuffix("}")
            .trim()
            .removeSuffix("}")
            .trim()

    // ==================================================================
    // WORKING CASES — the ones S4-A1 must preserve
    // ==================================================================

    @Test
    fun `pwd is rewritten to the facade and carries its call site`() {
        val generated = lower("val p = pwd()")
        assertEquals(listOf("Pwd(tmp=false)"), kinds(generated))
        assertEquals(
            """val p = steps.pwd(ScriptedCallSiteId("s4a0:1:9:pwd"), tmp = false)""",
            body(generated),
            "the whole call is replaced, argument for argument",
        )
    }

    @Test
    fun `pwd tmp is rewritten and preserves the variant`() {
        val generated = lower("val p = pwd(tmp = true)")
        assertEquals(listOf("Pwd(tmp=true)"), kinds(generated))
        assertTrue(
            body(generated).contains("tmp = true"),
            "the tmp variant must survive the rewrite: ${body(generated)}",
        )
    }

    @Test
    fun `isUnix is rewritten to the facade and carries its call site`() {
        val generated = lower("val u = isUnix()")
        assertEquals(listOf("IsUnix"), kinds(generated))
        assertEquals(
            """val u = steps.isUnix(ScriptedCallSiteId("s4a0:1:9:isUnix"))""",
            body(generated),
        )
    }

    @Test
    fun `an eager sh is left untouched, which is correct`() {
        val generated = lower("""sh("echo hi")""")
        assertEquals(listOf("Shell"), kinds(generated))
        assertEquals(
            """sh("echo hi")""",
            body(generated),
            "an eager sh must NOT be rewritten: it has no runtime value to return",
        )
    }

    @Test
    fun `the empty-argument readFile is the one form whose span happens to be right`() {
        val generated = lower("""val c = readFile("")""")
        assertEquals(listOf("ReadFile"), kinds(generated))
        assertEquals(
            """val c = steps.readFile(ScriptedCallSiteId("s4a0:1:9:readFile"), "")""",
            body(generated),
            "readFile(\"\") is 12 characters, which is exactly the hardcoded span",
        )
    }

    // ==================================================================
    // CHARACTERIZED DEFECT 1 of 3 — the span is the EMPTY-argument form
    // Destination: S4-B2 (exact PSI matching)
    // ==================================================================

    @Test
    fun `CHARACTERIZED DEFECT - readFile with a real path is truncated to invalid Kotlin`() {
        val generated = lower("""val c = readFile("config.yaml")""")
        assertEquals(
            listOf("ReadFile"),
            kinds(generated),
            "the mapper DOES accept readFile with a real path",
        )
        // The authored call is 24 characters; the rewriter consumes 12 and the
        // tail of the author's argument survives past the rewritten call.
        assertEquals(
            """val c = steps.readFile(ScriptedCallSiteId("s4a0:1:9:readFile"), "")nfig.yaml")""",
            body(generated),
            "MEASURED DEFECT: the rewrite consumes the length of readFile(\"\") regardless of " +
                "the authored argument, so a real path leaves a fragment behind and the generated " +
                "source is not valid Kotlin. If this test fails, the span was fixed — rewrite it " +
                "to assert the whole-call rewrite (S4-B2).",
        )
    }

    @Test
    fun `CHARACTERIZED DEFECT - fileExists with a real path is truncated to invalid Kotlin`() {
        val generated = lower("""val e = fileExists("config.yaml")""")
        assertEquals(listOf("FileExists"), kinds(generated))
        assertEquals(
            """val e = steps.fileExists(ScriptedCallSiteId("s4a0:1:9:fileExists"), "")nfig.yaml")""",
            body(generated),
            "MEASURED DEFECT: same cause as readFile, with a 14-character empty-form span. " +
                "Fails the day the span is fixed (S4-B2).",
        )
    }

    // ==================================================================
    // CHARACTERIZED DEFECT 2 of 3 — ShellReturnStdout is unreachable
    // Destination: S4-A2 (the migration) / S4-B2 (classification)
    // ==================================================================

    @Test
    fun `CHARACTERIZED DEFECT - sh with returnStdout is classified EAGER and never rewritten`() {
        val generated = lower("""val out = sh("echo hi", returnStdout = true)""")
        assertEquals(
            listOf("Shell"),
            kinds(generated),
            "MEASURED DEFECT: the author asked for stdout as a typed value and the mapper " +
                "classified it as the EAGER sh, because the eager branch is the FIRST arm of the " +
                "when and tests only the callee name, so the runtime-returning arm below it can " +
                "never be selected. `ScriptedCallKind.ShellReturnStdout` is unreachable.",
        )
        assertEquals(
            """val out = sh("echo hi", returnStdout = true)""",
            body(generated),
            "MEASURED DEFECT: the call is left as the raw eager sh, so `out` binds to whatever " +
                "the eager call yields and the author's returnStdout = true is a silent semantic " +
                "drop. The mapper's own KDoc claims this case is 'distinct from the eager " +
                "sh(...) branch above — both compile-time legal'; the code does not implement " +
                "that distinction.",
        )
    }

    @Test
    fun `CHARACTERIZED DEFECT - no sh spelling can ever reach ShellReturnStdout`() {
        // The consequence, stated separately so it cannot be read as one bad
        // input. Every unqualified `sh(...)` is classified Shell, whatever its
        // arguments, so no authoring of `sh` reaches the runtime-returning arm.
        val spellings = listOf(
            """sh("echo hi", returnStdout = true)""",
            """val a = sh("x", returnStdout = true)""",
            """val b = sh(script = "x", returnStdout = true)""",
            """if (isUnix()) { sh("x", returnStdout = true) }""",
        )
        for (spelling in spellings) {
            assertTrue(
                kinds(lower(spelling)).none { it.startsWith("ShellReturnStdout") },
                "no spelling of sh reaches the runtime-returning arm, but <$spelling> did",
            )
        }
    }

    // ==================================================================
    // CHARACTERIZED DEFECT 3 of 3 — identity has no loop ordinal
    // Destination: S4-C1
    // ==================================================================

    @Test
    fun `CHARACTERIZED DEFECT - a call inside a loop gets one identity, not one per iteration`() {
        val generated = lower(
            """
            for (i in 0 until 3) {
                val v = pwd()
            }
            """.trimIndent(),
        )
        val callSites = Regex("""ScriptedCallSiteId\("([^"]+)"\)""").findAll(generated.source)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            1,
            callSites.size,
            "MEASURED DEFECT: the loop body lowers to ONE call site, so all three iterations " +
                "share one identity. There is no loop ordinal in the call-site derivation " +
                "(${callSites}), which is what S4-C1 has to add before replay can distinguish " +
                "iteration 0 from iteration 2.",
        )
    }
}
