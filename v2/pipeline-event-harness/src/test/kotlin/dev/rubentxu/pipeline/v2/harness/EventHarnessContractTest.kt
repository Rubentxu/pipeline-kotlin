package dev.rubentxu.pipeline.v2.harness

import dev.rubentxu.pipeline.v2.events.*
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector
import dev.rubentxu.pipeline.v2.harness.codec.YamlEventContractCodec
import dev.rubentxu.pipeline.v2.harness.model.*
import dev.rubentxu.pipeline.v2.harness.verify.EventHarness
import dev.rubentxu.pipeline.v2.harness.verify.TypedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * HF0 pure-contract tests: ADT laws, selector matching, partial order,
 * YAML decode fail-closed, deterministic counterexample slicing.
 * Histories are synthetic (no processes needed).
 */
class EventHarnessContractTest {

    private var seq = 0L
    private fun next() = ++seq
    private fun ts() = Instant.parse("2026-01-01T00:00:00Z")
    private fun runId() = "r1"

    private fun ev(e: DomainEvent): TypedEvent = TypedEvent(EnvelopeProjector.project(e), e)
    private fun List<TypedEvent>.sorted() = sortedBy { it.sequence }
    /** Pure mutation: re-stamp envelope sequence to simulate reordered reality. */
    private fun resequenced(history: List<TypedEvent>): List<TypedEvent> =
        history.mapIndexed { i, t ->
            TypedEvent(t.envelope.copy(sequence = (i + 1).toLong()), t.event)
        }

    // -- minimal builders ----------------------------------------------------

    private fun runStarted() = RunStarted("e${next()}", runId(), next(), ts(), "p.kts")
    private fun runFinished(o: String) = RunFinished("e${next()}", runId(), next(), ts(), o, emptyList())
    private fun stageStarted(i: Int) = StageStarted("e${next()}", runId(), next(), ts(), i, "s$i")
    private fun stageFinished(i: Int, o: String = "success") = StageFinished("e${next()}", runId(), next(), ts(), i, "s$i", o)
    private fun branchStarted(stage: Int, b: Int, name: String = "b$b") =
        ParallelBranchStarted("e${next()}", runId(), next(), ts(), b, name, stage)
    private fun branchFinished(stage: Int, b: Int, o: String = "success") =
        ParallelBranchFinished("e${next()}", runId(), next(), ts(), b, name(b), stage, o)
    private fun name(b: Int) = "b$b"
    private fun stepStarted(stage: Int, step: Int) = StepStarted("e${next()}", runId(), next(), ts(), stage, step, "s", "sh")
    private fun stepFinished(stage: Int, step: Int, o: String = "success") =
        StepFinished("e${next()}", runId(), next(), ts(), stage, step, "s", "sh")
    private fun retryStarted(a: Int, stage: Int, step: Int) =
        RetryAttemptStarted("e${next()}", runId(), next(), ts(), a, 3, "s", "sh", stage, step)
    private fun retryFinished(a: Int, o: String, stage: Int, step: Int) =
        RetryAttemptFinished("e${next()}", runId(), next(), ts(), a, 3, "s", "sh", stage, step, o)
    private fun stepFailed(step: Int, msg: String) =
        StepFailed("e${next()}", runId(), next(), ts(), step, "s", "sh", dev.rubentxu.pipeline.v2.domain.FailureKind.SCRIPT, msg)
    private fun catchError(result: String) =
        CatchErrorTriggered("e${next()}", runId(), next(), ts(), "stage", result, "FAILURE", null)
    private fun echo(content: String) = EchoOutputCaptured("e${next()}", runId(), next(), ts(), 0, content)
    private fun timeoutScheduled(stage: Int = 0, step: Int = 0) =
        TimeoutScheduled("e${next()}", runId(), next(), ts(), 1, "FAILURE", "s", "sh", stage, step)

    // -- selector matching ---------------------------------------------------

    @Test
    fun `selector matches typed payload fields`() {
        val h = listOf(runStarted(), catchError("FAILURE"), catchError("UNSTABLE"), runFinished("unstable")).map { ev(it) }.sorted()
        val sel = EventSelector("CatchErrorTriggered", listOf(FieldMatch.CatchBuildResult("FAILURE")))
        val found = h.filter { it.kind == sel.kind && dev.rubentxu.pipeline.v2.harness.verify.EventPayloadAccessor.matches(it, sel.where) }
        assertEquals(1, found.size)
    }

    @Test
    fun `exactly constraint satisfied and violated`() {
        val base = listOf(runStarted(), catchError("FAILURE"), catchError("UNSTABLE"), runFinished("unstable")).map { ev(it) }.sorted()
        val c = EventContract(1, "t", ExpectedRunOutcome.UNSTABLE, listOf(
            EventConstraint.Exactly(EventSelector("CatchErrorTriggered", listOf(FieldMatch.CatchBuildResult("FAILURE"))), 1),
            EventConstraint.Exactly(EventSelector("CatchErrorTriggered", listOf(FieldMatch.CatchBuildResult("UNSTABLE"))), 1),
            EventConstraint.Before(
                EventSelector("CatchErrorTriggered", listOf(FieldMatch.CatchBuildResult("FAILURE"))),
                EventSelector("CatchErrorTriggered", listOf(FieldMatch.CatchBuildResult("UNSTABLE"))),
                RelationScope.Global),
        ))
        assertEquals(VerificationResult.Valid, EventHarness.verify(base, c))
        // mutation: swap the FAILURE/UNSTABLE reality order (re-stamped sequences)
        val swapped = resequenced(listOf(base[0], base[2], base[1], base[3]))
        val r = EventHarness.verify(swapped, c)
        assertTrue(r is VerificationResult.Invalid)
    }

    @Test
    fun `partial order - both branch interleavings valid, missing branch start invalid`() {
        val c = EventContract(1, "p", ExpectedRunOutcome.SUCCESS, listOf(
            EventConstraint.Before(
                EventSelector("ParallelBranchStarted"),
                EventSelector("ParallelBranchFinished"),
                RelationScope.SameKey(KeyKind.BRANCH)),
        ))
        val s = runStarted(); val st = stageStarted(0)
        val a1 = branchStarted(0, 0); val b1 = branchStarted(0, 1)
        val b2 = branchFinished(0, 1); val a2 = branchFinished(0, 0)
        val sf = stageFinished(0); val f = runFinished("success")
        // interleaving 1: A,B,B,A
        val h1 = listOf(s, st, a1, b1, b2, a2, sf, f).map { ev(it) }.sorted()
        assertEquals(VerificationResult.Valid, EventHarness.verify(h1, c))
        // interleaving 2: A,B,A,B — fresh events created in intended chronological order
        val s3 = runStarted(); val st3 = stageStarted(0)
        val a3 = branchStarted(0, 0); val b3 = branchStarted(0, 1)
        val af = branchFinished(0, 0); val bf = branchFinished(0, 1)
        val sf3 = stageFinished(0); val f3 = runFinished("success")
        val h3 = listOf(ev(s3), ev(st3), ev(a3), ev(b3), ev(af), ev(bf), ev(sf3), ev(f3)).sorted()
        assertEquals(VerificationResult.Valid, EventHarness.verify(h3, c))
        // missing start for branch 1
        val bad = listOf(ev(s), ev(st), ev(a1), ev(branchFinished(0, 1)), ev(sf), ev(f)).sorted()
        val r = EventHarness.verify(bad, c)
        assertTrue(r is VerificationResult.Invalid && r.violations.any { it.rule == ViolationRule.LAW_FINISHED_WITHOUT_STARTED })
    }

    @Test
    fun `parent stage completion before child branch terminal is rejected`() {
        val s = runStarted(); val st = stageStarted(0)
        val a1 = branchStarted(0, 0)
        val sf = stageFinished(0)
        val a2 = branchFinished(0, 0)
        val f = runFinished("success")
        val h = listOf(ev(s), ev(st), ev(a1), ev(sf), ev(a2), ev(f)).sorted()
        val r = EventHarness.verify(h, EventContract(1, "x", ExpectedRunOutcome.SUCCESS, emptyList()))
        assertTrue(r is VerificationResult.Invalid && r.violations.any { it.rule == ViolationRule.LAW_PARENT_COMPLETED_BEFORE_CHILD })
    }

    @Test
    fun `contradictory attempt terminals rejected - but StepFailed closure is legitimate`() {
        val s = runStarted(); val ss = stepStarted(0, 0)
        val sf = stepFailed(0, "boom"); val sfin = stepFinished(0, 0)
        val f = runFinished("success")
        // characterized on trunk: StepFailed then StepFinished is legitimate closure
        val h = listOf(ev(s), ev(ss), ev(sf), ev(sfin), ev(f)).sorted()
        assertEquals(VerificationResult.Valid, EventHarness.verify(h, EventContract(1, "x", ExpectedRunOutcome.SUCCESS, emptyList())))
        // same attempt key with divergent terminal outcomes IS a contradiction
        val rf1 = retryFinished(1, "failed", 0, 0)
        val rf2 = retryFinished(1, "succeeded", 0, 0)
        val h2 = listOf(ev(s), ev(ss), ev(sf), ev(sfin), ev(rf1), ev(rf2), ev(f)).sorted()
        val r = EventHarness.verify(h2, EventContract(1, "x", ExpectedRunOutcome.SUCCESS, emptyList()))
        assertTrue(r is VerificationResult.Invalid && r.violations.any { it.rule == ViolationRule.LAW_CONTRADICTORY_TERMINAL })
    }

    @Test
    fun `terminal outcome law - absence of RunFinished is never complete`() {
        val h = listOf(ev(runStarted())).sorted()
        val c = EventContract(1, "t", ExpectedRunOutcome.SUCCESS, listOf(EventConstraint.TerminalOutcome(ExpectedRunOutcome.SUCCESS)))
        val r = EventHarness.verify(h, c)
        assertTrue(r is VerificationResult.Invalid)
        assertEquals(null, EventHarness.observedTerminal(h))
    }

    // -- YAML codec ----------------------------------------------------------

    @Test
    fun `yaml v1 decode round and fail-closed`() {
        val yaml = """
            version: 1
            name: t
            expect:
              runOutcome: UNSTABLE
            constraints:
              - exactly: { event: CatchErrorTriggered, where: { buildResult: FAILURE }, count: 1 }
              - before:
                  first: { event: CatchErrorTriggered, where: { buildResult: FAILURE } }
                  second: { event: CatchErrorTriggered, where: { buildResult: UNSTABLE } }
        """.trimIndent()
        val c = YamlEventContractCodec.decode(yaml)
        assertEquals(1, c.version)
        assertEquals(ExpectedRunOutcome.UNSTABLE, c.expect)
        assertEquals(2, c.constraints.size)

        assertThrows<YamlEventContractCodec.ContractDecodeException> {
            YamlEventContractCodec.decode(yaml.replace("version: 1", "version: 2"))
        }
        assertThrows<YamlEventContractCodec.ContractDecodeException> {
            YamlEventContractCodec.decode(yaml.replace("- exactly:", "- magic:").replace("{ event: CatchErrorTriggered, where: { buildResult: FAILURE }, count: 1 }", "{ event: X }"))
        }
        assertThrows<YamlEventContractCodec.ContractDecodeException> {
            YamlEventContractCodec.decode(yaml.replace("buildResult: FAILURE", "hacker: 1"))
        }
    }

    // -- counterexample determinism + bounded window -------------------------

    @Test
    fun `violation carries bounded counterexample not full dump`() {
        val big = mutableListOf(ev(runStarted()))
        repeat(100) { big += ev(stepStarted(0, it)) }
        val r = EventHarness.verify(big.sorted(), EventContract(1, "b", ExpectedRunOutcome.SUCCESS,
            listOf(EventConstraint.Exactly(EventSelector("RunFinished"), 1))))
        assertTrue(r is VerificationResult.Invalid)
        val v = (r as VerificationResult.Invalid).violations.single()
        assertTrue(v.relevantTrace.size <= 8)
        // determinism
        val r2 = EventHarness.verify(big.sorted(), EventContract(1, "b", ExpectedRunOutcome.SUCCESS,
            listOf(EventConstraint.Exactly(EventSelector("RunFinished"), 1))))
        assertEquals(r, r2)
    }

    // -- acceptance separation ------------------------------------------------

    @Test
    fun `acceptance is independent of pipeline outcome mismatch handling`() {
        // run reports SUCCESS; contract demands UNSTABLE terminal: acceptance FAILED, outcome untouched
        val h = listOf(ev(runStarted()), ev(runFinished("success"))).sorted()
        val c = EventContract(1, "t", ExpectedRunOutcome.UNSTABLE, listOf(EventConstraint.TerminalOutcome(ExpectedRunOutcome.UNSTABLE)))
        assertEquals(AcceptanceOutcome.FAILED, EventHarness.accept(h, c))
        assertEquals(PipelineOutcome.SUCCESS, EventHarness.observedTerminal(h))
    }
}
