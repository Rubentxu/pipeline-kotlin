package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A pump a test can drive: it waits for the child or works on the plane as the test says, and it
 * finishes when the test says it finishes.
 *
 * There is no thread and no clock here, so the drain's waits are the test's. That is what makes
 * the drain's behaviour observable without a process, a pipe and a real second.
 */
private class FakePump(
    override val name: String = "fake-pump",
) : TranscriptPumpView {
    private var alive = true

    /** How many times the drain waited on this pump. */
    var waits = 0
        private set

    /** The wait on which the pump finishes. `null` means it never finishes. */
    var finishesOnWait: Int? = null

    /** The waits on which the pump is WORKING on the plane instead of waiting for the child. */
    var worksOnWaits: Set<Int> = emptySet()

    override fun isAlive(): Boolean = alive

    override fun isWaitingForInput(): Boolean = waits !in worksOnWaits

    override fun awaitFinish(millis: Long): Long {
        waits++
        if (finishesOnWait != null && waits >= finishesOnWait!!) alive = false
        return millis
    }
}

class TranscriptDrainTest {

    // --- the rule, one partition at a time ------------------------------------------

    @Test
    fun `a finished pump ends the drain`() {
        val decision = decideTranscriptDrain(
            TranscriptDrainState(
                allFinished = true,
                waitingMs = 0,
                idleBudgetMs = 10_000,
                waitedMs = 5,
                ceilingMs = 60_000,
            ),
        )
        assertEquals(TranscriptDrainDecision.DONE, decision)
    }

    @Test
    fun `a pump that stopped moving is stuck, and it is stuck now`() {
        // The idle budget is reached while the drain has barely waited: the child is holding the
        // transcript open, so waiting longer cannot produce the bytes that are missing.
        val decision = decideTranscriptDrain(
            TranscriptDrainState(
                allFinished = false,
                waitingMs = 10_000,
                idleBudgetMs = 10_000,
                waitedMs = 10_000,
                ceilingMs = 60_000,
            ),
        )
        assertEquals(TranscriptDrainDecision.STUCK, decision)
    }

    @Test
    fun `a pump that is still moving when the ceiling arrives is out of time`() {
        // The same pump, still reading, when the ceiling arrives: the transcript is behind, not
        // broken. A rule that only counted time could not tell this from a stuck child, and it
        // refused the slow one.
        val decision = decideTranscriptDrain(
            TranscriptDrainState(
                allFinished = false,
                waitingMs = 0,
                idleBudgetMs = 10_000,
                waitedMs = 60_000,
                ceilingMs = 60_000,
            ),
        )
        assertEquals(TranscriptDrainDecision.CEILING_REACHED, decision)
    }

    @Test
    fun `a pump that is moving and still has time means the drain waits`() {
        val decision = decideTranscriptDrain(
            TranscriptDrainState(
                allFinished = false,
                waitingMs = 250,
                idleBudgetMs = 10_000,
                waitedMs = 250,
                ceilingMs = 60_000,
            ),
        )
        assertEquals(TranscriptDrainDecision.KEEP_WAITING, decision)
    }

    @Test
    fun `a stuck pump is stuck even when the ceiling has not arrived`() {
        // The order of the checks is the rule: a child that is holding the transcript open is
        // refused at once, and not after the drain has spent its whole ceiling waiting for bytes
        // that were never going to come.
        val decision = decideTranscriptDrain(
            TranscriptDrainState(
                allFinished = false,
                waitingMs = 10_000,
                idleBudgetMs = 10_000,
                waitedMs = 10_000,
                ceilingMs = 60_000,
            ),
        )
        assertEquals(TranscriptDrainDecision.STUCK, decision)
    }

    // --- the drain, over pumps the test drives --------------------------------------

    @Test
    fun `a pump that finishes leaves the transcript complete`() {
        val pump = FakePump("stdout").apply { finishesOnWait = 3 }
        val refusal = drainTranscriptPumps(
            pumps = listOf(pump),
            idleBudgetMs = 1_000,
            ceilingMs = 60_000,
            sliceMs = 250,
        )
        assertNull(refusal, "a finished pump means a complete transcript, and no refusal")
    }

    @Test
    fun `a pump that never moves is refused as a stuck child`() {
        val pump = FakePump("stdout")
        val refusal = drainTranscriptPumps(
            pumps = listOf(pump),
            idleBudgetMs = 1_000,
            ceilingMs = 60_000,
            sliceMs = 250,
        )
        val drain = assertInstanceOf(
            ProcessOutputRefusal.DrainIncomplete::class.java,
            refusal,
            "a pump that never moves cannot yield a complete transcript",
        )
        assertEquals(TranscriptDrainCause.STUCK_CHILD, drain.cause)
        assertTrue(
            drain.detail.contains("stdout"),
            "the refusal must name the pump that did not move: <${drain.detail}>",
        )
    }

    @Test
    fun `a pump that keeps moving is waited for, and the refusal says the machine was too slow`() {
        // The case that the old ceiling could not see. The pump reads all along, so the drain has
        // no reason to call it stuck; it runs out of time instead, and says so. A refusal that
        // called this a stuck child would send the operator to look at the child for a problem that
        // is in the machine.
        val pump = FakePump("stdout").apply { worksOnWaits = (1..100).toSet() }
        val refusal = drainTranscriptPumps(
            pumps = listOf(pump),
            idleBudgetMs = 1_000,
            ceilingMs = 2_000,
            sliceMs = 250,
        )
        val drain = assertInstanceOf(
            ProcessOutputRefusal.DrainIncomplete::class.java,
            refusal,
            "the drain cannot wait forever",
        )
        assertEquals(TranscriptDrainCause.OUT_OF_TIME, drain.cause)
        assertTrue(
            drain.waitedMs >= 2_000,
            "the refusal must say how long the drain really waited: <${drain.waitedMs}>",
        )
    }

    @Test
    fun `the drain waits for every pump, and the idle budget counts the whole wait`() {
        // Two pumps: one that finishes and one that never moves. The drain must not report a
        // complete transcript because the first pump finished, and it must not wait for the ceiling
        // either: the second one stopped moving.
        val finished = FakePump("stderr").apply { finishesOnWait = 1 }
        val stuck = FakePump("stdout")
        val refusal = drainTranscriptPumps(
            pumps = listOf(finished, stuck),
            idleBudgetMs = 1_000,
            ceilingMs = 60_000,
            sliceMs = 250,
        )
        val drain = assertInstanceOf(ProcessOutputRefusal.DrainIncomplete::class.java, refusal)
        assertEquals(TranscriptDrainCause.STUCK_CHILD, drain.cause)
        assertTrue(
            drain.detail.contains("stdout"),
            "the refusal must name the pump that did not move: <${drain.detail}>",
        )
    }

    @Test
    fun `a drain with no pumps has nothing to refuse`() {
        assertNull(
            drainTranscriptPumps(
                pumps = emptyList(),
                idleBudgetMs = 1_000,
                ceilingMs = 60_000,
                sliceMs = 250,
            ),
        )
    }

    // --- the verdict, over launches the test owns --------------------------------------

    /**
     * A sink that answers with the refusal the test decides, and remembers being asked.
     */
    private class RefusingSink(
        private val answer: ProcessOutputRefusal?,
    ) : ProcessOutputSink {
        var consulted = false
            private set

        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

        override fun refusal(): ProcessOutputRefusal? {
            consulted = true
            return answer
        }

        override fun close() = Unit
    }

    /**
     * The drain's budgets, small enough that a stuck pump is refused in a few rounds.
     */
    private fun config() = DurableShConfig(
        transcriptDrainIdleBudgetMs = 1_000,
        transcriptDrainCeilingMs = 60_000,
    )

    /**
     * A launch with the state the test gives it.
     *
     * The handle is the CURRENT JVM's: the verdict never looks at the child, and a test must
     * not fork a process to prove a rule about bookkeeping.
     */
    private fun launch(
        pumps: List<TranscriptPumpView>,
        sinks: List<ProcessOutputSink>,
    ) = LaunchedShell(
        handle = ProcessHandle.current(),
        transcriptPumps = pumps,
        transcriptSinks = sinks,
        agentWarmup = emptyList(),
    )

    @Test
    fun `two launches do not exchange their drain objects`() {
        // Two executions, two launches. The first is healthy: its pump finishes and its sink has
        // nothing to refuse. The second is not: its pump never finishes.
        //
        // If the runtime kept the drain state somewhere both executions could reach it, the first
        // verdict would inherit the second's stuck pump, and the first execution would be reported
        // on a transcript that is not its own — the "short, ordered, plausible" transcript that
        // this codebase refuses to accept anywhere else.
        val healthyPump = FakePump("healthy-pump").apply { finishesOnWait = 1 }
        val stuckPump = FakePump("stuck-pump")

        val healthy = launch(listOf(healthyPump), emptyList())
        val failing = launch(listOf(stuckPump), emptyList())

        assertNull(
            transcriptVerdict(healthy, config()),
            "a healthy launch must not inherit the other launch's stuck pump",
        )
        assertEquals(1, healthyPump.waits, "the launch's own pump was drained")
        assertEquals(0, stuckPump.waits, "the other launch's pump was never touched")

        val refusal = transcriptVerdict(failing, config())
        val drain = assertInstanceOf(
            ProcessOutputRefusal.DrainIncomplete::class.java,
            refusal,
            "the launch whose pump never finishes is refused as a stuck child",
        )
        assertEquals(TranscriptDrainCause.STUCK_CHILD, drain.cause)
        assertTrue(
            drain.detail.contains("stuck-pump"),
            "the refusal must name ITS OWN pump: <${drain.detail}>",
        )
        assertTrue(
            !drain.detail.contains("healthy-pump"),
            "the refusal must not name the other launch's pump: <${drain.detail}>",
        )
    }

    @Test
    fun `a refusal belongs to the launch that produced it`() {
        // The same isolation for the sinks: a launch with no pumps at all, so the verdict can
        // only come from its own sink.
        val healthySink = RefusingSink(null)
        val refusingSink = RefusingSink(
            ProcessOutputRefusal.WriteFailed(
                detail = "the plane refused the bytes",
                cause = IllegalStateException("the store said no"),
            ),
        )

        val healthy = launch(emptyList(), listOf(healthySink))
        val failing = launch(emptyList(), listOf(refusingSink))

        assertNull(
            transcriptVerdict(healthy, config()),
            "a launch with nothing to refuse must not inherit the other launch's refusal",
        )
        assertTrue(healthySink.consulted, "the launch's own sink was consulted")
        assertTrue(!refusingSink.consulted, "the other launch's sink was never consulted")

        val refusal = transcriptVerdict(failing, config())
        val write = assertInstanceOf(
            ProcessOutputRefusal.WriteFailed::class.java,
            refusal,
            "the launch whose sink refused is refused with what the sink said",
        )
        assertEquals("the plane refused the bytes", write.detail)
        assertTrue(refusingSink.consulted, "the refusing launch's own sink was consulted")
    }
}
