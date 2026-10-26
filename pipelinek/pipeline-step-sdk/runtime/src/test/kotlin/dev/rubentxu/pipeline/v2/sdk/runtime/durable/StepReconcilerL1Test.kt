package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * A resumed `sh` step fails closed.
 *
 * **The guarantee.** When a later process asks what became of a durable `sh` step whose worker
 * died, the answer is never "it succeeded" unless a published exit code says so. Every other
 * state — no result, a result that cannot be read, a result that is not an exit code, a stale
 * heartbeat, nothing at all — is `Lost`, and `Lost` means "nobody knows", which is not success.
 *
 * **The observable capacity it serves.** A durable run that is resumed after its worker died
 * re-attaches to the operations it left behind, and each one has to end in an honest terminal.
 * The end-to-end path for that exists elsewhere; what is checked HERE is the decision that the
 * path rests on and that no end-to-end scenario can localise: given what a control directory
 * holds, which classification follows.
 *
 * **Why this instrument and not another.** The decision reads a control directory on disk, and a
 * unit test may not create one — a test that made a real control directory and looked at what
 * survived it would be testing the filesystem, not the rule. So the reading and the decision are
 * separated: the decision is a pure function of the probe's answers, and the probe here is an
 * in-memory map. That is also what makes the ORDER of the checks observable, which a test that
 * only asserted an outcome would miss.
 *
 * **Why the order is pinned.** The three facts are not interchangeable. A `timeout.flag` is
 * written before the kill, so its presence names a timeout even if the heartbeat is stale for an
 * unrelated reason. A published exit code is the strongest fact available. The heartbeat is the
 * weakest: it says the child may still be alive. Consulting them in that order is the protocol,
 * and the last one is consulted only when the earlier ones are absent — a directory that already
 * holds its answer must not be made to fail by a transcript that cannot be timestamped.
 */
class StepReconcilerL1Test {

    private val controlDir: Path = Path.of("/tmp/pipelinek-step-reconciler/op-42")

    private val protocol = ShellProtocolLayout(controlDir)

    /** The threshold the heartbeat rule compares against: interval plus minimum delta. */
    private val thresholdMillis = 300_000L + 2_000L

    private val config = DurableShConfig(heartbeatCheckInterval = 300L, heartbeatMinimumDelta = 2L)

    private fun classify(probe: ControlDirProbe): StepReconcilerL1.Classification =
        classifyControlDirFacts(
            controlDir = controlDir,
            logPath = protocol.consoleLog,
            resultFile = protocol.resultFile,
            timeoutFlag = protocol.timeoutFlag,
            probe = probe,
            config = config,
        )

    @Test
    fun `a published exit code is the completion`() {
        val probe = FakeProbe(nowMillis = 1_000_000L, contents = mapOf(protocol.resultFile to "0"))

        assertEquals(StepReconcilerL1.Classification.Complete(0), classify(probe))
    }

    @Test
    fun `the exit code is read as a number, whatever the line ending`() {
        // The wrapper publishes it with `echo`, so the file ends in a newline. The newline is not
        // part of the exit code, and a protocol that broke here would report every finished step
        // as unknown.
        val probe = FakeProbe(nowMillis = 1_000_000L, contents = mapOf(protocol.resultFile to "42\n"))

        assertEquals(StepReconcilerL1.Classification.Complete(42), classify(probe))
    }

    @Test
    fun `a result that is not an exit code does not become a success`() {
        // The task protocol publishes `EXIT 0` / `TIMED_OUT` / `CANCELLED` in a file with the
        // SAME name, and on the non-Linux route it even shares the per-operation directory.
        // Reading one grammar with the other reader is exactly the fabrication the rule forbids.
        val probe = FakeProbe(
            nowMillis = 1_000_000L,
            contents = mapOf(protocol.resultFile to "EXIT 0"),
        )

        assertEquals(StepReconcilerL1.Classification.Lost, classify(probe))
    }

    @Test
    fun `a result that cannot be read is not a success`() {
        // Present but unreadable is not absent. Both are `Lost`, but they are different facts and
        // the decision must treat an unreadable answer as no answer at all.
        val probe = FakeProbe(
            nowMillis = 1_000_000L,
            unreadable = setOf(protocol.resultFile),
        )

        assertEquals(StepReconcilerL1.Classification.Lost, classify(probe))
    }

    @Test
    fun `the timeout marker wins over a published exit code`() {
        // The watchdog writes the marker BEFORE it kills. A directory that carries both says the
        // deadline fired; the exit code in it is the exit code of a killed child, not a verdict.
        val probe = FakeProbe(
            nowMillis = 1_000_000L,
            contents = mapOf(
                protocol.resultFile to "0",
                protocol.timeoutFlag to "",
            ),
        )

        val classification = classify(probe)

        assertEquals(
            StepReconcilerL1.Classification.TimedOut(controlDir, protocol.consoleLog),
            classification,
        )
    }

    @Test
    fun `the timeout marker alone is a timeout`() {
        val probe = FakeProbe(nowMillis = 1_000_000L, contents = mapOf(protocol.timeoutFlag to ""))

        assertEquals(
            StepReconcilerL1.Classification.TimedOut(controlDir, protocol.consoleLog),
            classify(probe),
        )
    }

    @Test
    fun `a fresh heartbeat without a result means the child may still be running`() {
        val probe = FakeProbe(
            nowMillis = 1_000_000L,
            contents = mapOf(protocol.consoleLog to "still going"),
            lastModified = mapOf(protocol.consoleLog to 1_000_000L - thresholdMillis),
        )

        assertEquals(StepReconcilerL1.Classification.Reattach(controlDir), classify(probe))
    }

    @Test
    fun `a stale heartbeat means nobody knows`() {
        val probe = FakeProbe(
            nowMillis = 1_000_000L,
            contents = mapOf(protocol.consoleLog to "silence since long ago"),
            lastModified = mapOf(protocol.consoleLog to 1_000_000L - thresholdMillis - 1L),
        )

        assertEquals(StepReconcilerL1.Classification.Lost, classify(probe))
    }

    @Test
    fun `a control directory with nothing in it is lost`() {
        // Not an error case: this is the ordinary shape of a step whose worker died before the
        // child wrote anything. It must not read as "nothing happened, therefore fine".
        val probe = FakeProbe(nowMillis = 1_000_000L)

        assertEquals(StepReconcilerL1.Classification.Lost, classify(probe))
    }

    @Test
    fun `the heartbeat is not consulted once the directory holds its answer`() {
        // The transcript's timestamp is the weakest fact and the last one to be read. A directory
        // that already published its exit code must not depend on a transcript that cannot be
        // timestamped — otherwise a finished step is lost because an unrelated file misbehaved.
        val probe = FakeProbe(
            nowMillis = 1_000_000L,
            contents = mapOf(protocol.resultFile to "0"),
            timestampReadsThrow = true,
        )

        assertEquals(StepReconcilerL1.Classification.Complete(0), classify(probe))
        assertFalse(probe.timestampRead, "the transcript's timestamp was read even though a result was published")
    }

    @Test
    fun `the freshness boundary is inclusive`() {
        // The wrapper touches the transcript ON the interval, so a transcript touched exactly at
        // the threshold is evidence that the child is alive, not evidence that it is gone.
        assertTrue(heartbeatIsFresh(nowMillis = 10_000L, lastModifiedMillis = 10_000L - thresholdMillis, config = config))
        assertFalse(heartbeatIsFresh(nowMillis = 10_000L, lastModifiedMillis = 10_000L - thresholdMillis - 1L, config = config))
    }

    @Test
    fun `the freshness threshold is the interval plus the minimum delta`() {
        // Both halves of the rule are configuration, and a rule that silently dropped one of them
        // would still look right for a directory touched moments ago.
        assertEquals(thresholdMillis, config.heartbeatCheckInterval * 1000 + config.heartbeatMinimumDelta * 1000)
    }

    /**
     * A control directory held in memory.
     *
     * It answers the four questions the reconciler asks and nothing else. It records whether the
     * transcript's timestamp was read, because that is how the ORDER of the checks becomes
     * observable, and it can refuse to answer the timestamp at all, which is how the guarantee
     * that a published answer does not depend on the transcript is pinned.
     */
    private class FakeProbe(
        private val nowMillis: Long,
        private val contents: Map<Path, String> = emptyMap(),
        private val unreadable: Set<Path> = emptySet(),
        private val lastModified: Map<Path, Long> = emptyMap(),
        private val timestampReadsThrow: Boolean = false,
    ) : ControlDirProbe {

        /** Whether the transcript's timestamp was consulted. */
        var timestampRead: Boolean = false
            private set

        override fun exists(path: Path): Boolean = contents.containsKey(path) || unreadable.contains(path)

        override fun readText(path: Path): String? = if (unreadable.contains(path)) null else contents[path]

        override fun lastModifiedMillis(path: Path): Long {
            timestampRead = true
            if (timestampReadsThrow) error("the transcript cannot be timestamped")
            return lastModified[path] ?: 0L
        }

        override fun nowMillis(): Long = nowMillis
    }
}
