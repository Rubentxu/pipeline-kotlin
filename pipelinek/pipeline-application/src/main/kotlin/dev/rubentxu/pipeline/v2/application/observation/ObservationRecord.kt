package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress

/**
 * One thing a reader may be shown, in a closed vocabulary.
 *
 * ## Why this type exists at all
 *
 * Until OBS-D the read model spoke only [DomainEvent]. Process output was reachable, but through a
 * service with no shared vocabulary, so any consumer that wanted "what happened" together with "what
 * it printed" had to invent its own union — and every one of those unions was a place where a
 * transcript could be mistaken for a fact. `pipelinek-fabric` already forbids that in its own code
 * (`output bytes NO viajan dentro de ProjectionFact`); this makes the prohibition structural here
 * instead of a convention each adapter has to remember.
 *
 * ## The two cases are the two authorities, and neither absorbs the other
 *
 * - [Event] is a semantic fact, positioned by the event store's own sequence.
 * - [Output] is process bytes, positioned by an observation ordinal and attributed by channel.
 *
 * There is deliberately **no third case and no merged one**. A reader that wants both reads both and
 * decides how to present them; this type refuses to pretend they were one stream to begin with.
 *
 * ## What this is NOT, and the refusal is the point
 *
 * [ObservationView.FULL] asks for events and console interleaved into one document. That would need
 * a total order between an event sequence and an output ordinal, and **no such order exists in this
 * repository**: `OutputFrame.ordinal` is the order PipelineK *observed* chunks, explicitly not the
 * order the kernel wrote them and explicitly not comparable with an event sequence. Inventing a
 * merge key here would fabricate a causality the storage never recorded, so `FULL` stays refused
 * rather than approximated.
 *
 * ## Why it is not persisted
 *
 * A record is a projection assembled at read time from two durable authorities. Persisting it would
 * create a third copy of facts that already exist — the exact duplication `ADR-M1 §D2` forbids — and
 * it would freeze a merged order into storage, converting a read-side decision into a fact that
 * later corrections could not reach.
 *
 * @see ObservationQuery for how a record is selected.
 */
sealed interface ObservationRecord {

    /** A semantic fact from the Event Plane. Carries no process bytes. */
    data class Event(val event: DomainEvent) : ObservationRecord

    /**
     * A committed range of process bytes, attributed to the channel that produced it.
     *
     * @property frame the durable metadata: which stream, which channel, which byte range, which
     *   observation ordinal. It carries **no payload**, because the bytes live exactly once in the
     *   Output Plane.
     * @property bytes that range's raw bytes, exactly as committed. Present because a machine format
     *   has to be able to emit bytes that are not valid UTF-8, and a record that only carried a
     *   decoded [text] would have destroyed them before any encoder could offer a base64 fallback.
     *   It is the same window [frame] names — never a second copy of the stream, and never the whole
     *   transcript, because the reader bounds the window.
     * @property text those same bytes decoded for filtering. A VIEW over [bytes], not a replacement
     *   for them: a character split across frames decodes to U+FFFD here and is still intact in
     *   [bytes].
     */
    data class Output(
        val frame: OutputFrame,
        val bytes: ByteArray,
        val text: String,
    ) : ObservationRecord {

        /** Which of the child's streams produced these bytes. Never a guess — the frame carries it. */
        val channel: OutputChannel get() = frame.channel

        /**
         * Where these bytes live, when the stream id has the shape this producer mints.
         *
         * `null` is a real case and not a hole: a stream id is documented as opaque, so one minted
         * by a different producer — including the pre-OBS-C2 `.../transcript` shape — has no
         * recoverable operation or run. `CHANNEL` does not depend on it ([channel] rides on the
         * frame), and a query on operation or run is the thing that correctly refuses such bytes.
         */
        val address: OutputStreamAddress? get() = OutputStreamAddress.parse(frame.stream)

        /** Structural equality over a byte array needs this, or two equal windows compare unequal. */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Output) return false
            return frame == other.frame &&
                bytes.contentEquals(other.bytes) &&
                text == other.text
        }

        override fun hashCode(): Int =
            (frame.hashCode() * 31 + bytes.contentHashCode()) * 31 + text.hashCode()
    }
}

/**
 * The channel this record was produced by, or `null` when it carries none.
 *
 * `null` means the record is a semantic fact, not that its channel is unknown — those are different
 * answers, and collapsing them would let a channel filter silently keep or drop facts.
 */
fun channelCarriedBy(record: ObservationRecord): OutputChannel? = when (record) {
    is ObservationRecord.Output -> record.channel
    is ObservationRecord.Event -> null
}

/**
 * Text this record carries for `--grep`, or `null` when it carries none.
 *
 * The null case is the same one [textCarriedBy] states for events, and it means the text dimension
 * does not apply to the record rather than that the record failed it — see [ObservationQuery].
 */
fun textCarriedBy(record: ObservationRecord): String? = when (record) {
    is ObservationRecord.Event -> textCarriedBy(record.event)
    is ObservationRecord.Output -> record.text
}
