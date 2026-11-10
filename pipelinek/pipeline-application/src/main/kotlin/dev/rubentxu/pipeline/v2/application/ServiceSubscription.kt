package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.application.observation.RunPlane
import dev.rubentxu.pipeline.v2.application.observation.FollowDecision
import dev.rubentxu.pipeline.v2.application.observation.followDecision
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.follow.EventFollowRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import kotlinx.serialization.json.JsonObject
import dev.rubentxu.pipeline.v2.service.FrameOrdinalToken
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceEndReason
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import dev.rubentxu.pipeline.v2.service.ServiceSubscriptionRequest

/**
 * The streets of the local service.
 *
 * ## What this owns
 *
 * How a subscription is SERVED: the frames that go on the wire, the cursor
 * that travels with each of them, and the refusal that ends a street when it
 * cannot go on. The protocol —what a frame is and what a request may ask for
 * —is [ServiceCodec]'s, and the durable authorities the streets read are the
 * ones the service was composed with.
 *
 * ## The two streets and why there are two
 *
 * The events street and the output street are two DIFFERENT lanes with two
 * different positions, and this class serves them separately because they
 * have no total order between them: an event sequence and an output ordinal
 * are not comparable, and a document that interleaved them would have to
 * invent an order the storage never recorded. A client that wants both opens
 * two subscriptions.
 *
 * ## Why the events street rides the READ PORT and not the published follower
 *
 * The published follower is a good contract and this street could have ridden
 * it, but it cannot ride it and still end a street:
 *
 *  - **The follower never reports a quiet poll.** A poll that finds no row
 *    produces NO event at all, so a consumer cannot know that it is caught
 *    up with the tail. The street needs that fact: it is what tells it that
 *    there is nothing left to deliver.
 *  - **The follower's terminal detection only fires on a DELIVERED row.** It
 *    ends when the terminal row is inside a page it just handed over. A
 *    client that reconnects with a cursor at or past that row — the ordinary
 *    reconnect of a run that already finished — would be handed nothing, and
 *    the street would stay open for ever.
 *
 * The read port publishes the same facts the follower publishes: the same
 * cursor family ([EventCursor] in, [EventCursor] out), the same three named
 * refusals (through the follower's own translation,
 * [EventFollowRefusal.of]), and the same undecodable rows. And it publishes
 * the one the follower does not: the QUIET page, which is the signal the
 * street needs.
 *
 * ## Why the output street rides the APPLICATION path
 *
 * The published `OutputFollower` resumes from a frame ORDINAL and publishes a
 * BYTE cursor, and it never publishes the ordinal it stands at: a consumer
 * of that contract cannot resume from what the contract publishes. The
 * reader this street uses publishes the ordinal —it is the position of the
 * last record of the page— and it is the same reader the command line's
 * `observe --follow --view console` uses, so the socket and the command line
 * cannot disagree about what the console of a run is.
 *
 * ## Why the end of a street says the run is OVER and nothing more
 *
 * The end frame carries a reason with one word, and the word says that the
 * run's terminal fact was observed. It says NOTHING about whether the run
 * succeeded: the outcome is another authority's answer —`run.state`, or the
 * `outcome` of the terminal event— and a street that also carried it would be
 * a second opinion about the run's fate. A street that cannot say that the
 * terminal fact was observed does NOT end: it refuses, naming the fact that
 * stopped it.
 *
 * ## Why the socket REFUSES where the command line DEGRADES
 *
 * The decision that ends a street is the command line's, and both end it on
 * the same fact. But the command line's lane can be one with NO event store at
 * all, and such a lane still has to be able to end: degrading to the output
 * lane's own authority is what lets a console-only follow finish on a sealed
 * stream. The socket composes both authorities, and it has a vocabulary of
 * refusals, so a run plane it cannot ask is a REFUSAL with a name and not a
 * degradation that keeps reading. The two disagree on this one point, on
 * purpose: a street that cannot decide must not wait for ever.
 */
internal class ServiceSubscription(
    private val lanes: ObserveLanes,
    private val events: EventRecordReadPort,
) : ServiceStreets {

    /**
     * Serves the street [request] asks for on [connection].
     *
     * The method returns when the street ends. The connection is closed by
     * the caller, which owns it.
     */
    override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) {
        when (request) {
            is ServiceSubscriptionRequest.EventsFollow -> serveEvents(connection, request)
            is ServiceSubscriptionRequest.OutputFollow -> serveOutput(connection, request)
        }
    }

    /**
     * Serves the events of [request]'s run on [connection].
     *
     * The street ends when the run's terminal fact was observed —either
     * because the terminal row was delivered, or because the street caught
     * up with a run that was already over— and refuses when the store cannot
     * answer.
     */
    private fun serveEvents(
        connection: ServiceConnection,
        request: ServiceSubscriptionRequest.EventsFollow,
    ) {
        var cursor: EventCursor? = request.after
        // Whether the run plane has already been asked. It is asked ONCE, and
        // the reason is a proof, not an economy: the street asks at its first
        // quiet page, which is the moment its cursor stands at the tail. If
        // the run is still going then, the terminal row will be written
        // AFTER that tail, so it will be DELIVERED, and the street will end
        // on the delivery. If the run was already over, the terminal row is
        // at or before the tail, so the client either has it or is resumed
        // past it, and the street ends there and then.
        var askedAboutTheRun = false
        while (true) {
            var delivered = false
            when (val read = events.readRecords(
                runId = request.runId,
                after = cursor,
                query = EventQuery.All,
                limit = RECORDS_PER_READ,
            )) {
                is EventRecordReadResult.Refused -> {
                    connection.writeLine(refusedLine(refusalOf(read.refusal)))
                    return
                }
                is EventRecordReadResult.Page -> {
                    val slice = read.slice
                    if (slice.records.isNotEmpty()) {
                        delivered = true
                        slice.records.forEachIndexed { index, record ->
                            connection.writeLine(
                                ServiceCodec.encodeRecord(
                                    cursor = EventCursor(request.runId, record.sequence).encode(),
                                    record = documentOf(record),
                                    // More of THIS lane follows this record: either the page has
                                    // another row behind it, or the page says the store had more
                                    // behind the page.
                                    hasMore = index < slice.records.size - 1 || slice.hasMore,
                                ),
                            )
                        }
                        cursor = slice.nextCursor
                        if (slice.decoded.any { it is RunFinished }) {
                            // The terminal row was DELIVERED, so the terminal fact was observed
                            // by the client itself. Nothing else has to be asked.
                            connection.writeLine(
                                ServiceCodec.encodeEnd(
                                    ServiceEndReason.TerminalObserved,
                                    slice.nextCursor.encode(),
                                ),
                            )
                            return
                        }
                    } else if (!askedAboutTheRun) {
                        askedAboutTheRun = true
                        when (val execution = executionOf(request.runId)) {
                            is Execution.Failed -> {
                                // The plane refused to answer and the street cannot answer
                                // either, so it refuses with the plane's own word instead of
                                // claiming a terminal fact nobody observed.
                                connection.writeLine(refusedLine(execution.refusal))
                                return
                            }
                            is Execution.Answered -> if (execution.plane == RunPlane.Completed) {
                                connection.writeLine(
                                    ServiceCodec.encodeEnd(
                                        ServiceEndReason.TerminalObserved,
                                        (cursor ?: EventCursor(request.runId, 0L)).encode(),
                                    ),
                                )
                                return
                            }
                        }
                    }
                }
            }
            // A round that delivered something is followed by another round at once: the store
            // may have more behind the page. A round that delivered nothing is followed by a
            // wait, because the street asked for nothing and there was nothing to give it.
            if (!delivered && !idle()) return
        }
    }

    /**
     * Serves the console of [request]'s run on [connection].
     *
     * The street reads a page at a time and writes each record as it goes,
     * so a street that the client stops reading holds no more than one page.
     *
     * The street ends with a WORD whenever it cannot go on: a run that does
     * not exist is refused by name, a run plane that cannot be asked is
     * refused by name, and a run plane that refused to answer at all is
     * refused by name too. It never closes in silence, and it never waits
     * for a console that is not going to arrive.
     */
    private fun serveOutput(
        connection: ServiceConnection,
        request: ServiceSubscriptionRequest.OutputFollow,
    ) {
        var afterOrdinal = request.after
        var cursor = FrameOrdinalToken.encode(afterOrdinal)
        // Whether the street has already asked whether the run exists. It asks ONCE, and the
        // reason is a proof, not an economy: a run nobody declared and that holds no rows is not
        // going to declare itself later, and a run that exists keeps existing.
        var askedAboutTheRun = false
        while (true) {
            var moreFrames = false
            var delivered = false
            when (val read = lanes.outputOf(request.runId, afterOrdinal, FRAMES_PER_READ)) {
                null -> {
                    connection.writeLine(refusedLine(ServiceRefusal.NotConfiguredOutputPlane))
                    return
                }
                is ObservationOutputRead.Refused -> {
                    connection.writeLine(refusedLine(refusalOf(read.reason)))
                    return
                }
                is ObservationOutputRead.Page -> {
                    val page = read.page
                    moreFrames = page.moreFrames
                    delivered = page.records.isNotEmpty()
                    page.records.forEachIndexed { index, record ->
                        connection.writeLine(
                            ServiceCodec.encodeRecord(
                                cursor = FrameOrdinalToken.encode(record.frame.ordinal),
                                record = ObservationJsonLines.encodeOneObject(record),
                                hasMore = index < page.records.size - 1 || page.moreFrames,
                            ),
                        )
                    }
                    // The resume position IS the last record of the page, and the page says so
                    // about itself: an empty page carries back the position it was asked for.
                    afterOrdinal = page.lastOrdinal
                    cursor = FrameOrdinalToken.encode(page.lastOrdinal)
                }
            }

            // A round that delivered NOTHING is the moment the question of the run's existence
            // matters: the street is about to wait, and waiting for the console of a run that
            // does not exist is the hole this street closes. A round that delivered something
            // has already answered the question: the run has a console, so it exists.
            if (!delivered && !askedAboutTheRun) {
                askedAboutTheRun = true
                when (val existence = existenceOf(request.runId)) {
                    is Existence.Refused -> {
                        connection.writeLine(refusedLine(existence.refusal))
                        return
                    }
                    Existence.Known -> Unit
                }
            }

            // Two authorities, asked separately: the tails say whether more BYTES can arrive, and
            // the run plane says whether the run ended at all. The decision is the command line's,
            // so the socket and the command line end a street at the same moment for the same
            // reason — but the socket does not DEGRADE a plane it cannot ask: it refuses, because
            // a street that cannot decide cannot wait for ever.
            val tails = lanes.outputTailsOf(request.runId).orEmpty()
            when (val execution = executionOf(request.runId)) {
                is Execution.Failed -> {
                    connection.writeLine(refusedLine(execution.refusal))
                    return
                }
                is Execution.Answered -> when (
                    followDecision(moreFrames, tails, execution.plane)
                ) {
                    FollowDecision.ReadAgain -> {
                        if (!moreFrames && !idle()) return
                    }
                    // The decision can only be FINISHED when the plane says the run is over, so
                    // the terminal fact was observed and the street ends saying so.
                    FollowDecision.Finished -> {
                        connection.writeLine(
                            ServiceCodec.encodeEnd(ServiceEndReason.TerminalObserved, cursor),
                        )
                        return
                    }
                }
            }
        }
    }

    /**
     * Whether [runId] names a run this instance knows.
     *
     * The question goes to the READ PORT, because its refusal IS that
     * distinction: the port asks the event store AND the authority that
     * declares a run, and it refuses `unknown-run` only when both say no.
     *
     * The output plane cannot answer it — it knows STREAMS, not runs — and
     * the run plane cannot either: it answers about the rows of the run, and
     * a run nobody wrote a row in looks exactly like a run that does not
     * exist.
     *
     * The question carries a `null` cursor because it asks whether the run
     * exists, not what is behind the position the client resumed from.
     */
    private fun existenceOf(runId: String): Existence =
        when (val read = events.readRecords(
            runId = runId,
            after = null,
            query = EventQuery.All,
            limit = 1,
        )) {
            is EventRecordReadResult.Page -> Existence.Known
            is EventRecordReadResult.Refused -> Existence.Refused(refusalOf(read.refusal))
        }

    /**
     * What the execution authority says about [runId].
     *
     * The plane has three answers and can throw a fourth fact on top of
     * them, and the street turns them into two: an answer it can act on, or
     * a refusal it can name. A plane that cannot be asked is NOT a plane that
     * says "no": a street that ended on an unanswered question would be
     * claiming a terminal fact nobody observed, and a street that kept
     * reading on it would wait for ever.
     */
    private fun executionOf(runId: String): Execution = try {
        when (lanes.runPlaneOf(runId)) {
            RunPlane.Completed -> Execution.Answered(RunPlane.Completed)
            RunPlane.Live -> Execution.Answered(RunPlane.Live)
            RunPlane.Unavailable -> Execution.Failed(ServiceRefusal.NotConfiguredEventPlane)
        }
    } catch (failure: Exception) {
        Execution.Failed(ServiceRefusal.StorageFailed(failure.shortDiagnostic()))
    }

    /**
     * What the street learned about the run's execution.
     *
     * The street's own vocabulary, and deliberately NOT the plane's: the
     * plane's third answer —that it cannot be asked— is a REFUSAL here, not
     * a state to keep reading in.
     */
    private sealed interface Execution {

        /** The authority answered, and [plane] is what it said. */
        data class Answered(val plane: RunPlane) : Execution

        /** The authority could not answer, and [refusal] is the word the street refuses with. */
        data class Failed(val refusal: ServiceRefusal) : Execution
    }

    /**
     * Whether a run exists, as the street learned it.
     */
    private sealed interface Existence {

        /** The run exists. */
        data object Known : Existence

        /** The run does not exist, or the authority that says so could not answer. */
        data class Refused(val refusal: ServiceRefusal) : Existence
    }

    /**
     * What the failure says about itself, in one line.
     *
     * The name of the failure and its own words, without the stack: a
     * diagnostic that has to be read in a log is a diagnostic that nobody
     * reads.
     */
    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }

    /**
     * The document a durable row travels as.
     *
     * A row that decoded travels as the event it is, and a row that did not
     * travels as the refusal it is: a row the store holds is never absent
     * from the street, not even when this binary could not read it.
     */
    private fun documentOf(record: EventRecordRead): JsonObject = when (record) {
        is EventRecordRead.Decoded -> ObservationJsonLines.encodeOneObject(
            ObservationRecord.Event(record.event),
        )
        is EventRecordRead.Undecodable -> ObservationJsonLines.encodeUndecodableObject(record)
    }

    /**
     * The line that refuses with [refusal].
     */
    private fun refusedLine(refusal: ServiceRefusal): String =
        ServiceCodec.encode(ServiceResponse.Refused(ServiceProtocol.SCHEMA, refusal))

    /**
     * The refusal of the events street.
     *
     * The mapping goes through the follower's OWN translation, so the socket
     * refuses with the same words a consumer of the published follow would
     * read, and the translation is a single authority rather than two.
     */
    private fun refusalOf(
        refusal: EventRecordReadRefusal,
    ): ServiceRefusal = refusalOf(EventFollowRefusal.of(refusal))

    /**
     * The refusal of the events street.
     *
     * The mapping is TOTAL: every refusal the store can answer has a case
     * here, and the `when` says so at compile time. A refusal that had no
     * case would have to be invented at the last moment, and an invented
     * refusal is usually a generic one that says less than the refusal it
     * replaced.
     */
    private fun refusalOf(refusal: EventFollowRefusal): ServiceRefusal = when (refusal) {
        is EventFollowRefusal.UnknownRun -> ServiceRefusal.UnknownRun(refusal.runId)
        is EventFollowRefusal.CursorBeyondTail -> ServiceRefusal.CursorBeyondTail(
            runId = refusal.runId,
            requestedSequence = refusal.requestedSequence,
            tailSequence = refusal.tailSequence,
        )
        is EventFollowRefusal.StorageError -> ServiceRefusal.StorageFailed(refusal.cause)
    }

    /**
     * The refusal of the output street.
     *
     * The plane has nine refusals and no wire names for them, so the service
     * carries the plane's own word instead of inventing nine of its own. The
     * mapping is TOTAL, one case per refusal of the plane.
     */
    private fun refusalOf(refusal: OutputRefusal): ServiceRefusal = when (refusal) {
        is OutputRefusal.StorageError -> ServiceRefusal.StorageFailed(refusal.cause)
        is OutputRefusal.ForeignStream -> ServiceRefusal.OutputPlaneRefused(
            reason = "foreign-stream",
            detail = "expected ${refusal.expected}, got ${refusal.actual}",
        )
        is OutputRefusal.UnknownStream -> ServiceRefusal.OutputPlaneRefused(
            reason = "unknown-stream",
            detail = "the store has never heard of ${refusal.stream}",
        )
        is OutputRefusal.OffsetBeyondCommitted -> ServiceRefusal.OutputPlaneRefused(
            reason = "offset-beyond-committed",
            detail = "requested ${refusal.requested}, committed ${refusal.committed}",
        )
        is OutputRefusal.InvalidRange -> ServiceRefusal.OutputPlaneRefused(
            reason = "invalid-range",
            detail = "from ${refusal.from} to ${refusal.to}",
        )
        OutputRefusal.RecoveryNotCompleted -> ServiceRefusal.OutputPlaneRefused(
            reason = "recovery-not-completed",
            detail = "the store has not been recovered",
        )
        is OutputRefusal.DanglingCommit -> ServiceRefusal.OutputPlaneRefused(
            reason = "dangling-commit",
            detail = "requested end ${refusal.requestedEnd}, readable bytes ${refusal.readableBytes}",
        )
        is OutputRefusal.StreamLostRetention -> ServiceRefusal.OutputPlaneRefused(
            reason = "stream-lost-retention",
            detail = "stream ${refusal.stream}, last committed ${refusal.lastCommitted}",
        )
        is OutputRefusal.RetentionGap -> ServiceRefusal.OutputPlaneRefused(
            reason = "retention-gap",
            detail = "stream ${refusal.stream}, range ${refusal.requestedRange}, " +
                "last committed ${refusal.lastCommitted}",
        )
    }

    /**
     * Waits for the next round, and says whether the street is still alive.
     *
     * The wait is the consumer's, not the product's: the street asked for
     * nothing and there was nothing to give it. An interrupted street is a
     * street that is closing, and it says so instead of spinning.
     */
    private fun idle(): Boolean = try {
        Thread.sleep(POLL_INTERVAL_MILLIS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private companion object {

        /** How many rows one round of the events street reads. */
        private const val RECORDS_PER_READ: Int = 256

        /** How many frames one round of the output street reads. */
        private const val FRAMES_PER_READ: Int = 256

        /** How long a street waits when a round brought nothing. */
        private const val POLL_INTERVAL_MILLIS: Long = 25L
    }
}
