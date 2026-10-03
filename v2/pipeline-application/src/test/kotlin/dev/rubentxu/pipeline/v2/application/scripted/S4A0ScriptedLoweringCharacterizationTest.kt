package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallKind
import dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    // IDENTITY — corrected characterization
    // ==================================================================

    @Test
    fun `a loop at one call site does get distinct durable identities per iteration`() {
        // CORRECTS an earlier characterization in this file, which claimed the
        // lowering's single call site meant all iterations shared one identity.
        // That was wrong, and the correction matters more than the original
        // claim: the RUNTIME assigns the ordinal, not the lowering.
        //
        //   ScriptedScope.ordinals : MutableMap<callSiteId + scopePath, Int>
        //   ScriptedScope.nextOrdinal(callSite) -> and increments
        //
        // so `for (i in 0 until 3) { pwd() }` lowers to one call site and still
        // executes under ordinals 0, 1, 2. The property S4-C1 needs is therefore
        // ALREADY satisfied, and what remains for S4-C1 is the caveat below, not
        // the ordinal itself.
        val base = ScriptedRegistryCall(
            runId = "run",
            entryPointId = "entry",
            callSiteId = ScriptedCallSiteId("entry:1:9:pwd"),
            dynamicScopePath = emptyList(),
            invocationOrdinal = 0,
            stepKey = PluginStepId("core.pwd"),
            encodedInput = EncodedStepValue("{}"),
        )
        val second = base.copy(invocationOrdinal = 1)
        val third = base.copy(invocationOrdinal = 2)

        assertNotEquals(
            base.operationId(),
            second.operationId(),
            "iterations 0 and 1 of the same call site must not share a durable identity",
        )
        assertNotEquals(second.operationId(), third.operationId())
        assertEquals(
            base.operationId(),
            base.copy().operationId(),
            "the same call site and ordinal MUST reproduce the same identity — that is what " +
                "makes replay work at all",
        )
    }

    @Test
    fun `CHARACTERIZED CAVEAT - an ordinal is an EXECUTION COUNT, not a structural position`() {
        // The real S4-C1/C2 constraint, stated correctly. Identity is
        // (callSite, scopePath, ordinal) and the ordinal is produced by a
        // per-scope counter that lives in process memory. Replay is therefore
        // correct only while the resumed execution REACHES each call site the
        // same number of times. That holds when every branch decision is made
        // from a journaled value, which is exactly what S4-C2 has to prove.
        //
        // This is a constraint, not a proven defect: no test here shows replay
        // drifting. It is recorded so S4-C1/C2 is designed against the real
        // mechanism rather than against the assumption that the ordinal is
        // derived from source structure.
        val first = ScriptedRegistryCall(
            runId = "run",
            entryPointId = "entry",
            callSiteId = ScriptedCallSiteId("entry:1:9:pwd"),
            dynamicScopePath = emptyList(),
            invocationOrdinal = 0,
            stepKey = PluginStepId("core.pwd"),
            encodedInput = EncodedStepValue("{}"),
        )
        val insideScope = first.copy(dynamicScopePath = listOf("loop-0"))
        assertNotEquals(
            first.operationId(),
            insideScope.operationId(),
            "the same call site under a different dynamic scope must have its own identity",
        )
    }
}
