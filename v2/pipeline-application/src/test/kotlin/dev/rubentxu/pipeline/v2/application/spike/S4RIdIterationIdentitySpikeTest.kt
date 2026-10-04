package dev.rubentxu.pipeline.v2.application.spike

import dev.rubentxu.pipeline.v2.application.scripted.ScriptedOperation
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedOperationRuntime
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedRuntime
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedScope
import dev.rubentxu.pipeline.v2.application.scripted.ScriptedCallSiteProvider
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.scripting.KotlinScriptedSourceMapper
import dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSource
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLowering
import dev.rubentxu.pipeline.v2.scripting.ScriptedSourceMapping
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S4-R-ID — SPIKE, measurement only. **Zero production change.**
 *
 * ## The question this falsifies
 *
 * AGENTS.md **DR-6** states that iteration identity is based on deterministic
 * iteration POSITION within a specific structural loop site, and that it MUST NOT
 * depend "on how many effects happened inside previous iterations".
 *
 * The candidate decision the owner named is:
 *
 * ```text
 * D: ordinal of ENTRY into the iteration, produced at the loop boundary and
 *    reconstructed by deterministic replay.
 * ```
 *
 * This spike asks one question of production, and nothing else:
 *
 * > **Does the durable identity production composes today carry the iteration
 * > index, or only the count of arrivals at the call site?**
 *
 * ## Harness fidelity
 *
 * It crosses the productive authority and re-implements **nothing**:
 *
 * - the compiler half is the real [KotlinScriptedSourceMapper] and the real
 *   [ScriptedSourceLowering], so the scope id asserted here is the one production emits;
 * - the runtime half is the real [ScriptedRuntime] → [ScriptedScope.scoped] →
 *   `invokeAt`/`nextOrdinal`, recording the [ScriptedOperation] identity inputs
 *   production actually composes.
 *
 * Verdict: **measurement**, never certification. See "What this does NOT claim".
 *
 * ## What this does NOT claim
 *
 * It does NOT claim loop identity is durable, and it does NOT claim the
 * positional model is implemented. Both are open. The
 * [S4IdentityLoopScopeTest] KDoc already says the ordinal "remains an arrival
 * counter and remains non-durable across a resume", and names the missing slice:
 * *I2b, which replaces that counter with a deterministic occurrence path*.
 *
 * No mutation is attached here, deliberately. The property under study is
 * **open, not closed**; a mutation would certify a claim this slice does not make.
 * The mutation that kills these rows is the one I2b must earn, and it belongs to
 * the slice that closes the defect.
 *
 * Rows that pass below are characterisation: they pin what production does, so
 * that the I2b change has a red baseline to move.
 */
@Timeout(30)
class S4RIdIterationIdentitySpikeTest {

    private val sourceId = ScriptedSourceId("s4-r-id.pipeline.kts")
    private val loopScope = ScriptedDynamicScopeId("loop:s4-r-id.pipeline.kts:1:1")

    // ==================================================================
    // the harness — real runtime, real identity inputs
    // ==================================================================

    /** Every [ScriptedOperation] the real runtime produced, in order. */
    private fun runRecording(body: suspend ScriptedScope.() -> Unit): List<ScriptedOperation> {
        val recorded = mutableListOf<ScriptedOperation>()
        val runtime = ScriptedRuntime(
            operationRuntime = ScriptedOperationRuntime { operation ->
                recorded += operation
                ShellInvocationResult.UnitValue
            },
            callSites = ScriptedCallSiteProvider.fixed("pipeline.kts:1:step"),
        )
        runBlocking {
            runtime.run(
                definitionDigest = "spike-digest",
                entryPointId = "spike-entry",
                runId = "spike-run",
                block = body,
            )
        }
        return recorded
    }

    private fun ordinalsOf(operations: List<ScriptedOperation>) = operations.map { it.invocationOrdinal }

    private fun scopePathsOf(operations: List<ScriptedOperation>) =
        operations.map { it.dynamicScopePath.joinToString("/") }

    // ==================================================================
    // COMPILER HALF — the scope id production emits
    // ==================================================================

    @Test
    fun `MEASURED - the emitted loop scope id carries no iteration component, because it cannot`() {
        val source = """
            for (i in listOf("a", "b", "c")) {
                sh("build")
            }
        """.trimIndent()

        val mapping = KotlinScriptedSourceMapper().map(ScriptedSource(sourceId, source))
        assertTrue(
            mapping is ScriptedSourceMapping.Mapped,
            "the mapper must accept this source, but reported $mapping",
        )
        val scopeId = (mapping as ScriptedSourceMapping.Mapped).loopScopes.single().scopeId.value

        val generated = ScriptedSourceLowering.lower(
            sourceId = sourceId,
            sourceText = source,
            mapper = KotlinScriptedSourceMapper(),
            facadeSchemaVersion = ScriptedSourceLowering.FACADE_SCHEMA_VERSION,
        )
        assertTrue(
            generated is ScriptedSourceLowering.LoweringResult.Generated,
            "the lowering must accept this source, but reported $generated",
        )

        assertEquals(
            "loop:s4-r-id.pipeline.kts:1:1",
            scopeId,
            "MEASURED: the compiler emits one scope id per loop SITE, derived from the loop's " +
                "position. It is a constant evaluated before the body runs, so it cannot carry an " +
                "iteration index. Whatever distinguishes one iteration from the next must " +
                "therefore come from the runtime half, which is exactly what the rows below " +
                "measure.",
        )
        assertTrue(
            (generated as ScriptedSourceLowering.LoweringResult.Generated).source
                .contains("steps.scoped(ScriptedDynamicScopeId(\"$scopeId\"))"),
            "MEASURED: the generated source wraps the loop body in that constant scope.\n" +
                "GENERATED WAS:\n${generated.source}",
        )
    }

    // ==================================================================
    // RUNTIME HALF — what the identity actually is
    // ==================================================================

    @Test
    fun `MEASURED P1 - one call site reached once per iteration gets three distinct ordinals`() {
        val operations = runRecording {
            for (iteration in 0..2) {
                scoped(loopScope) { sh("build") }
            }
        }

        assertEquals(3, operations.size, "three iterations must reach the call site three times")
        assertEquals(
            listOf(0, 1, 2),
            ordinalsOf(operations),
            "MEASURED P1: identity is distinct per iteration. This property production SATISFIES, " +
                "and it is the reason the defect below is not a correctness bug today.",
        )
    }

    @Test
    fun `MEASURED P4 - identity does not depend on the element value`() {
        val duplicates = runRecording {
            for (item in listOf("x", "x", "x")) {
                scoped(loopScope) { sh("build") }
            }
        }
        val distinctValues = runRecording {
            for (item in listOf("a", "b", "c")) {
                scoped(loopScope) { sh("build") }
            }
        }

        assertEquals(
            ordinalsOf(distinctValues),
            ordinalsOf(duplicates),
            "MEASURED P4: three identical elements and three different elements produce the SAME " +
                "identity sequence. Identity is independent of the element value. Property " +
                "SATISFIED — the parameter name is not part of the scope id either, which is " +
                "what S4IdentityLoopScopeTest already pinned.",
        )
    }

    @Test
    fun `MEASURED P3 - the whole dynamic scope is known before the first effect`() {
        val operations = runRecording {
            scoped(loopScope) { sh("build") }
        }

        assertEquals(
            listOf(listOf("loop:s4-r-id.pipeline.kts:1:1")),
            operations.map { it.dynamicScopePath },
            "MEASURED P3: the complete dynamic scope is present on the very first operation, " +
                "before any effect has run. Property SATISFIED — DR-7 holds for the scope half " +
                "of the identity, which is the half I2a delivered.",
        )
    }

    @Test
    fun `MEASURED P6 - nested scopes compose into a path`() {
        val operations = runRecording {
            for (outer in 0..1) {
                scoped(ScriptedDynamicScopeId("loop:outer")) {
                    for (inner in 0..1) {
                        scoped(ScriptedDynamicScopeId("loop:inner")) { sh("build") }
                    }
                }
            }
        }

        assertEquals(
            listOf(
                listOf("loop:outer", "loop:inner"),
                listOf("loop:outer", "loop:inner"),
                listOf("loop:outer", "loop:inner"),
                listOf("loop:outer", "loop:inner"),
            ),
            operations.map { it.dynamicScopePath },
            "MEASURED P6: nested loop scopes compose into one path.",
        )
        assertNotEquals(
            operations[0].operationId(),
            operations[1].operationId(),
            "and distinct iterations still get distinct durable identities.",
        )
    }

    @Test
    fun `MEASURED P9 - no journal is consulted to build the identity`() {
        // The harness has no journal, no OperationJournal instance and no recovery
        // seam at all, and the identity is still fully composed. DR-8 is satisfied
        // by construction, and the spike needs no fixture to show it.
        val operations = runRecording {
            for (iteration in 0..2) {
                scoped(loopScope) { sh("build") }
            }
        }

        assertEquals(
            3,
            operations.map { it.operationId() }.distinct().size,
            "MEASURED P9: identity is computable with no durable state whatsoever. Property " +
                "SATISFIED, and it is the opening the positional model needs: a deterministic " +
                "replay-local index needs no second durable store.",
        )
    }

    // ==================================================================
    // THE DISCRIMINATOR — DR-6, the property production does NOT satisfy
    // ==================================================================

    @Test
    fun `MEASURED P5 - the same structural position gets a different identity depending on how many arrivals preceded it`() {
        // Two scripts, one loop site, one call site. The arrival of interest is the
        // one at ITERATION 2 in both. Nothing about that iteration differs between
        // them — same loop, same body position, same call site, same effect.
        //
        // The only difference is how many times the call site was reached BEFORE
        // iteration 2. Under DR-6 that must not be able to change the identity.
        val reachedEveryIteration = runRecording {
            for (iteration in 0..2) {
                if (iteration != 1) {
                    scoped(loopScope) { sh("build") }
                }
            }
        }
        val reachedOnlyAtTwo = runRecording {
            for (iteration in 0..2) {
                if (iteration == 2) {
                    scoped(loopScope) { sh("build") }
                }
            }
        }

        // Both scripts execute their effect at iteration 2 and nowhere else.
        assertEquals(2, reachedEveryIteration.size, "script A: iterations 0 and 2")
        assertEquals(1, reachedOnlyAtTwo.size, "script B: iteration 2 only")
        assertEquals(
            scopePathsOf(reachedEveryIteration).distinct().size +
                scopePathsOf(reachedOnlyAtTwo).distinct().size,
            2,
            "MEASURED: both arrivals carry the SAME dynamic scope path — the scope is " +
                "per-loop-site, and the loop site is the same in both scripts.",
        )

        // HERE is the measurement. Script A's iteration-2 arrival is the SECOND
        // arrival, so its ordinal is 1. Script B's iteration-2 arrival is the FIRST,
        // so its ordinal is 0.
        val aSecondArrival = reachedEveryIteration.last()
        val bOnlyArrival = reachedOnlyAtTwo.single()

        assertEquals(1, aSecondArrival.invocationOrdinal)
        assertEquals(0, bOnlyArrival.invocationOrdinal)

        assertNotEquals(
            aSecondArrival.operationId(),
            bOnlyArrival.operationId(),
            "MEASURED P5 — THE FINDING. The durable identity of the effect at iteration 2 " +
                "changed because a DIFFERENT iteration had already reached the same call site. " +
                "The identity is a function of arrival order, not of iteration position.\n\n" +
                "That is precisely what AGENTS.md DR-6 forbids: iteration identity MUST NOT " +
                "depend on how many effects happened inside previous iterations.\n\n" +
                "The counterexample in the S4-R-ID brief is the same shape: " +
                "`for (i in 0..3) { if (i == 2) sh(\"only once\") }` yields `sh ordinal = 0` " +
                "here, whereas positional identity would place it at `loop:<site>[2]`. The two " +
                "descriptions disagree about WHICH iteration ran, and only one of them says so.\n\n" +
                "CONSEQUENCE, and it is the whole point of this spike: ADR-S4-R2 is required. " +
                "Until it exists, a scripted loop's durable identity is arrival-based, so it " +
                "cannot distinguish 'the third iteration' from 'the third time this call site was " +
                "reached'. Nothing here is a correctness bug for a fresh, deterministic run — " +
                "P1 shows identity is distinct per iteration there — but the identity does not " +
                "carry the fact that DR-6 requires it to carry.\n\n" +
                "This row is MEASURED, not a defect report, and it is green on purpose: it pins " +
                "today's behaviour so the I2b slice has a red baseline to move.",
        )
    }

    @Test
    fun `MEASURED P7 - continue does not renumber the iterations that already ran`() {
        // `continue` skips the effect for one iteration. The arrivals that already
        // happened keep their ordinals; nothing is renumbered behind the author's back.
        val operations = runRecording {
            for (iteration in 0..2) {
                if (iteration == 1) {
                    continue
                }
                scoped(loopScope) { sh("build") }
            }
        }

        assertEquals(
            listOf(0, 1),
            ordinalsOf(operations),
            "MEASURED P7: the arrival at iteration 0 keeps ordinal 0, and the arrival at " +
                "iteration 2 gets ordinal 1 because it is the second arrival. Prior ordinals " +
                "are untouched. Note the asymmetry with P5: `continue` and a conditional " +
                "arrival are indistinguishable to this counter, and both compress 'which " +
                "iteration' into 'which arrival'.",
        )
    }

    @Test
    fun `MEASURED P8 - break invents no later iterations`() {
        val operations = runRecording {
            for (iteration in 0..4) {
                if (iteration == 1) {
                    break
                }
                scoped(loopScope) { sh("build") }
            }
        }

        assertEquals(
            listOf(0),
            ordinalsOf(operations),
            "MEASURED P8: breaking at iteration 1 produces exactly one arrival and no phantom " +
                "ordinals for iterations that never ran. Property SATISFIED.",
        )
    }

    // ==================================================================
    // LOOP FORMS — the gap the first run of this spike declared
    // ==================================================================

    @Test
    fun `MEASURED - repeat while and do-while receive NO structural scope at all`() {
        val forms = mapOf(
            "repeat" to """
                repeat(3) {
                    sh("build")
                }
            """.trimIndent(),
            "while" to """
                var i = 0
                while (i < 3) {
                    sh("build")
                    i = i + 1
                }
            """.trimIndent(),
            "do-while" to """
                var i = 0
                do {
                    sh("build")
                    i = i + 1
                } while (i < 3)
            """.trimIndent(),
        )

        forms.forEach { (form, source) ->
            val mapping = KotlinScriptedSourceMapper().map(ScriptedSource(sourceId, source))
            assertTrue(
                mapping is ScriptedSourceMapping.Mapped,
                "the mapper must accept $form, but reported $mapping",
            )
            val scopes = (mapping as ScriptedSourceMapping.Mapped).loopScopes
            assertTrue(
                scopes.isEmpty(),
                "MEASURED — A REAL GAP, not a characterisation. The mapper records a structural " +
                    "loop scope for `for` and for NOTHING ELSE: its only visitor overrides are " +
                    "visitForExpression, visitCallExpression and visitErrorElement. So a " +
                    "`$form` loop body gets no `steps.scoped(...)` at all.\n\n" +
                    "The consequence for identity is stronger than the one measured for `for`: " +
                    "with no scope, every iteration of a `$form` loop shares the ROOT dynamic " +
                    "scope path, so the only thing distinguishing two iterations is the arrival " +
                    "counter — the property DR-6 forbids, with no structural half at all to " +
                    "build on. I2b has to decide what a `$form` loop's identity is; today " +
                    "nothing in the compiler or the runtime has an opinion.\n\n" +
                    "Recorded here rather than asserted as a defect: the mapper is not WRONG " +
                    "about anything it was asked to do, it simply was never extended to these " +
                    "forms. The scope of ADR-S4-R2 therefore has to include them, or it has to " +
                    "say explicitly that they are out of scope.",
            )
        }
    }

    @Test
    fun `MEASURED - a repeat loop falls back to root scope and pure arrival counting`() {
        // The runtime half of the previous row, with the same shape `for` would have had.
        val operations = runRecording {
            repeat(3) {
                sh("build")
            }
        }

        assertEquals(3, operations.size)
        assertEquals(
            listOf(emptyList<String>(), emptyList(), emptyList()),
            operations.map { it.dynamicScopePath },
            "MEASURED: all three iterations of a `repeat` loop report the ROOT dynamic scope, " +
                "empty. There is no loop site in the path at all, not even the per-site constant " +
                "a `for` loop gets.",
        )
        assertEquals(
            listOf(0, 1, 2),
            ordinalsOf(operations),
            "MEASURED: and the only thing separating them is the arrival count. Which is the " +
                "P5 violation with nothing structural to offset it.",
        )
    }

    @Test
    fun `MEASURED - two loops reusing one parameter name get two distinct scope paths`() {
        val source = """
            for (i in listOf("a", "b")) {
                sh("first")
            }
            for (i in listOf("c")) {
                sh("second")
            }
        """.trimIndent()

        val mapping = KotlinScriptedSourceMapper().map(ScriptedSource(sourceId, source))
        assertTrue(
            mapping is ScriptedSourceMapping.Mapped,
            "the mapper must accept this source, but reported $mapping",
        )
        val scopeIds = (mapping as ScriptedSourceMapping.Mapped).loopScopes.map { it.scopeId.value }

        assertEquals(2, scopeIds.size, "two `for` loops, two structural scopes")
        assertNotEquals(
            scopeIds[0],
            scopeIds[1],
            "MEASURED: two loops reusing the SAME parameter name get two distinct scope ids, " +
                "because the id is derived from the loop's position and not from the name. " +
                "Property SATISFIED — and it is the fault S4IdentityLoopScopeTest already pinned " +
                "when the first draft derived the id from the parameter.",
        )
    }

    // ==================================================================
    // RE-EXECUTION WITNESS — the necessary condition, and NOT a restart proof
    // ==================================================================

    @Test
    fun `MEASURED P10 - re-executing the same body reconstructs identical operation ids in identical order`() {
        // A necessary condition for surviving a restart: the identity must be a function of the
        // body alone, not of anything accumulated in the process. The ordinals map is created
        // fresh per run, so a second run re-derives it from the script rather than reading it.
        val body: suspend ScriptedScope.() -> Unit = {
            for (iteration in 0..2) {
                scoped(loopScope) { sh("build") }
            }
        }

        val first = runRecording(body)
        val second = runRecording(body)

        assertEquals(
            first.map { it.operationId() },
            second.map { it.operationId() },
            "MEASURED P10: two independent executions of the same body produce the same " +
                "operation ids in the same order. Determinism of reconstruction HOLDS for the " +
                "arrival model, which is why P1 can be satisfied at all.",
        )
        assertEquals(
            first.map { it.invocationOrdinal },
            second.map { it.invocationOrdinal },
            "and the ordinals themselves are re-derived, not remembered.",
        )
    }
}
