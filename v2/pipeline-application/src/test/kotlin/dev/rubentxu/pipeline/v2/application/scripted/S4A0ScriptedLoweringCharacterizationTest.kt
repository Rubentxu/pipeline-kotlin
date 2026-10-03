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

    /**
     * Simple names of the mapped kinds, with the `sh` shape appended so a test can
     * see WHICH of the three shapes was classified rather than only that it was `sh`.
     *
     * S4-DATA: the simple name is used rather than `toString()`, because `readFile`
     * and `fileExists` now carry their argument expression and a data-class
     * `toString()` would make every kind assertion depend on the payload text.
     * Tests that care about the payload assert it directly.
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
    fun `S4-A1 - an eager sh IS rewritten now, onto the facade, with its script intact`() {
        val generated = lower("""sh("echo hi")""")
        assertEquals(listOf("Shell:NONE"), kinds(generated))
        assertEquals(
            """steps.sh(ScriptedCallSiteId("s4a0:1:1:sh:none"), "echo hi", null, null)""",
            body(generated),
            "S4-A1: an eager sh is a REAL runtime-returning call and must be rewritten onto " +
                "the facade. Leaving it untouched is what made a scripted sh fail to compile " +
                "with `Unresolved reference 'sh'`: the bare call survived into the generated " +
                "Kotlin with no receiver.",
        )
    }

    @Test
    fun `S4-DATA - readFile carries the authored path expression`() {
        val generated = lower("""val c = readFile("config.yaml")""")
        assertEquals(listOf("ReadFile"), kinds(generated))
        assertEquals(
            """val c = steps.readFile(ScriptedCallSiteId("s4a0:1:9:readFile"), "config.yaml")""",
            body(generated),
            "S4-DATA: the author's own expression is re-scoped into the façade call. It used " +
                "to be a literal \"\", which dropped the path; and combined with the old " +
                "hardcoded span it left `nfig.yaml\")` trailing after the rewritten call, so " +
                "the generated source did not compile.",
        )
    }

    // ==================================================================
    // S4-DATA — the path EXPRESSION is carried, so the call is usable
    // Corpus: see S4DataArgumentExpressionTest for the non-literal forms
    // ==================================================================

    @Test
    fun `S4-DATA - fileExists carries the authored path expression`() {
        val generated = lower("""val e = fileExists("config.yaml")""")
        assertEquals(listOf("FileExists"), kinds(generated))
        assertEquals(
            """val e = steps.fileExists(ScriptedCallSiteId("s4a0:1:9:fileExists"), "config.yaml")""",
            body(generated),
            "S4-DATA: same property as readFile — the expression, not a placeholder.",
        )
    }

    // ==================================================================
    // CHARACTERIZED DEFECT 2 of 3 — ShellReturnStdout is unreachable
    // Destination: S4-A2 (the migration) / S4-B2 (classification)
    // ==================================================================

    @Test
    fun `S4-A1 - sh with returnStdout is classified STDOUT and rewritten to the facade`() {
        val generated = lower("""val out = sh("echo hi", returnStdout = true)""")
        assertEquals(
            listOf("Shell:STDOUT"),
            kinds(generated),
            "S4-A1: the mapper now distinguishes the three sh shapes by ARGUMENT FORM in a " +
                "single arm, so the requested shape survives classification instead of being " +
                "swallowed by the eager branch. Previously every sh was classified the same " +
                "way and `returnStdout = true` was a silent semantic drop.",
        )
        assertEquals(
            """val out = steps.sh(ScriptedCallSiteId("s4a0:1:11:sh:ro"), "echo hi", ReturnStdout, null, null)""",
            body(generated),
            "S4-A1: the call must be rewritten through the facade with the marker overload, " +
                "so the author's requested shape reaches the runtime as a typed value.",
        )
    }

    @Test
    fun `S4-A1 - every sh spelling now reaches a classified shape, none is silently eager`() {
        // RETRACTS the characterization this file previously carried. `ShellReturnStdout`
        // no longer exists: one `Shell` kind carries the shape in its payload, so the old
        // "no spelling can reach the runtime-returning arm" statement is not just false,
        // it is unanswerable. What replaces it is the property that actually matters —
        // no authoring of `sh` is classified as the no-value shape when it asks for a
        // value.
        val asking = listOf(
            """sh("echo hi", returnStdout = true)""",
            """val a = sh("x", returnStdout = true)""",
            """val b = sh(script = "x", returnStdout = true)""",
            """if (isUnix()) { sh("x", returnStdout = true) }""",
            """val c = sh("x", returnStatus = ReturnStatus)""",
        )
        for (spelling in asking) {
            assertTrue(
                kinds(lower(spelling)).none { it == "Shell:NONE" },
                "a sh call that asks for a value must never be classified as the no-value " +
                    "shape, but <$spelling> was",
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
            definitionDigest = "s4-test-artifact-v1",
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
            definitionDigest = "s4-test-artifact-v1",
)
        val insideScope = first.copy(dynamicScopePath = listOf("loop-0"))
        assertNotEquals(
            first.operationId(),
            insideScope.operationId(),
            "the same call site under a different dynamic scope must have its own identity",
        )
    }
}
