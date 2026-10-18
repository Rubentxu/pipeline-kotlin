package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.EventRecordReadPortStoreAdapter
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `inspect` must not answer "this run does not exist" with an event store that
 * failed to answer.
 *
 * ## The defect this pins
 *
 * The adapter asked the event plane whether a run exists and collapsed the answer
 * into a `Boolean`:
 *
 * ```kotlin
 * private fun evStoreHasRun(runId: String): Boolean =
 *     eventReads.readRecords(..., limit = 1) !is EventRecordReadResult.Refused
 * ```
 *
 * `EventRecordReadRefusal` has three cases. One of them — `UnknownRun` — really is
 * an answer about the run, and folding it into `false` is correct. `StorageError` is
 * not: it is the store saying *I could not look*. The collapsed version reported a
 * transient failure as a confidently-worded positive claim ("no event envelopes")
 * about a run that exists. A caller reading that cannot tell "there is nothing here"
 * from "I failed to look", and the two demand opposite responses.
 *
 * The second defect sat in the same file, 150 lines earlier, and pointed the other
 * way: the `!runKnown` branch mapped *every* refusal to `IntrospectionRefusal.UnknownRun`,
 * so a storage failure was reported as an unknown run. That direction is fail-CLOSED
 * and therefore less dangerous — but it is still a wrong answer, and it meant the
 * same `readRecords(limit = 1)` call meant two different things within one file. Both
 * sites now map through one `asIntrospectionRefusal`.
 *
 * ## The production entry point this crosses
 *
 * ```text
 * RuntimeIntrospectionPortStoreAdapter.inspect(runId)
 *   -> lease.isKnown(runId)         REAL FileBackedRunExecutionLeaseStore, real record file
 *   -> lease.observe(runId)         REAL, same file
 *   -> journal.listForRun(runId)    REAL InMemoryOperationJournal, empty
 *   -> eventReads.readRecords(...)  the PORT under test
 * ```
 *
 * **HF1 — in-process, and the substitute is at the PORT boundary, not inside it.**
 * The journal, cursors, lease store and its durable record file are all real. Only
 * `EventRecordReadPort` is substituted, because the store adapter cannot produce a
 * `StorageError` on demand without corrupting a real store — and corrupting storage to
 * force a refusal would test the corruption, not the mapping. The seam under test IS
 * the port: the defect is in how the adapter CONSUMES a refusal, and a refusal arriving
 * from the port is the production shape of that input.
 *
 * ## Reaching the decision at all
 *
 * `evStorePresenceOf` is only consulted when the run is known through a durable lease
 * record that `observe` cannot read. `isKnown` is `Files.isRegularFile(record)` and
 * `observe` goes through `readRecord`, so the state that separates them is a record
 * file that EXISTS but does not parse — a torn write. [givenATornLeaseRecord]
 * produces exactly that by writing a record file with no `run_id` line, which is what a
 * crash between create and append leaves behind. `PRES_0` is the control that proves
 * this fixture really reaches the decision: with a store that answers `UnknownRun` the
 * run comes back `LiveButEmpty`, and `PRES_1` differs from it by the refusal alone.
 *
 * ## The mutations that must kill this
 *
 * Measured 2026-10-18 on this class; both numbers are from the XML, not predicted:
 *
 * ```text
 * M-1  EventStorePresence.Unreadable(mapped)  ->  EventStorePresence.Absent
 *      flips PRES-1 ONLY (failures=1 of 4). PRES-0, PRES-2, PRES-3 stay green.
 *
 * M-2  the !runKnown branch's asIntrospectionRefusal(evRead.refusal, runId)
 *      reverted to  IntrospectionRefusal.UnknownRun(runId)
 *      flips PRES-3 ONLY (failures=1 of 4).
 * ```
 *
 * One mutation per site, attributed 1:1 to the rows whose subject that site is. M-1 is
 * the pre-fix line; M-2 is the pre-fix line at the other site.
 *
 * M-1 was initially predicted to flip PRES-1 AND PRES-3. Measurement said otherwise:
 * PRES-3 returns through the `!runKnown` branch, which never reaches
 * `evStorePresenceOf`. The prediction was wrong because it assumed the two refusals
 * travelled the same path — they share the MAPPING, not the CALL SITE. Recording the
 * corrected number here rather than the predicted one is the point of running the
 * mutation at all.
 */
class RuntimeIntrospectionPortEventPresenceRefusalTest {

    private val runId = "run-presence"
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    /**
     * CONTROL. A store that answers "unknown run" is an answer about the run, and the
     * adapter must act on it: the observation is `LiveButEmpty`.
     *
     * This row exists so `PRES_1` cannot be green because the fixture never reached the
     * decision. The delta between this row and `PRES_1` is the refusal's constructor and
     * nothing else — same torn record, same empty journal, same adapter.
     */
    @Test
    fun PRES_0_torn_lease_record_with_an_unknown_run_is_live_but_empty(
        @TempDir tempDir: Path,
    ) {
        val result = inspect(tempDir, refusingWith(EventRecordReadRefusal.UnknownRun(runId)))

        val observation = assertInstanceOf(RuntimeIntrospectionResult.Observation::class.java, result)
        assertInstanceOf(RuntimeObservation.LiveButEmpty::class.java, observation.observation)
    }

    /**
     * The defect row. A store that FAILED must produce a refusal, not an absence claim.
     *
     * The failure kind is asserted, not just the shape: `StorageError` is not
     * `UnknownRun`, and a test that only asserted "some Refused" would have passed on
     * the old `!runKnown` mapping too.
     */
    @Test
    fun PRES_1_torn_lease_record_with_a_failing_store_refuses_rather_than_claiming_absence(
        @TempDir tempDir: Path,
    ) {
        val result = inspect(tempDir, refusingWith(EventRecordReadRefusal.StorageError("disk gone")))

        val refused = assertInstanceOf(RuntimeIntrospectionResult.Refused::class.java, result)
        val storage = assertInstanceOf(IntrospectionRefusal.StorageError::class.java, refused.refusal)
        assertEquals("disk gone", storage.cause)
    }

    /**
     * `Present` is a third answer, and it is not a refusal.
     *
     * Guards the other side of the same `when`: a served page means the store looked
     * and the run is here, so the adapter must proceed rather than refuse. The page is
     * produced by the REAL [EventRecordReadPortStoreAdapter] over a real store that
     * reports the run as existing with no envelopes, so this is a genuine empty page
     * rather than a hand-built one.
     */
    @Test
    fun PRES_2_a_served_page_is_not_treated_as_a_failure(
        @TempDir tempDir: Path,
    ) {
        val store = InMemoryEventStore()
        val result = inspect(
            tempDir,
            EventRecordReadPortStoreAdapter(
                store = store,
                runExists = { true },
                tailSequence = { null },
            ),
        )

        assertInstanceOf(RuntimeIntrospectionResult.Observation::class.java, result)
    }

    /**
     * The OTHER site. When nothing knows the run, a failing store must be reported as
     * the failure it is.
     *
     * This is the fail-closed direction: pre-fix it reported `UnknownRun`, which refuses
     * rather than lies. It is pinned anyway because the two sites are the same question
     * and one shared mapping is what keeps them from drifting apart again — the same
     * `readRecords(limit = 1)` call cannot mean two things in one file.
     */
    @Test
    fun PRES_3_on_the_unknown_run_path_a_failing_store_is_reported_as_the_failure_it_is(
        @TempDir tempDir: Path,
    ) {
        val bundle = Bundle(tempDir, refusingWith(EventRecordReadRefusal.StorageError("disk gone")))

        val result = bundle.port.inspect("a-run-nobody-declared")

        val refused = assertInstanceOf(RuntimeIntrospectionResult.Refused::class.java, result)
        val storage = assertInstanceOf(IntrospectionRefusal.StorageError::class.java, refused.refusal)
        assertEquals("disk gone", storage.cause)
    }

    // ---- fixture ---------------------------------------------------------------------------------

    private fun inspect(tempDir: Path, eventReads: EventRecordReadPort): RuntimeIntrospectionResult {
        givenATornLeaseRecord(tempDir, runId)
        return Bundle(tempDir, eventReads).use { it.port.inspect(runId) }
    }

    /**
     * A port that always refuses with [refusal].
     *
     * Deliberately blind to its arguments: the point of every row here is the refusal
     * the adapter receives, not the query it asked. Rows that depend on a specific
     * argument would be testing the port, which is not the subject.
     */
    private fun refusingWith(refusal: EventRecordReadRefusal): EventRecordReadPort =
        object : EventRecordReadPort {
            override fun readRecords(
                runId: String,
                after: EventCursor?,
                query: EventQuery,
                limit: Int,
            ): EventRecordReadResult = EventRecordReadResult.Refused(refusal)
        }

    private inner class Bundle(
        tempDir: Path,
        eventReads: EventRecordReadPort,
    ) : AutoCloseable {
        private val clock: Clock = object : Clock {
            override fun now(): Instant = at
        }
        private val journal = InMemoryOperationJournal(clock)
        private val cursors = InMemoryReplayCursorStore(clock)
        private val frames: OutputFrameIndex = object : OutputFrameIndex {
            override fun declareStream(stream: OutputStreamId, channel: OutputChannel) =
                throw UnsupportedOperationException("not reached by this fixture")

            override fun append(
                stream: OutputStreamId,
                channel: OutputChannel,
                from: Long,
                to: Long,
            ) = throw UnsupportedOperationException("not reached by this fixture")

            override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame> = emptyList()
            override fun lastOrdinal(runId: String): Long? = null
            override fun streamsOfRun(runId: String): List<OutputStreamId> = emptyList()
            override fun recoverUnframedBytes(): List<OutputFrame> = emptyList()
        }
        private val tails: OutputTailPort = object : OutputTailPort {
            override fun tailState(stream: OutputStreamId): OutputTailState? = null
        }
        val lease = FileBackedRunExecutionLeaseStore(tempDir.resolve("leases"))

        val port: RuntimeIntrospectionPort = RuntimeIntrospectionPortStoreAdapter(
            eventReads = eventReads,
            tails = tails,
            frames = frames,
            journal = journal,
            cursors = cursors,
            lease = lease,
        )

        override fun close() {
            lease.close()
        }
    }

    /**
     * A lease record file that EXISTS but does not parse — the crash shape.
     *
     * `FileBackedRunExecutionLeaseStore.isKnown` is `Files.isRegularFile(record)`, so it
     * answers `true`. `observe` goes through `readRecord`, which returns `null` when the
     * `run_id` field is absent. That disagreement is the only state in which the adapter
     * consults the event store for existence, which is why this fixture is required and
     * a plainly-absent run is not enough to reach the decision.
     */
    private fun givenATornLeaseRecord(tempDir: Path, runId: String) {
        val leaseDir = tempDir.resolve("leases")
        Files.createDirectories(leaseDir)
        val record = leaseDir.resolve("lease-" + runId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".record")
        // Written, but the identifying line never landed: a create that was not followed
        // by its append. `isKnown` sees the file; `readRecord` cannot find `run_id`.
        Files.writeString(record, "owner_id=someone\nfencing_token=7\n")
        assertTrue(Files.isRegularFile(record), "the torn record must exist on disk: $record")
    }
}
