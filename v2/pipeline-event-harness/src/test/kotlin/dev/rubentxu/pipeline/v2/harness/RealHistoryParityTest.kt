package dev.rubentxu.pipeline.v2.harness

import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.harness.codec.YamlEventContractCodec
import dev.rubentxu.pipeline.v2.harness.model.*
import dev.rubentxu.pipeline.v2.harness.verify.EventHarness
import dev.rubentxu.pipeline.v2.harness.verify.TypedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Real-history differential parity + anti-false-green mutations.
 * Fixtures are REAL histories captured from the installed binary (examples 07-10).
 * Legacy P4-EX assertions are re-encoded as typed contracts; parity =
 * legacyVerdict == harnessVerdict for the same real execution.
 */
class RealHistoryParityTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    private fun history(name: String): List<TypedEvent> =
        EventHarness.typedHistory(JsonEventLog.decode(fixture(name)))

    // ---- 07 ----------------------------------------------------------------

    @Test
    fun `07 - legacy verdict == harness verdict (real history)`() {
        val h = history("07-catch-error.out.json")
        val c = YamlEventContractCodec.decode(fixtureText("07-catch-error.events.yaml"))
        assertEquals(VerificationResult.Valid, EventHarness.verify(h, c))
        assertEquals(PipelineOutcome.UNSTABLE, EventHarness.observedTerminal(h))
        assertEquals(AcceptanceOutcome.PASSED, EventHarness.accept(h, c))
    }

    // ---- 08 ----------------------------------------------------------------

    /**
     * 08 reuse law via AFTER_LAST_RUN_STARTED scope (never timestamps).
     * BASELINE DEBT INC-EVT3-1: durable rerun re-appends a lifecycle skeleton with
     * RESTARTED sequences (characterized on trunk; append semantics untouched).
     * Store read order is (sequence, rowid); the harness treats the last execution
     * segment (insertion-order suffix from the LAST RunStarted) as the reuse window.
     */
    @Test
    fun `08 - reuse segment has no fabricated branch or step starts (real history, last-segment scope)`() {
        val r2 = history("08-parallel.run2.out.json")
        val c = YamlEventContractCodec.decode(fixtureText("08-parallel.events.yaml"))
        // the whole combined history is NOT a valid protocol trace (dup sequences,
        // interleaved segments) - that is INC-EVT3-1 debt, not a harness verdict:
        // the reuse law is about the LAST execution segment.
        val suffix = EventHarness.lastSegment(r2)
        assertEquals(0, suffix.count { it.kind == "ParallelBranchStarted" })
        assertEquals(0, suffix.count { it.kind == "StepStarted" })
        assertEquals(VerificationResult.Valid, EventHarness.verify(suffix, c, EventHarness.Scope.WHOLE))
        assertEquals(PipelineOutcome.SUCCESS, EventHarness.observedTerminal(suffix))
    }

    // ---- 09 ----------------------------------------------------------------

    @Test
    fun `09 - retry failed-then-succeeded exactly once each (real history)`() {
        val h = history("09-retry.run2.out.json")
        val c = YamlEventContractCodec.decode(fixtureText("09-retry.events.yaml"))
        assertEquals(VerificationResult.Valid, EventHarness.verify(h, c))
        assertEquals(PipelineOutcome.SUCCESS, EventHarness.observedTerminal(h))
    }

    // ---- 10 ----------------------------------------------------------------

    @Test
    fun `10 - timeout scheduled before timed-out step failure (real history)`() {
        val h = history("10-timeout.out.json")
        val c = YamlEventContractCodec.decode(fixtureText("10-timeout.events.yaml"))
        assertEquals(VerificationResult.Valid, EventHarness.verify(h, c))
        assertEquals(PipelineOutcome.FAILURE, EventHarness.observedTerminal(h))
    }

    // ---- mutation families (pure transformations of REAL histories) --------

    @Test
    fun `mutation M1 - remove required Started yields Invalid`() {
        val h = history("09-retry.run2.out.json").toMutableList()
        h.removeAll { it.kind == "RetryAttemptStarted" }
        val r = EventHarness.verify(h, contract09())
        assertTrue(r is VerificationResult.Invalid && r.violations.any { it.rule == ViolationRule.LAW_FINISHED_WITHOUT_STARTED })
    }

    @Test
    fun `mutation M2 - duplicate terminal event yields Invalid`() {
        val h = history("09-retry.run2.out.json").toMutableList()
        val ok = h.last { it.kind == "RetryAttemptFinished" }
        h.add(TypedEvent(ok.envelope.copy(eventRef = ok.envelope.eventRef, sequence = ok.sequence + 1000), ok.event))
        val r = EventHarness.verify(h.sortedBy { it.sequence }, contract09())
        assertTrue(r is VerificationResult.Invalid)
    }

    @Test
    fun `mutation M3 - invert parent-child ordering yields Invalid`() {
        // real 08 history (first execution segment only - INC-EVT3-1 duplicates excluded):
        // re-stamp a ParallelBranchFinished AFTER StageFinished => parent completed before child
        val r2 = history("08-parallel.run2.out.json").take(27)
        val sf = r2.last { it.kind == "StageFinished" }
        val bf = r2.last { it.kind == "ParallelBranchFinished" }
        assertTrue(bf.sequence < sf.sequence)
        val swapped = r2.map {
            if (it.sequence == bf.sequence) TypedEvent(it.envelope.copy(sequence = sf.sequence + 5000), it.event)
            else it
        }.sortedBy { it.sequence }
        val r = EventHarness.verify(swapped, EventContract(1, "m3", ExpectedRunOutcome.SUCCESS, emptyList()))
        assertTrue(r is VerificationResult.Invalid && r.violations.any { it.rule == ViolationRule.LAW_PARENT_COMPLETED_BEFORE_CHILD })
    }

    @Test
    fun `mutation M4 - corrupt ResourceRef relation yields Invalid counterexample with subject`() {
        // For the 07 Before(FAILURE, UNSTABLE) contract, corrupt the UNSTABLE envelope subject
        val h = history("07-catch-error.out.json").toMutableList()
        val unstable = h.indexOfFirst {
            it.kind == "CatchErrorTriggered" &&
                (it.event as? dev.rubentxu.pipeline.v2.events.CatchErrorTriggered)?.buildResult == "UNSTABLE"
        }
        assertTrue(unstable >= 0)
        val e = h[unstable]
        h[unstable] = TypedEvent(
            e.envelope.copy(subject = dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs.run("corrupted-run")),
            e.event,
        )
        val c = YamlEventContractCodec.decode(fixtureText("07-catch-error.events.yaml"))
        val r = EventHarness.verify(h, c)
        // Global scope still passes ordering; use SameSubject contract variant to detect corruption
        val sameSubject = c.copy(constraints = listOf(
            EventConstraint.Before(
                EventSelector("CatchErrorTriggered", listOf(FieldMatch.CatchBuildResult("FAILURE"))),
                EventSelector("CatchErrorTriggered", listOf(FieldMatch.CatchBuildResult("UNSTABLE"))),
                RelationScope.SameSubject),
        ))
        val r2 = EventHarness.verify(h, sameSubject)
        assertTrue(r2 is VerificationResult.Invalid)
        assertTrue((r2 as VerificationResult.Invalid).violations.first().relevantTrace.isNotEmpty())
    }

    @Test
    fun `mutation M5 - fabricated StepStarted in replay-only trace yields Invalid`() {
        val r2 = history("08-parallel.run2.out.json")
        val cut = Regex("cutSequence.:.\\s*(\\d+)").find(fixture("08-parallel.cut.json"))!!.groupValues[1].toLong()
        val sub = r2.filter { it.sequence > cut }.toMutableList()
        val fabricated = StepStarted("fabricated", r2.first().event.runId, cut + 1, java.time.Instant.EPOCH, 0, 0, "s", "sh")
        sub += TypedEvent(dev.rubentxu.pipeline.v2.events.identity.EnvelopeProjector.project(fabricated), fabricated)
        val c = YamlEventContractCodec.decode(fixtureText("08-parallel.events.yaml"))
        val r = EventHarness.verify(sub.sortedBy { it.sequence }, c)
        assertTrue(r is VerificationResult.Invalid && r.violations.any { it.rule == ViolationRule.NEVER_VIOLATED })
    }

    @Test
    fun `historical - verify same history twice identical, and after restart-shaped reload identical`() {
        val payload = fixture("09-retry.run2.out.json")
        val h1 = EventHarness.typedHistory(JsonEventLog.decode(payload))
        val h2 = EventHarness.typedHistory(JsonEventLog.decode(JsonEventLog.decode(payload).let { JsonEventLog.encode(it) }))
        val c = contract09()
        assertEquals(EventHarness.verify(h1, c), EventHarness.verify(h2, c))
        assertEquals(EventHarness.verify(h1, c), EventHarness.verify(h1, c))
    }

    // -- helpers --------------------------------------------------------------

    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    private fun contract09(): EventContract =
        YamlEventContractCodec.decode(fixtureText("09-retry.events.yaml"))

    private fun contract07(): EventContract =
        YamlEventContractCodec.decode(fixtureText("07-catch-error.events.yaml"))
}
