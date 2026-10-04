package dev.rubentxu.pipeline.fabric.consumer

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventTail
import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * What `pipelinek-fabric` will actually write, written here first.
 *
 * This is the whole reason the read side of the Output Plane and the paging side of the event plane
 * were published. Before BLOCK 2 a Fabric adapter could not exist: `OutputReadPort` shared a module
 * with `SegmentOutputStore`, and `EventTail` shared one with `OperationJournal`, so consuming either
 * meant also depending on the thing that writes to it.
 *
 * Nothing in this file implements product behaviour. It implements the two PORT interfaces, because
 * a port that no outside code can implement is a port that is really a class in disguise, and
 * proving that both are implementable from an independent build is part of what is being certified.
 */

/** The closed run-outcome algebra, projected to what a build status can show. */
sealed interface RunVerdict {
    data object Passed : RunVerdict
    data object Flaky : RunVerdict
    data object Failed : RunVerdict
    data object Cancelled : RunVerdict
}

/**
 * Maps a [RunOutcome] to a verdict, exhaustively and without a fallback.
 *
 * No `else` branch, on purpose. A fourth outcome added to the published algebra must break this
 * function at compile time in a repository that is not the product, and that break is the signal
 * that the consumer's mapping is now incomplete. An `else -> Flaky` would absorb the new case and
 * report a run as flaky because nobody remembered to update a consumer.
 */
fun RunOutcome.verdict(): RunVerdict = when (this) {
    is RunOutcome.Success -> RunVerdict.Passed
    is RunOutcome.Unstable -> RunVerdict.Flaky
    is RunOutcome.Failure -> RunVerdict.Failed
    is RunOutcome.Aborted -> RunVerdict.Cancelled
}

/** The short, stable label a console or a status badge renders. */
val RunVerdict.label: String
    get() = when (this) {
        is RunVerdict.Passed -> "SUCCESS"
        is RunVerdict.Flaky -> "UNSTABLE"
        is RunVerdict.Failed -> "FAILURE"
        is RunVerdict.Cancelled -> "ABORTED"
    }

/** Why a console read produced no text. Every refusal is named, because a console must say which. */
sealed interface ConsoleReadStop {
    data class Refused(val reason: String) : ConsoleReadStop
    data class Ended(val bytesRead: Long) : ConsoleReadStop
}

/**
 * Reads a run's console transcript through the published read port, one bounded page at a time.
 *
 * The page loop is the part Fabric gets wrong most easily: it must stop on the committed end rather
 * than on an empty page, and it must not treat a refusal as "no bytes so far". Both are properties
 * of the contract, and both are decided here from the published types alone.
 *
 * @param maxBytesPerPage upper bound the caller is willing to hold in memory at once
 */
fun readConsole(
    output: OutputReadPort,
    stream: OutputStreamId,
    maxBytesPerPage: Int = 64 * 1024,
): Pair<String, ConsoleReadStop> {
    val collected = StringBuilder()
    var cursor = OutputCursor.start(stream)

    while (true) {
        when (val result = output.read(stream, cursor, maxBytesPerPage)) {
            // Total by construction: the port returns a refusal, never an exception, so "this
            // consumer could not read" is a value the loop has to interpret rather than a crash.
            is OutputReadResult.Refused -> return collected.toString() to ConsoleReadStop.Refused(explain(result.reason))

            is OutputReadResult.Page -> {
                collected.append(String(result.page.bytes, Charsets.UTF_8))
                // `next == null` is the ONLY correct way to learn the stream has reached its
                // committed end. A stream can grow after a page is produced, so a cursor alone
                // cannot distinguish "finished" from "not written yet" — which is exactly why the
                // port carries `committedEnd` and why this loop does not compare byte counts.
                val next = result.page.next ?: return collected.toString() to ConsoleReadStop.Ended(result.page.committedEnd)
                cursor = next
            }
        }
    }
}

/**
 * Names a refusal so a console can print it.
 *
 * Exhaustive over the closed ADT for the same reason [verdict] is: a new refusal case is a new
 * thing a consumer can be told, and a consumer that cannot name it will either crash at runtime or
 * collapse it into a message that hides the difference.
 */
internal fun explain(refusal: OutputRefusal): String = when (refusal) {
    is OutputRefusal.ForeignStream -> "cursor belongs to ${refusal.actual.value}, not ${refusal.expected.value}"
    is OutputRefusal.UnknownStream -> "no stream ${refusal.stream.value}"
    is OutputRefusal.OffsetBeyondCommitted -> "offset ${refusal.requested} is past the committed end ${refusal.committed}"
    is OutputRefusal.InvalidRange -> "range [${refusal.from}, ${refusal.to}) is not a forward range"
    is OutputRefusal.RecoveryNotCompleted -> "the store has not finished recovery"
    is OutputRefusal.DanglingCommit -> "${refusal.readableBytes} readable bytes back a commit claiming ${refusal.requestedEnd}"
}

/** One page of history, reduced to what a stage view renders. */
data class HistoryStep(
    val kind: String,
    val sequence: Long,
    val subject: String,
)

/**
 * Walks a run's history to its end, following the continuation cursor.
 *
 * `EventPage` carries `hasMore` separately from `nextCursor`, and the difference is the point: a
 * truncated page is not a finished one. This loop follows the cursor and reports how many pages it
 * took, which is what a reconnecting observer needs to log.
 *
 * The stop condition is the cursor running out, never an empty page. A run whose next stage has not
 * emitted yet legitimately returns zero events, and treating that as "history finished" would make
 * a live build look complete.
 */
fun readHistory(tail: EventTail, run: dev.rubentxu.pipeline.v2.domain.identity.ResourceRef): List<HistoryStep> {
    val steps = mutableListOf<HistoryStep>()
    var cursor: EventCursor? = null
    var pages = 0

    while (true) {
        val page: EventPage = tail.readAfter(run, cursor, PAGE_SIZE)
        pages++
        page.envelopes.forEach { envelope ->
            steps += HistoryStep(
                kind = envelope.kind,
                sequence = envelope.sequence,
                subject = envelope.subject.canonicalText(),
            )
        }
        if (!page.hasMore) return steps
        // Defensive on purpose: a page that claims more history and offers no cursor is a contract
        // violation, and returning what we have would report a partial read as a complete one.
        val next = page.nextCursor
            ?: error("page $pages reported hasMore=true with a null nextCursor; that is not a state the contract allows")
        cursor = next
    }
}

private const val PAGE_SIZE = 100

/** The closed event query algebra, projected to a one-line description for logs. */
fun EventQuery.describe(): String = when (this) {
    is EventQuery.All -> "all"
    is EventQuery.ByKind -> "kind=$kind"
    is EventQuery.BySource -> "source=${source.canonicalText()}"
    is EventQuery.BySubject -> "subject=${subject.canonicalText()}"
    is EventQuery.BySequenceRange -> "sequence in [$fromSequence, $toSequence]"
}
