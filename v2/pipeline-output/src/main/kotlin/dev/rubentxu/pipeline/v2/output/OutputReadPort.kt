package dev.rubentxu.pipeline.v2.output

/**
 * The **read** side of the Output Plane: bounded, cursor-addressed, never event-addressed.
 *
 * Every method is total — it returns an [OutputReadResult], never an exception for a refusal — so a
 * caller cannot accidentally treat "refused" as "no bytes".
 *
 * ## What a reader may see
 *
 * This port is the whole of the published Output Plane. The write side ([`OutputAppendPort`]) and
 * the recovery entry point live in `:pipeline-output-store` and are not part of this contract,
 * because a reader that could also write would be a second potential authority over the bytes
 * [ADR-M1 §D2] gives to exactly one plane. An external consumer (Fabric's console reader) reads
 * committed bytes; it does not reserve, commit, recover or prune.
 *
 * @see ADR-M1 §D3
 */
interface OutputReadPort {

    /** Committed extent of [stream] in bytes, or `null` if the stream is unknown. */
    fun committedExtent(stream: OutputStreamId): Long?

    /**
     * Reads at most [maxBytes] committed bytes of [stream], starting at [cursor]'s offset.
     *
     * The read is addressed to a **stream as well as** a cursor, and that is deliberate: a cursor
     * from another stream is a real mistake a consumer makes when it holds one output handle and
     * asks for another. Addressing by cursor alone would make the mistake undetectable, so the
     * refusal would have nowhere to live and [OutputRefusal.ForeignStream] would be dead code.
     *
     * @param maxBytes upper bound on the returned page; a store may return fewer
     */
    fun read(stream: OutputStreamId, cursor: OutputCursor, maxBytes: Int): OutputReadResult

    /**
     * Reads exactly the committed bytes in `[from, to)` of [stream].
     *
     * The range is a contractual property, not a convenience: `readRange(s, o, o + n)` must equal
     * the first `n` bytes of `readRange(s, o, committed)`, for every `n`. That is what makes a
     * consumer able to address a byte without reading the ones before it.
     */
    fun readRange(stream: OutputStreamId, from: Long, to: Long): OutputReadResult
}
