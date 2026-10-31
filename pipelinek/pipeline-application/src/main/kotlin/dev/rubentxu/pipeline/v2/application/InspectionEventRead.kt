package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.application.observation.FrameIndexedObservationOutputReader
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.StreamingUtf8Decoder
import dev.rubentxu.pipeline.v2.output.store.DiagnosticText
import java.nio.file.Paths

/**
 * What a read of the durable history ANSWERED.
 *
 * Two states, because the read has exactly two outcomes: the lane answered, or it refused. An
 * empty run is NOT a refusal — it is an answer with nothing in it, and only the whole-run reports
 * decide that an empty answer is a refusal for them. The policy stays with the caller; the read
 * only reports what happened.
*/
internal sealed interface InspectionEventRead {

    /** The lane answered. The list may be empty: an empty run is an answer, not a refusal. */
    data class Admitted(val events: List<DomainEvent>) : InspectionEventRead

    /**
     * The lane refused, and [reason] is the sentence the operator reads.
     *
     * The sentence travels with the refusal instead of being written at each call site: the read
     * is the only place that knows WHY it could not answer, so it is the only place that can say
     * so once.
    */
    data class Refused(val reason: String) : InspectionEventRead
}

/**
 * The READ of an inspection: the two durable lanes `inspect` reads, and nothing else.
 *
 * ## What this owns
 *
 *  - the event lane: the admission of the durable event store and the read of one run's events;
 *  - the output lane: the per-operation console tail, read once per DISTINCT operation.
 *
 * ## What it does not own
 *
 * The selection of the rows (that is [InspectionSelection]) and the rendering of the document
 * (that is [InspectionPresentation]). Nothing here decides what is shown or how it looks: a reader
 * that also filtered would make the read's answer depend on the question, and the same run read
 * two ways would give two different answers.
*/
internal object InspectionReader {

    /**
     * Read one run's events from the durable event store.
     *
     * ## One admission, one read
     *
     * The admission is the ONE decision on whether this store may be opened, and it is asked once
     * here for every caller: the record table and the three whole-run reports all read the same
     * run through this function, so a store that cannot be opened is refused with the same words
     * everywhere and in the same order.
     *
     * The refusal message is the one the verb printed before the split: the admission's own
     * rendering, or the read failure naming the path. A caller that adds a sentence of its own
     * would make the same refusal readable two ways.
    */
    fun readRunEvents(runId: String, dbPath: String): InspectionEventRead {
        val admission = admitEventStore(dbPath, EventStorePurpose.Read)
        if (admission is EventStoreAdmission.Refused) {
            return InspectionEventRead.Refused(admission.refusal.render())
        }
        val events: List<DomainEvent> = try {
            (admission as EventStoreAdmission.Admitted).store.use { store ->
                store.eventsFor(runId).toList()
            }
        } catch (e: java.io.IOException) {
            return InspectionEventRead.Refused(
                "inspect: cannot read event store at $dbPath: ${e.message}",
            )
        }
        return InspectionEventRead.Admitted(events)
    }

    /**
     * Read each operation's tail ONCE and index it by opId.
     *
     * Bounded by the number of DISTINCT operations in the run, not by the number of rows: a
     * 40-step run reads 40 indexed tails and stamps them onto however many rows each owns.
     *
     * [scopeOpId] (`--log-tail-op`) narrows the eligible set to the operation the operator named,
     * so every other row projects silence rather than someone else's transcript. The verb has
     * already validated that a named operation belongs to the run, which is what lets
     * [OutputRefusal.UnknownStream] mean one thing here — "this operation has no output stream" —
     * instead of two.
    */
    fun readConsoleTails(
        opIds: List<OpId?>,
        runId: String,
        controlRoot: String,
        tailBytes: Long,
        scopeOpId: String?,
    ): Map<String, InspectConsoleTail> {
        if (tailBytes <= 0) return emptyMap()
        val eligible = opIds.filterNotNull().map { it.format() }.toSet()
            .let { if (scopeOpId != null) it.intersect(setOf(scopeOpId)) else it }
        if (eligible.isEmpty()) return emptyMap()
        return try {
            val plane = OutputPlaneProvider.storeForReading(Paths.get(controlRoot))
            val reader = FrameIndexedObservationOutputReader(plane.frameIndex(), plane, plane)
            eligible.associateWith { opId ->
                // Every case of the read ADT is carried through. `ObservationOutputRead` already
                // separates "the page" from "the store could not answer", and collapsing the
                // second into an empty transcript made a dangling commit indistinguishable from a
                // step that printed nothing.
                when (val r = reader.readTail(runId, opId, tailBytes)) {
                    is ObservationOutputRead.Page ->
                        InspectConsoleTail.Bytes(decodeTranscript(r.page.records))
                    is ObservationOutputRead.Refused -> when (r.reason) {
                        // The op is known to belong to this run (see [scopeOpId]), so a missing
                        // stream is a FACT about it: it printed nothing. `Bytes("")` states that;
                        // `NotRequested` would state a different fact, and `Unreadable` would
                        // claim the bytes are lost when the plane is simply empty.
                        is OutputRefusal.UnknownStream -> InspectConsoleTail.Bytes("")
                        else -> InspectConsoleTail.Unreadable(r.reason)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Previously this became `NotRequested`, which rendered as silence on every row —
            // the same "printed nothing" lie the class above was built to remove. An unreadable
            // plane is unreadable on every row, and each row now says so.
            eligible.associateWith { InspectConsoleTail.Unreadable(OutputRefusal.StorageError(DiagnosticText.from(e))) }
        }
    }

    /**
     * Decode a stream of per-frame byte buffers as one continuous UTF-8 transcript.
     *
     * `ObservationOutputReader.decodeWindow` decodes one frame at a time on purpose: the contract
     * is per-frame determinism, and a carry-over buffer would re-decode the same bytes differently
     * depending on where a reader resumed. `inspect --log-tail`, however, presents ONE transcript
     * to a human; a multi-byte codepoint straddling a frame boundary must be reassembled or the
     * tail shows two U+FFFD characters instead of the original character. That defect is named
     * for the human-facing surface; this helper is the fix.
     *
     * The decoder is the same [StreamingUtf8Decoder] the `StreamingUtf8DecoderTest` exercises; the
     * bytes are not re-decoded character-by-character — the full byte array per frame is fed
     * through the incremental decoder in the page's `records` order, oldest first, and
     * the final `String` is the assembled transcript.
    */
    fun decodeTranscript(records: List<ObservationRecord.Output>): String {
        val decoder = StreamingUtf8Decoder()
        val out = StringBuilder(records.sumOf { it.bytes.size })
        for (r in records) {
            out.append(decoder.decode(r.bytes))
        }
        return out.toString()
    }
}
