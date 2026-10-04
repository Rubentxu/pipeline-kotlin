package dev.rubentxu.pipeline.v2.output

/**
 * Stable identity of one output stream inside the Output Plane.
 *
 * A stream is the unit of continuation: an [OutputCursor] is meaningless without the stream it
 * belongs to, which is why a cursor carries this value rather than a bare offset.
 *
 * ## What a stream is NOT
 *
 * It is **not** a run id, and it is **not** an operation id. One run contains many streams (one per
 * step that writes a transcript), and one step may be retried. The id therefore has to be assigned
 * by whoever creates the stream and stay stable for the stream's whole life, including across a
 * recovery that reattaches to it.
 *
 * ## Reference semantics for S5
 *
 * An event may carry an [OutputStreamId] to *point at* console content. It may never carry the
 * bytes themselves — that would make the event store a second holder of bytes it does not own, and
 * would recreate the double authority that [ADR-M1 D2] removed.
 *
 * @see ADR-M1 §D3
 */
@JvmInline
value class OutputStreamId(val value: String) {
    init {
        require(value.isNotBlank()) { "OutputStreamId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A position inside one output stream, expressed as **committed byte offset**.
 *
 * ## Why this is not an [dev.rubentxu.pipeline.v2.events.identity.EventCursor]
 *
 * An event cursor continues over a store-assigned event sequence. An output cursor continues over
 * byte offsets. They are different orders with different failure modes:
 *
 * - event #143 is not byte #143 of the log;
 * - a hole in an event sequence is indistinguishable from a hole that was never emitted;
 * - advancing a console read on the event sequence lets unrelated event traffic perturb it.
 *
 * [ADR-M1 D3] requires the two to be independent, so this type shares no state, no field and no
 * constructor with the event plane. The Output Plane does not depend on `pipeline-events` at all —
 * that is enforced by the module graph, not by convention.
 *
 * ## Guarantee
 *
 * [committedOffset] is **always** a committed byte offset. A reservation that has been taken but not
 * committed is invisible to every reader, so a cursor can never name a byte that does not exist yet.
 *
 * @property stream          the stream this position belongs to
 * @property committedOffset bytes committed in [stream] before this position; `0` is the start
 */
data class OutputCursor(
    val stream: OutputStreamId,
    val committedOffset: Long,
) {
    init {
        require(committedOffset >= 0) { "committedOffset must be non-negative, got $committedOffset" }
    }

    companion object {
        /** The position before the first committed byte of [stream]. */
        fun start(stream: OutputStreamId): OutputCursor = OutputCursor(stream, 0L)

        /**
         * Token prefix for a serialised output cursor.
         *
         * Deliberately **not** the event plane's `evt-cursor-v1:`. Two prefixes, two planes, and a
         * consumer that pastes an event cursor where an output cursor belongs gets a decode
         * failure rather than a plausible offset into the wrong bytes. The event sequence and the
         * committed byte offset are different orders ([ADR-M1 D3]); the wire format says so too.
         */
        const val TOKEN_PREFIX: String = "out-cursor-v1"

        /**
         * Parse a token produced by [encode]. Returns `null` for anything else, including an
         * `evt-cursor-v1:` token — see [TOKEN_PREFIX].
         */
        fun decode(token: String): OutputCursor? {
            val parts = token.split(':')
            if (parts.size != 3) return null
            if (parts[0] != TOKEN_PREFIX) return null
            val offset = parts[2].toLongOrNull() ?: return null
            if (offset < 0) return null
            val stream = runCatching {
                java.net.URLDecoder.decode(parts[1], Charsets.UTF_8)
            }.getOrNull() ?: return null
            if (stream.isBlank()) return null
            return OutputCursor(OutputStreamId(stream), offset)
        }
    }

    /**
     * Serialise for a CLI or an HTTP continuation token.
     *
     * The stream id is percent-escaped because it contains `/` as a path separator, and an
     * unescaped separator would make the token ambiguous about where the stream ends.
     */
    fun encode(): String =
        "$TOKEN_PREFIX:${java.net.URLEncoder.encode(stream.value, Charsets.UTF_8)}:$committedOffset"
}

/**
 * A bounded slice of committed bytes.
 *
 * [next] is `null` when this page reached the committed end, which is the only correct way to say
 * "no more bytes *right now*" — a stream can grow after the page is produced, so a cursor alone
 * cannot distinguish "finished" from "not written yet". The committed extent is therefore carried
 * explicitly rather than inferred.
 *
 * @property bytes    the committed bytes in `[from, from + bytes.size)`; never redacted, never re-encoded
 * @property stream   the stream these bytes came from
 * @property from     offset of the first byte in [bytes]
 * @property next     cursor to pass to resume, or `null` at the committed end
 * @property committedEnd the stream's committed extent when this page was produced
 */
data class OutputPage(
    val bytes: ByteArray,
    val stream: OutputStreamId,
    val from: Long,
    val next: OutputCursor?,
    val committedEnd: Long,
) {
    init {
        require(from >= 0) { "from must be non-negative, got $from" }
        require(committedEnd >= from) { "committedEnd ($committedEnd) must not precede from ($from)" }
        // The page may not run past what is committed, and the cursor it hands back must name the
        // byte immediately after the last one it delivered. The first version of this invariant
        // asserted `bytes.size == committedEnd - from`, which no bounded page can satisfy: a page is
        // a *slice*, and committedEnd is the extent of the whole stream, not the end of the slice.
        // A vacuous invariant is worse than none, because it reads as a guarantee.
        val end = from + bytes.size
        require(end <= committedEnd) { "page ends at $end, past the committed extent $committedEnd" }
        require(next == null || next == OutputCursor(stream, end)) {
            "next cursor $next must be the position immediately after the page, ${OutputCursor(stream, end)}"
        }
    }

    /** Offset one past the last byte in this page. */
    val end: Long get() = from + bytes.size

    override fun equals(other: Any?): Boolean =
        this === other || (other is OutputPage &&
            stream == other.stream &&
            from == other.from &&
            committedEnd == other.committedEnd &&
            next == other.next &&
            bytes.contentEquals(other.bytes))

    override fun hashCode(): Int =
        ((((stream.hashCode() * 31) + from.hashCode()) * 31 + committedEnd.hashCode()) * 31 +
            (next?.hashCode() ?: 0)) * 31 + bytes.contentHashCode()
}
