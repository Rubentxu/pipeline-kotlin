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

    /**
     * M3 — read exactly the committed bytes in `[from, to)` of [stream]
     * AND a deterministic content hash of those bytes.
     *
     * Contractual properties:
     *
     *  - `readRangeDigested(s, a, b).digest` is a deterministic function
     *    of the committed bytes in `[a, b)` — same bytes, same digest;
     *    different bytes, different digest.
     *  - `readRangeDigested(s, a, b).page.bytes` equals the bytes
     *    returned by `readRange(s, a, b)` for the same range (no
     *    redactions, no re-encodings, no padding).
     *  - A read that crosses a pruned range, or that lands in a
     *    corrupted region, refuses closed with the sealed cases from
     *    `OutputRefusal` — `RetentionGap`, `Corrupt`, `Unavailable`,
     *    `RangeLostRetention`. A read NEVER returns a Page with an empty digest.
     *
     * The default implementation composes [readRange] + [OutputDigest.sha256Of];
     * concrete adapters in `:pipeline-output-store` override this for a single
     * pass that does not double the I/O.
     *
     * @param stream the stream to read
     * @param from   inclusive start offset
     * @param to     exclusive end offset; `to > from`
     */
    fun readRangeDigested(
        stream: OutputStreamId,
        from: Long,
        to: Long,
    ): OutputReadDigestedResult {
        // Default implementation composes readRange + OutputDigest.sha256Of.
        val result = readRange(stream, from, to)
        return when (result) {
            is OutputReadResult.Page -> OutputReadDigestedResult.Digested(
                page = result.page,
                digest = OutputDigest.sha256Of(result.page.bytes),
            )
            is OutputReadResult.Refused -> OutputReadDigestedResult.Refused(
                reason = result.reason,
            )
        }
    }
}
