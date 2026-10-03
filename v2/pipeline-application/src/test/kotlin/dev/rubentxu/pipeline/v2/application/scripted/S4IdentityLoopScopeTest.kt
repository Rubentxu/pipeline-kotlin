package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSource
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering.LoweringResult
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceMapping
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S4-IDENTITY I2a — the compiler emits a STRUCTURAL scope for a `for` body.
 *
 * ## What I1 left open
 *
 * I1 falsified the invocation ordinal against production wiring and found the
 * measurement that constrains everything downstream: inside a real `for` loop the
 * ONLY thing distinguishing one iteration from the next is
 * `ScriptedScope.nextOrdinal` — a process-local arrival counter. The runtime half
 * that could carry structure already existed (`ScriptedScope.scoped`), and
 * `ScriptedScopeTest` exercised it, but a grep of the whole
 * `pipeline-scripting-kotlin24` module showed the lowering emitted NO loop scope
 * at all, so no author-written loop could ever reach it.
 *
 * I2a supplies that missing compiler half. It is deliberately scoped to it.
 *
 * ## What I2a does NOT claim
 *
 * It does NOT close the I1 silent-no-op defect. The ordinal remains an arrival
 * counter and remains non-durable across a resume; I2a only makes it *scoped to an
 * identified loop* instead of floating in a global count, which is the structure
 * I2b needs in order to replace that counter with a deterministic occurrence path.
 * Several tests below assert the scope is emitted and that identity follows it —
 * none of them assert a loop survives a kill, because that is not what this slice
 * implements. Reading a green run here as "loop identity is durable" would be the
 * same unearned claim S4-A1b had to undo once.
 *
 * ## Why the scope id is positional
 *
 * The first draft derived it from the loop parameter (`loop:i[i]`). That was wrong
 * twice over, and both faults are now pinned by tests: the `[i]` slot advertised an
 * iteration index that the emitted string cannot know (it is a constant evaluated
 * before the body runs), and a parameter name is not unique in a file, so two
 * different `for (i in …)` loops would have composed the same dynamic path and
 * merged distinct call sites onto one durable identity.
 */
@Timeout(20)
class S4IdentityLoopScopeTest {

    private fun map(body: String): ScriptedSourceMapping.Mapped {
        val result = KotlinScriptedSourceMapper().map(ScriptedSource(ScriptedSourceId(SOURCE_ID), body))
        assertTrue(
            result is ScriptedSourceMapping.Mapped,
            "the mapper must accept this body:\n$body\nbut reported $result",
        )
        return result as ScriptedSourceMapping.Mapped
    }

    private fun lower(body: String): LoweringResult.Generated {
        val result = ScriptedSourceLowering.lower(
            sourceId = ScriptedSourceId(SOURCE_ID),
            sourceText = body,
            mapper = KotlinScriptedSourceMapper(),
            facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
        )
        assertTrue(
            result is LoweringResult.Generated,
            "the lowering must accept this body:\n$body\nbut reported $result",
        )
        return result as LoweringResult.Generated
    }

    /** The rewritten body, without the generated entry-point wrapper. */
    private fun body(generated: LoweringResult.Generated): String {
        val marker = "override suspend fun execute(steps: ScriptedStepFacade) {"
        val start = generated.source.indexOf(marker) + marker.length
        val end = generated.source.indexOf("\n            }", start)
        check(end > start) { "generated source has no `execute` body:\n${generated.source}" }
        // The body is interpolated into an indented template line, so its first
        // line carries the template's indent and every other line keeps the
        // author's. `trimIndent` cannot undo that: the wrapper's first and last
        // lines sit at column 0, so the common prefix is empty.
        val raw = generated.source.substring(start, end)
        check(raw.startsWith("\n")) { "the `execute` body must start on the line after the marker" }
        val content = raw.substring(1)
        val firstLineEnd = content.indexOf('\n')
        val indent = content.substring(0, firstLineEnd).takeWhile { it == ' ' }
        return content.removeRange(0, indent.length)
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ==================================================================
    // M — the mapper records the loop as a structural fact
    // ==================================================================

    @Test
    fun `a for loop with a block body is recorded with the body's own brace offsets`() {
        val source = """
            for (i in listOf("a", "b")) {
                val copy = i
            }
        """.trimIndent()

        val scope = map(source).loopScopes.single()

        assertEquals(source.indexOf('{'), scope.bodyStartOffset, "open brace offset")
        assertEquals(source.indexOf('}'), scope.bodyEndOffset, "close brace offset")
        assertEquals("i", scope.loopParameter)
        assertEquals(1, scope.location.line, "the loop, not the body, is the identity anchor")
    }

    @Test
    fun `the scope id is the loop's position and does not contain its parameter name`() {
        val source = """
            for (i in listOf("a", "b")) {
                val copy = i
            }
        """.trimIndent()

        val scope = map(source).loopScopes.single()

        assertEquals("loop:$SOURCE_ID:1:1", scope.scopeId.value)
        assertTrue(
            !scope.scopeId.value.contains("i["),
            "the scope id must not fabricate an iteration suffix it cannot know: ${scope.scopeId.value}",
        )
    }

    /**
     * The property that makes loop identity survive a rename, and the fault that
     * made the parameter name the wrong anchor: names repeat, positions do not.
     */
    @Test
    fun `renaming the loop parameter does not change the scope id`() {
        fun scopeIdFor(parameter: String): String {
            val source = """
                for ($parameter in listOf("a", "b")) {
                    val copy = $parameter
                }
            """.trimIndent()
            return map(source).loopScopes.single().scopeId.value
        }

        assertEquals(scopeIdFor("i"), scopeIdFor("idx"), "identity must not depend on an author-chosen name")
    }

    @Test
    fun `two loops reusing one parameter name get two distinct scope ids`() {
        val source = """
            for (i in listOf("a", "b")) {
                val first = i
            }
            for (i in listOf("c")) {
                val second = i
            }
        """.trimIndent()

        val scopes = map(source).loopScopes

        assertEquals(2, scopes.size)
        assertNotEquals(
            scopes[0].scopeId.value,
            scopes[1].scopeId.value,
            "two different loops must never compose the same dynamic path",
        )
    }

    @Test
    fun `nested loops are recorded as two scopes at their own positions`() {
        val source = """
            for (outer in listOf(1)) {
                for (inner in listOf(2)) {
                    val pair = outer to inner
                }
            }
        """.trimIndent()

        val scopes = map(source).loopScopes

        assertEquals(2, scopes.size)
        assertEquals(listOf("outer", "inner"), scopes.map { it.loopParameter })
        assertEquals(1, scopes[0].location.line)
        assertEquals(2, scopes[1].location.line)
        assertNotEquals(scopes[0].scopeId.value, scopes[1].scopeId.value)
    }

    // ==================================================================
    // M — the shapes I2a deliberately does not take
    // ==================================================================

    @Test
    fun `a while loop is left alone because I2a covers for loops only`() {
        val source = """
            var n = 0
            while (n < 3) {
                n = n + 1
            }
        """.trimIndent()

        assertEquals(
            emptyList<Any>(),
            map(source).loopScopes,
            "wrapping `while` would need a different law (it has no structural iteration count); " +
                "I2a must not silently claim it",
        )
    }

    @Test
    fun `a braceless for body is left alone rather than rejected`() {
        val source = """
            for (i in listOf("a", "b")) println(i)
        """.trimIndent()

        val result = KotlinScriptedSourceMapper().map(ScriptedSource(ScriptedSourceId(SOURCE_ID), source))

        assertTrue(
            result is ScriptedSourceMapping.Mapped,
            "a braceless body works today through the arrival counter; refusing it would introduce a new failure mode",
        )
        assertEquals(emptyList<Any>(), (result as ScriptedSourceMapping.Mapped).loopScopes)
    }

    @Test
    fun `a source without loops carries no loop scope`() {
        val source = """
            val a = readFile("x.txt")
            val b = fileExists("y.txt")
        """.trimIndent()

        assertEquals(emptyList<Any>(), map(source).loopScopes)
    }

    // ==================================================================
    // L — the lowering emits the scope
    // ==================================================================

    /**
     * Rewrite fidelity. The new rewriter resolves every edit against the original
     * text and applies it back to front; the previous one walked a mutating buffer
     * in reverse. A body with a loop and no runtime-returning call is the cleanest
     * probe: the generated text must be the source plus exactly the two
     * insertions, with nothing else moved.
     */
    @Test
    fun `the loop body is wrapped by exactly two insertions and nothing else changes`() {
        val source = """
            for (i in listOf("a", "b")) {
                val copy = i
            }
            echo("done")
        """.trimIndent()
        val scope = map(source).loopScopes.single()
        val expected = source.substring(0, scope.bodyStartOffset + 1) +
            "\nsteps.scoped(ScriptedDynamicScopeId(\"${scope.scopeId.value}\")) {" +
            source.substring(scope.bodyStartOffset + 1, scope.bodyEndOffset) +
            "\n}" +
            source.substring(scope.bodyEndOffset)

        val generated = lower(source)

        assertEquals(expected, body(generated), "the wrapper must be the ONLY change to the source")
        assertEquals(1, occurrences(generated.source, "steps.scoped("))
        assertEquals(1, occurrences(generated.source, "ScriptedDynamicScopeId(\"loop:$SOURCE_ID:1:1\")"))
    }

    @Test
    fun `a runtime-returning call inside the loop is rewritten inside the scope`() {
        val source = """
            for (path in listOf("a.txt", "b.txt")) {
                val text = readFile(path)
                echo(text)
            }
        """.trimIndent()
        val callSite = map(source).calls
            .single { it.kind is dev.rubentxu.pipeline.v2.scripting.ScriptedCallKind.ReadFile }
            .location.readFileCallSite()
            .value

        val generated = lower(source).source

        val open = generated.indexOf("steps.scoped(ScriptedDynamicScopeId(\"loop:$SOURCE_ID:1:1\")) {")
        // The author's own expression survives — an implementation that dropped the
        // payload would emit `steps.readFile(..., )` and this would not be found.
        val rewritten = generated.indexOf("steps.readFile(ScriptedCallSiteId(\"$callSite\"), path)")
        val close = generated.indexOf("\n}", open)

        assertTrue(open >= 0, "the scope must be emitted:\n$generated")
        assertTrue(
            rewritten >= 0,
            "the call must keep the author's expression `path`:\n$generated",
        )
        assertTrue(
            open < rewritten && rewritten < close,
            "the rewritten call must sit INSIDE the scope (open=$open call=$rewritten close=$close):\n$generated",
        )
    }

    /**
     * Nesting is what the back-to-front application exists for: a wrapper around
     * the outer body shifts every offset the inner loop's edits were resolved
     * against. Each loop's own close must land inside the next one out.
     */
    @Test
    fun `nested loops open outside-in and close inside-out`() {
        val source = """
            for (outer in listOf(1)) {
                for (inner in listOf(2)) {
                    val pair = outer to inner
                }
            }
            echo("done")
        """.trimIndent()
        val scopes = map(source).loopScopes

        val text = body(lower(source))
        val outerOpen = text.indexOf(scopes[0].scopeId.value)
        val innerOpen = text.indexOf(scopes[1].scopeId.value)
        val innerBody = text.indexOf("val pair")
        val innerClose = text.indexOf("\n}", innerOpen)
        val outerClose = text.indexOf("\n}", innerClose + 1)

        assertTrue(
            outerOpen in 0..innerOpen,
            "the outer scope must open first ($outerOpen vs $innerOpen)\n--- body ---\n$text\n--- full ---\n${lower(source).source}",
        )
        assertTrue(
            innerOpen in 0..innerBody,
            "the inner body must be inside BOTH scopes (innerOpen=$innerOpen body=$innerBody):\n$text",
        )
        assertTrue(
            innerClose in 0..outerClose,
            "the inner scope must close before the outer one ($innerClose vs $outerClose):\n$text",
        )
    }

    @Test
    fun `a source with no loop emits no scope at all`() {
        val source = """
            val a = readFile("x.txt")
        """.trimIndent()

        val generated = lower(source).source

        assertEquals(0, occurrences(generated, "steps.scoped("), "the no-loop path must be untouched")
        assertEquals(0, occurrences(generated, "ScriptedDynamicScopeId("))
    }

    @Test
    fun `lowering the same source twice yields byte-identical output`() {
        val source = """
            for (i in listOf("a", "b")) {
                val exists = fileExists(i)
                echo(exists)
            }
        """.trimIndent()

        assertEquals(lower(source).source, lower(source).source, "the edit order must be deterministic, not incidental")
    }

    // ==================================================================
    // R — the identity consequence, against the production runtime
    // ==================================================================

    /**
     * What the emitted scope id actually buys. Two loops in one program, each
     * running the same call-site text, must reach the journal under different
     * dynamic paths. With a parameter-derived id they would have shared one path.
     */
    @Test
    fun `two loops reach the runtime under different dynamic scope paths`() = runBlocking {
        val captured = mutableListOf<ScriptedOperation>()
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime { operation ->
                captured += operation
                ShellInvocationResult.UnitValue
            },
            callSites = ScriptedCallSiteProvider.fixed("site"),
        )

        runtime.run(definitionDigest = "digest", entryPointId = "entry") {
            scoped(ScriptedDynamicScopeId("loop:a:1:1")) { sh("first") }
            scoped(ScriptedDynamicScopeId("loop:a:4:1")) { sh("second") }
        }

        assertEquals(2, captured.size)
        assertEquals(listOf("loop:a:1:1"), captured[0].dynamicScopePath)
        assertEquals(listOf("loop:a:4:1"), captured[1].dynamicScopePath)
        assertNotEquals(
            captured[0].operationId(),
            captured[1].operationId(),
            "distinct structural paths must yield distinct durable identities",
        )
    }

    /**
     * And the counter is confined to the scope: the second loop's first arrival
     * is ordinal 0 again, not a continuation of the first loop's arrivals. This is
     * the improvement I2a delivers — and it is deliberately NOT the same claim as
     * "iteration identity is durable", which still depends on a live counter.
     */
    @Test
    fun `the ordinal is counted within the scope, not globally`() = runBlocking {
        val captured = mutableListOf<ScriptedOperation>()
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime { operation ->
                captured += operation
                ShellInvocationResult.UnitValue
            },
            callSites = ScriptedCallSiteProvider.fixed("site"),
        )

        runtime.run(definitionDigest = "digest", entryPointId = "entry") {
            scoped(ScriptedDynamicScopeId("loop:a:1:1")) {
                sh("one")
                sh("two")
            }
            scoped(ScriptedDynamicScopeId("loop:a:4:1")) { sh("three") }
        }

        assertEquals(listOf(0, 1, 0), captured.map { it.invocationOrdinal })
    }

    private companion object {
        const val SOURCE_ID = "s4identity"
    }
}
