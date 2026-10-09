package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * OBS-E3: the failure-context projection, over the two real authorities it reads.
 *
 * ## Harness fidelity
 *
 * HF2 — a real [SegmentOutputStore] on a real filesystem, written through the same
 * declare/reserve/write/commit/append order `ShExecution` uses, and a real SQLite
 * [OperationJournal] read through `listForRun`. Nothing here reimplements either authority, and the
 * operation ids are derived with [OpId.format] rather than typed out, so a change to the identity
 * format breaks these rows instead of silently diverging from production.
 *
 * The one double is the read port in [REFUSE-1], stated in the row: the store's own refusal taxonomy
 * is certified elsewhere, and what this row adds is the projection's obligation to PROPAGATE a
 * refusal rather than report an empty tail.
 *
 * ## What this pins
 *
 * ```text
 * SELECT-1  only non-successful operations are selected, and UNSTABLE is not one of them
 * IDENT-1   the operation id the reader fetches by is the one the journal recorded
 * TAIL-1    the tail is the END of the committed stream, bounded by tailBytes
 * TAIL-2    a channel that never committed reads as empty, not as a refusal
 * SCOPE-1   failure events stay at run scope and are never paired with an operation
 * REFUSE-1  a refusal fails the whole read instead of yielding empty tails
 * ```
 *
 * ## Mutation
 *
 * - `M-E6` (select by journal status) → select every operation, ignoring status.
 * - `M-E7` (fetch by journal identity) → fetch by the step id instead of the operation id.
 * - `M-E8` (bounded tail at the end) → read from the start of the stream.
 * - `M-E9` (refusal propagates) → answer a refusal with an empty tail.
 * - `M-E10` (facts stay unpaired) → attach each `StepFailed` to an operation by position.
 */
class FailureContextTest {

    @TempDir
    lateinit var root: Path

    private lateinit var store: SegmentOutputStore
    private lateinit var journal: OperationJournal

    private val runId = "run-obse3"
    private val clock: Clock = object : Clock {
        override fun now(): Instant = java.time.Clock.systemUTC().instant()
    }

    @BeforeEach
    fun setUp() {
        store = SegmentOutputStore(root.resolve("output-plane"))
        store.recover()

        val eventStore = SqliteEventStore(root.resolve("events.db").toString())
        journal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            clock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )
    }

    // ---------------------------------------------------------------- authorities

    /** The production order: declare, reserve, write, commit, THEN append the frame. */
    private fun publish(operationId: String, channel: OutputChannel, text: String) {
        val stream = OutputStreamAddress.of(runId, operationId, channel).stream
        store.frameIndex().declareStream(stream, channel)
        val payload = text.toByteArray(Charsets.UTF_8)
        val from = store.committedExtent(stream) ?: 0L
        val reservation = store.open(stream).reserve(payload.size)
        reservation.write(payload)
        reservation.commit()
        store.frameIndex().append(stream, channel, from, from + payload.size)
    }

    /** A journal row shaped the way `StepDispatchEngine` writes one. */
    private fun record(
        operationId: String,
        stepId: String,
        status: OperationStatus,
        attempt: Int = 1,
    ): DurableOperation = RerunOperation(
        id = operationId,
        fingerprint = Fingerprint("a".repeat(64)),
        input = OperationInput(stepId, mapOf(), runId, attempt),
        // A failed step's CommonExecutionResult carries encodedOutput = null, so its row has no
        // output. Written here as production writes it, because a fixture that quietly supplied one
        // would hide exactly the fact this projection has to cope without.
        output = null,
        status = status,
        attempt = attempt,
    )

    private fun read(
        tailBytes: Int = 64,
        events: List<dev.rubentxu.pipeline.v2.events.DomainEvent> = emptyList(),
    ): FailureContext = assertInstanceOf(
        FailureContextRead.Page::class.java,
        FailureContextReader(journal, store, tailBytes).read(runId, events),
        "expected a page",
    ).context

    // ---------------------------------------------------------------- rows

    @Test
    fun `SELECT-1 only non-successful operations are selected and UNSTABLE is not one`() {
        val succeeded = record("run-obse3-s0-0", "build/echo-0", OperationStatus.SUCCEEDED)
        val unstable = record("run-obse3-s1-0", "build/warn-0", OperationStatus.UNSTABLE)
        val failed = record("run-obse3-s2-0", "build/sh-0", OperationStatus.FAILED)
        val timedOut = record("run-obse3-s3-0", "test/sh-0", OperationStatus.FAILED_TIMEOUT)
        val running = record("run-obse3-s4-0", "test/sh-1", OperationStatus.RUNNING)
        val pending = record("run-obse3-s5-0", "test/sh-2", OperationStatus.PENDING)
        val lost = record("run-obse3-s6-0", "test/sh-3", OperationStatus.LOST)

        val selected = selectFailures(listOf(succeeded, unstable, failed, timedOut, running, pending, lost))

        assertEquals(
            listOf("run-obse3-s2-0", "run-obse3-s3-0", "run-obse3-s6-0"),
            selected.map { it.operationId },
            "only FAILED, FAILED_TIMEOUT and LOST are failures. UNSTABLE is NOT: its own contract " +
                "says the work ran and something in it deserves attention, which is what warnError " +
                "produces — a run that only went unstable did not fail, and listing it here would " +
                "make failure-context report runs that did not fail.",
        )

        assertFalse(
            selected.any { it.stepId == "build/warn-0" },
            "the unstable step must not appear in a failure context",
        )
        assertEquals(
            listOf("build/echo-0"),
            listOf(succeeded.input.stepId),
            "the two identities in this fixture differ on purpose: the journal records the StepId " +
                "in input.stepId and the operation id as row id, and confusing them is the defect " +
                "ObservationOperationIdShapeTest exists to catch.",
        )
    }

    @Test
    fun `IDENT-1 the reader fetches output by the operation id the journal recorded`() {
        val operationId = OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()
        journal.append(record(operationId, stepId = "build/sh-0", status = OperationStatus.FAILED), null)
        publish(operationId, OutputChannel.STDOUT, "compiling module A\n")
        publish(operationId, OutputChannel.STDERR, "error: unresolved symbol\n")

        val context = read()

        assertEquals(1, context.operations.size, "one operation failed")
        val failure = context.operations.single()
        assertEquals(operationId, failure.identity.operationId)
        assertEquals(
            "build/sh-0",
            failure.identity.stepId,
            "the context names BOTH identities and keeps them apart: the step id names the step in " +
                "the pipeline, the operation id names the durable operation whose streams were read",
        )
        assertEquals("compiling module A\n", failure.stdoutTail)
        assertEquals("error: unresolved symbol\n", failure.stderrTail)
        assertTrue(failure.wroteOutput, "this operation committed bytes")
    }

    @Test
    fun `IDENT-2 an operation that failed without running leaves empty tails, not an error`() {
        // Most steps that fail never started a process, so they have no stream at all. That is a
        // fact about the run, not a refusal by the store.
        val operationId = OpId(runId = runId, stageIndex = 1, stepIndex = 0).format()
        journal.append(record(operationId, stepId = "build/error-0", status = OperationStatus.FAILED), null)

        val failure = read().operations.single()

        assertEquals("", failure.stdoutTail, "no stream was ever opened for this operation")
        assertEquals("", failure.stderrTail)
        assertFalse(failure.wroteOutput, "and the projection says so rather than inventing content")
    }

    @Test
    fun `TAIL-1 the tail is the END of the committed stream and is bounded`() {
        val operationId = OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()
        journal.append(record(operationId, stepId = "build/sh-0", status = OperationStatus.FAILED), null)
        publish(operationId, OutputChannel.STDOUT, "OLD-NOISE-ONE\n")
        publish(operationId, OutputChannel.STDOUT, "OLD-NOISE-TWO\n")
        val evidence = "the actual reason it died\n"
        publish(operationId, OutputChannel.STDOUT, evidence)

        // Exactly the length of the evidence, so the assertion is about WHICH end was read rather
        // than about where an arbitrary window happens to land.
        val failure = read(tailBytes = evidence.length).operations.single()

        assertEquals(
            evidence,
            failure.stdoutTail,
            "the tail is the last tailBytes of the stream, not its start. For a failing build the " +
                "end is the evidence; a reader that drained from the beginning would cost the same " +
                "work to reach a less useful window.",
        )
        assertFalse(
            failure.stdoutTail.contains("OLD-NOISE"),
            "the bounded window must actually exclude the earlier bytes",
        )

        // And a SMALLER window must cut into the evidence rather than silently returning all of
        // it, which is what a reader that ignored tailBytes would do.
        val narrow = read(tailBytes = evidence.length - 6).operations.single()
        assertEquals(
            evidence.drop(6),
            narrow.stdoutTail,
            "shrinking tailBytes shrinks the tail from the END, so the bound is real",
        )
    }

    @Test
    fun `TAIL-2 a channel that never committed reads as empty`() {
        val operationId = OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()
        journal.append(record(operationId, stepId = "build/sh-0", status = OperationStatus.FAILED), null)
        publish(operationId, OutputChannel.STDOUT, "only stdout here\n")

        val failure = read().operations.single()

        assertEquals("only stdout here\n", failure.stdoutTail)
        assertEquals("", failure.stderrTail, "stderr was declared by no publisher and reads empty")
        assertTrue(failure.wroteOutput)
    }

    @Test
    fun `SCOPE-1 failure events stay at run scope and are never paired with an operation`() {
        val first = OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()
        val second = OpId(runId = runId, stageIndex = 1, stepIndex = 0).format()
        journal.append(record(first, stepId = "build/sh-0", status = OperationStatus.FAILED), null)
        journal.append(record(second, stepId = "test/sh-0", status = OperationStatus.FAILED), null)

        // TWO failures, and both events are structurally indistinguishable from the operations:
        // StepFailed carries stepName and stepIndex but NO stageIndex, so `test/sh-0` and
        // `build/sh-0` both match "sh at index 0". This is why no pairing is derivable.
        val events = listOf(
            stepFailed(stepName = "sh", stepIndex = 0, message = "build failed"),
            stepFailed(stepName = "sh", stepIndex = 0, message = "test failed"),
        )

        val context = read(events = events)

        assertEquals(2, context.operations.size)
        assertEquals(
            listOf("build failed", "test failed"),
            context.facts.map { it.message },
            "both messages survive, unpaired and in order",
        )
        assertEquals(
            null,
            context.runOutcome,
            "no RunFinished was supplied, so the outcome stays null rather than being guessed " +
                "from the operations: an operation status is not a run outcome",
        )
        assertTrue(context.didFail, "the run did fail")
    }

    @Test
    fun `SCOPE-2 the run outcome is read from RunFinished`() {
        val operationId = OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()
        journal.append(record(operationId, stepId = "build/sh-0", status = OperationStatus.FAILED), null)

        val finished = dev.rubentxu.pipeline.v2.events.RunFinished(
            eventId = "e-1",
            runId = runId,
            sequence = 9L,
            occurredAt = Instant.EPOCH,
            outcome = "FAILURE",
            diagnostics = emptyList(),
        )

        assertEquals("FAILURE", read(events = listOf(finished)).runOutcome)
    }

    @Test
    fun `SCOPE-3 a run that only went unstable reports no failure`() {
        val operationId = OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()
        journal.append(record(operationId, stepId = "build/warn-0", status = OperationStatus.UNSTABLE), null)

        val context = read()

        assertEquals(emptyList<FailedOperation>(), context.operations, "unstable is not a failure")
        assertFalse(context.didFail, "so the projection reports a run that did not fail")
    }

    @Test
    fun `REFUSE-1 a refusal fails the whole read instead of yielding empty tails`() {
        val operationId = OpId(runId = runId, stageIndex = 0, stepIndex = 0).format()
        journal.append(record(operationId, stepId = "build/sh-0", status = OperationStatus.FAILED), null)
        publish(operationId, OutputChannel.STDOUT, "real output\n")

        val refusing: OutputReadPort = object : OutputReadPort {
            override fun committedExtent(stream: OutputStreamId): Long? = 12L
            override fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int) =
                OutputReadResult.Refused(OutputRefusal.UnknownStream(cursor.stream))

            override fun readRange(stream: OutputStreamId, from: Long, to: Long) =
                OutputReadResult.Refused(OutputRefusal.UnknownStream(stream))
        }

        val read = FailureContextReader(journal, refusing, tailBytes = 64).read(runId)

        assertInstanceOf(
            FailureContextRead.Refused::class.java,
            read,
            "a refusal must propagate. Answering it with empty tails would report a broken store as " +
                "a step that printed nothing — the same collapse ObservationOutputRead was sealed " +
                "to close on the output lane.",
        )
    }

    @Test
    fun `ORDER-1 the journal's own order is preserved`() {
        journal.append(
            record("run-obse3-s2-0", "build/sh-2", OperationStatus.FAILED),
            null,
        )
        journal.append(
            record("run-obse3-s0-0", "build/sh-0", OperationStatus.FAILED),
            null,
        )
        journal.append(
            record("run-obse3-s1-0", "build/sh-1", OperationStatus.FAILED),
            null,
        )

        assertEquals(
            listOf("run-obse3-s2-0", "run-obse3-s0-0", "run-obse3-s1-0"),
            selectFailures(journal.listForRun(runId)).map { it.operationId },
            "listForRun already returns rows in execution order and that order is kept rather than " +
                "re-sorted: which failure came first is a fact the journal recorded, and sorting by " +
                "status or by id would discard it.",
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun stepFailed(stepName: String, stepIndex: Int, message: String): StepFailed =
        StepFailed(
            eventId = "e-$stepName-$stepIndex-$message",
            runId = runId,
            sequence = 0L,
            occurredAt = Instant.EPOCH,
            stepIndex = stepIndex,
            stepName = stepName,
            stepType = stepName,
            failureKind = FailureKind.SCRIPT,
            message = message,
        )
}
