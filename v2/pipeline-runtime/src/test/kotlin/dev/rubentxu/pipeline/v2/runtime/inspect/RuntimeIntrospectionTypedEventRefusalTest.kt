package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.FileBackedRunExecutionLeaseStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.events.shape.EventShapeReadPort
import dev.rubentxu.pipeline.v2.events.shape.EventShapeRefusal
import dev.rubentxu.pipeline.v2.events.shape.EventShapeResult
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `runStructure` must not answer "this run has no event-driven shape" with an event
 * plane that FAILED to answer.
 *
 * ## The defect this pins
 *
 * The adapter read the typed events and collapsed the outcome into a list:
 *
 * ```kotlin
 * private fun typedEventsOf(runId: String): List<TypedEvent> =
 *     (eventShapeRead?.eventShapesForRun(runId, EventShapeOptions.Default)
 *         as? EventShapeResult.Events)?.events ?: emptyList()
 * ```
 *
 * [EventShapeResult] has three cases and only one of them is an answer. `Events` is
 * events; `Empty` is a genuine "the run is known and has no typed events yet", and
 * falling back to the synthetic M6 view for it is correct. `Refused` is neither: it is
 * the plane saying *I could not look*. The `?: emptyList()` swallowed it and the caller
 * received a well-formed synthetic structure — a confident claim about a run's shape
 * that no caller could falsify, and that looked identical to a build with no event
 * plane wired at all.
 *
 * This is the same defect class as `RuntimeIntrospectionPortEventPresenceRefusalTest`
 * one function above in the same file, on the sibling port: a read refusal reported as
 * a fact about the run. Two occurrences of one class is what makes it a class, and why
 * this row exists rather than "the fix was already covered".
 *
 * ## Why `UnknownRun` refuses instead of degrading
 *
 * [EventShapeRefusal.UnknownRun] from an envelope-reading port is a legitimate answer
 * about the run, and `evStorePresenceOf` above degrades on it. Here it does not, and the
 * difference is not an inconsistency:
 *
 * ```text
 * evStorePresenceOf        consulted only when the lease said the run is known
 *                         -> "store does not know it" is still evidence
 *
 * readTypedEventsForRun    called only AFTER journalOps.isNotEmpty()
 *                         -> journal HAS rows for a run the plane does not know
 *                            = a disagreement between two durable authorities
 * ```
 *
 * `SHP_2` pins that disagreement as a refusal. The precondition that makes it total —
 * the call site is unreachable with an empty journal — is why no extra check is needed
 * at the branch.
 *
 * ## The production entry point this crosses
 *
 * ```text
 * RuntimeIntrospectionPortStoreAdapter.runStructure(runId, options)
 *   -> journal.listForRun(runId)      REAL InMemoryOperationJournal, ONE row
 *   -> readTypedEventsForRun(runId)   the PORT under test, the only substitute
 *   -> buildSyntheticStage(...)       REAL, only on the Unavailable arm
 * ```
 *
 * **HF1 — in-process, and the substitute is at the PORT boundary.** The journal,
 * cursor store, lease store and its durable record file are real. Only
 * [EventShapeReadPort] is substituted, for the same reason its sibling test
 * substitutes the record port: the store adapter cannot produce a `StorageError` on
 * demand, and corrupting a real substrate to force one would test the corruption.
 * A refusal arriving from the port IS the production shape of that input.
 *
 * `eventReads` is wired to a port that THROWS. [runStructure] must not consult it —
 * if it ever does, every row here fails with a message naming the violation instead of
 * a plausible-looking structure. That turns the fixture into evidence that each
 * refusal travelled through [eventShapeRead] and no other door.
 *
 * ## Reaching the decision at all
 *
 * `runStructure` returns `RunStructure.Empty` before the plane is consulted if the
 * journal has no rows for the run. Every fixture here therefore appends exactly one
 * operation first, and `SHP_0` is the control that proves the fixture gets that far:
 * with a plane that answers `Empty` the run comes back as a synthetic stage, and
 * `SHP_1` differs from it by the refusal's constructor alone.
 *
 * ## The mutations that must kill this
 *
 * Measured 2026-10-18 on this class; every number below is from the XML, not predicted.
 * One mutation per arm of the new `when`, attributed 1:1 to the rows that arm owns:
 *
 * ```text
 * M-1  EventShapeRefusal.StorageError -> Unreadable   became  Unavailable
 *      flips SHP_1 ONLY (failures=1 of 5). SHP_0/2/3/4 stay green.
 *
 * M-2  EventShapeRefusal.UnknownRun   -> Unreadable   became  Unavailable
 *      flips SHP_2 ONLY (failures=1 of 5).
 *
 * M-3  eventShapeRead ?: Unavailable   became  Unreadable(StorageError("no port"))
 *      flips SHP_4 ONLY (failures=1 of 5).
 * ```
 *
 * Each mutation is confined to the arm it rewrites, which is what makes the attribution
 * meaningful: a GREEN row outside its own mutation means the row is not measuring the
 * arm it names. `SHP_0` and `SHP_3` survive all three because they assert the two arms
 * the fix must NOT touch, and they would flip if the fix over-corrected into "refuse on
 * every non-`Events` result".
 *
 * Each mutation was verified to have APPLIED before its GREEN was read — the script
 * asserts its anchor occurs exactly once in the pristine file and re-checks the text
 * after the write. A mutation that silently failed to apply is not a surviving guard,
 * it is a measurement of nothing.
 */
class RuntimeIntrospectionTypedEventRefusalTest {

    private val runId = "run-shape"
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    /**
     * CONTROL. A plane that answers "no typed events for this run" is an ANSWER, and
     * the adapter must act on it by degrading to the synthetic M6 view.
     *
     * Without this row `SHP_1` could be green because the fixture never reached the
     * plane. The delta between this row and `SHP_1` is the plane's constructor and
     * nothing else — same journal, same operation, same adapter, same options.
     */
    @Test
    fun SHP_0_a_plane_that_serves_no_events_degrades_to_the_synthetic_view(
        @TempDir tempDir: Path,
    ) {
        val result = structureOf(tempDir, EventShapeResult.Empty)

        val structure = assertInstanceOf(RunStructureResult.Structure::class.java, result)
        val stages = assertInstanceOf(RunStructure.Stages::class.java, structure.structure).stages
        assertEquals(1, stages.size)
        assertEquals("synthetic-stage", stages[0].stageIndex)
        assertEquals("synthetic-step", stages[0].steps[0].stepKey)
    }

    /**
     * The defect row. A plane that FAILED must produce a refusal, not a synthetic shape.
     *
     * The failure KIND is asserted, not just the wrapper: a test that only asserted
     * "some Refused" would pass on an adapter that refused with the wrong reason, and
     * the wrong reason here is `UnknownRun` — which claims the run does not exist, the
     * opposite of what a journal with rows for it means.
     */
    @Test
    fun SHP_1_a_failing_plane_refuses_rather_than_claiming_the_run_has_no_event_shape(
        @TempDir tempDir: Path,
    ) {
        val result = structureOf(tempDir, shapeRead = refusingWith(EventShapeRefusal.StorageError("disk gone")))

        val refused = assertInstanceOf(RunStructureResult.Refused::class.java, result)
        val storage = assertInstanceOf(IntrospectionRefusal.StorageError::class.java, refused.refusal)
        assertEquals("disk gone", storage.cause)
    }

    /**
     * The disagreement. The journal has a row for a run the plane does not know, which
     * is two durable authorities contradicting each other — see the class KDoc for why
     * this refuses when its envelope-reading sibling degrades.
     */
    @Test
    fun SHP_2_a_plane_that_does_not_know_a_run_the_journal_knows_refuses(
        @TempDir tempDir: Path,
    ) {
        val result = structureOf(tempDir, shapeRead = refusingWith(EventShapeRefusal.UnknownRun(runId)))

        val refused = assertInstanceOf(RunStructureResult.Refused::class.java, result)
        val unknown = assertInstanceOf(IntrospectionRefusal.UnknownRun::class.java, refused.refusal)
        assertEquals(runId, unknown.runId)
    }

    /**
     * The arm that must NOT change. An unconfigured plane is a stable property of the
     * BUILD, not a failure, so degrading to the synthetic view is the correct answer.
     *
     * This is what stops a "refuse on every non-`Events` result" fix from passing: that
     * over-correction would flip this row, and a caller in a build with no event plane
     * would lose `runStructure` entirely.
     */
    @Test
    fun SHP_3_an_unconfigured_plane_still_degrades_rather_than_refusing(
        @TempDir tempDir: Path,
    ) {
        val result = structureOf(tempDir, shapeRead = refusingWith(EventShapeRefusal.NoEventStore))

        assertInstanceOf(RunStructureResult.Structure::class.java, result)
    }

    /**
     * The other arm that must NOT change: no plane wired at all — the M6 first cut.
     *
     * Absent port and refusing port are different facts about the build, and merging
     * them is the same collapse the fix exists to prevent, one step earlier.
     */
    @Test
    fun SHP_4_a_build_with_no_event_plane_still_degrades_rather_than_refusing(
        @TempDir tempDir: Path,
    ) {
        val result = structureOf(tempDir, shapeRead = null)

        assertInstanceOf(RunStructureResult.Structure::class.java, result)
    }

    // ---- fixture ---------------------------------------------------------------------------------

    /** A plane that always answers [EventShapeResult.Refused] with [refusal]. */
    private fun refusingWith(refusal: EventShapeRefusal): EventShapeReadPort =
        EventShapeReadPort { _, _ -> EventShapeResult.Refused(refusal) }

    /**
     * `runStructure` must reach the plane and nowhere else.
     *
     * [AssertionError] is an [Error], not an [Exception], so it is not caught by the
     * adapter's `catch (e: Exception)` and reaches the assertion as a failure instead of
     * degrading into a plausible structure.
     */
    private val aRecordPortThatIsNotPartOfThisSubject = object : EventRecordReadPort {
        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult = throw AssertionError(
            "runStructure must not read event records; a refusal must travel through EventShapeReadPort",
        )
    }

    private fun structureOf(
        tempDir: Path,
        shapeResult: EventShapeResult = EventShapeResult.Empty,
        shapeRead: EventShapeReadPort? = EventShapeReadPort { _, _ -> shapeResult },
    ): RunStructureResult =
        Bundle(tempDir, shapeRead).use { bundle ->
            bundle.appendOperation(opId = "op-1")
            bundle.port.runStructure(runId, StructureRunOptions.Default)
        }

    private inner class Bundle(
        tempDir: Path,
        private val eventShapeRead: EventShapeReadPort?,
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
        private val lease = FileBackedRunExecutionLeaseStore(tempDir.resolve("leases"))

        val port: RuntimeIntrospectionPort = RuntimeIntrospectionPortStoreAdapter(
            eventReads = aRecordPortThatIsNotPartOfThisSubject,
            tails = tails,
            frames = frames,
            journal = journal,
            cursors = cursors,
            lease = lease,
            eventShapeRead = eventShapeRead,
        )

        fun appendOperation(opId: String) {
            journal.append(
                RerunOperation(
                    id = opId,
                    fingerprint = Fingerprint(SHORT_FINGERPRINT),
                    input = OperationInput(
                        stepId = "step-$opId",
                        params = emptyMap(),
                        runId = runId,
                        attempt = 1,
                    ),
                    output = null,
                    status = OperationStatus.SUCCEEDED,
                    attempt = 1,
                ),
                deadlineMs = null,
            )
        }

        override fun close() {
            lease.close()
        }
    }

    private companion object {
        const val SHORT_FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}