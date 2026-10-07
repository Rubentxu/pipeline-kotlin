package dev.rubentxu.pipeline.v2.output

/**
 * One committed byte range, attributed to the channel that produced it, in the order PipelineK
 * observed it.
 *
 * ## Four fields, and why there are no others
 *
 * ```text
 * ordinal  the order in which PipelineK published this range
 * stream   which channel-addressed stream the bytes live in
 * channel  which of the child's streams produced them
 * from,to  the committed range, half-open
 * ```
 *
 * A frame deliberately carries **no payload**. It is metadata about bytes that live exactly once in
 * a stream, and a frame that also held those bytes would be the second copy that
 * [dev.rubentxu.pipeline.v2.output.OutputRefusal] and `ADR-M1 §D2` exist to prevent.
 *
 * ## What [ordinal] does and does not mean
 *
 * It means **the order in which PipelineK observed and published these chunks.** That is a real
 * order and it is the only order this repository can speak about, because:
 *
 * - it is **not** a causal order of the pipeline. Nothing about ordinal 41 happened before ordinal
 *   40 as far as any dependency is concerned;
 * - it is **not** the order the kernel wrote the bytes. Two pumps read two pipes on two threads,
 *   and the thread that is scheduled first is not the thread whose write happened first. The ordinal
 *   is honest about being an observation order precisely because it is the order the *observer*
 *   formed;
 * - it is **not** comparable with an event sequence. [dev.rubentxu.pipeline.v2.output.OutputCursor]
 *   and an event cursor are different orders with different failure modes, and this contract does
 *   not introduce a third clock that pretends to relate them.
 *
 * ## Why [ordinal] is not an [OutputCursor]
 *
 * They are different authorities over different things, and collapsing them would destroy the
 * property that makes a tail safe. A cursor names a **byte position inside one stream** and can
 * never leave it. An ordinal names a **position in the interleaving across streams** and is
 * meaningless alone: ordinal 7 exists whether or not any reader ever holds the byte it names. A
 * reader resuming from an ordinal has to be told which stream it was on to make progress; a reader
 * resuming from a cursor knows its stream by construction.
 *
 * @property ordinal monotonic, strictly increasing, assigned at publication time
 * @property stream  the channel-addressed stream holding these bytes
 * @property channel the channel that produced them; consistent with [stream]'s shape by construction
 * @property from    first committed offset of the range
 * @property to      one past the last committed offset; `from < to`, always non-empty
 */
data class OutputFrame(
    val ordinal: Long,
    val stream: OutputStreamId,
    val channel: OutputChannel,
    val from: Long,
    val to: Long,
) {
    init {
        require(ordinal >= 0) { "ordinal must be non-negative, got $ordinal" }
        require(from >= 0) { "from must be non-negative, got $from" }
        require(to > from) { "a frame is never empty: to ($to) must exceed from ($from)" }
    }

    /** Number of committed bytes this frame names. */
    val length: Long get() = to - from
}

/**
 * The durable record of **the order PipelineK observed**, kept separate from the bytes.
 *
 * ## Two authorities, deliberately not one
 *
 * ```text
 * the bytes          the Output Plane, addressed by (stream, byte cursor)
 * the order observed the frame index, addressed by (run, ordinal)
 * ```
 *
 * Keeping them apart is what lets the two failure modes be handled separately. A reader that
 * resumes by byte cursor continues exactly where its own previous read stopped, with no
 * possibility of skipping bytes written on a channel it is not reading. A reader that wants a
 * merged view resumes by ordinal and accepts that it is asking a question only the index can
 * answer.
 *
 * Merging them — putting an ordinal in [OutputCursor], or making a frame carry a payload — would
 * produce one authority that can answer both questions and can therefore be wrong about both.
 *
 * ## The commit order this port depends on
 *
 * [append] is called **after** the bytes are committed, never before. That ordering is the whole
 * contract and it is what gives the index its two safety properties:
 *
 * 1. a committed frame never names bytes that are not committed, because the bytes were published
 *    first; and
 * 2. bytes that are committed but unframed are recoverable, because [recoverUnframedBytes] can see
 *    exactly how far each stream's committed extent runs past the last frame and close the gap.
 *
 * A writer that appended the frame first would satisfy neither, and would lose bytes on a crash
 * between the two writes in a way that no amount of recovery could reconstruct — which ordinals
 * would be, because the missing bytes have no known position in the interleaving.
 *
 * ## What this index is not
 *
 * It is not an authority over bytes. It cannot lose or corrupt them; it can only fail to *mention*
 * them, and that failure is bounded, detectable and repairable by [recoverUnframedBytes]. It is
 * also not a clock: [OutputFrame.ordinal] is an observation order and claims nothing about the
 * kernel's write order or the pipeline's causal order.
 */
interface OutputFrameIndex {

    /**
     * Records that [stream] exists and names [channel], **before any byte is written to it**.
     *
     * ## Why this has to happen first
     *
     * [recoverUnframedBytes] can only close a gap it knows exists. The byte store names a stream by
     * a directory name produced by a lossy sanitiser that is not invertible, so the store cannot
     * enumerate stream identities — and a stream that was never declared here would be invisible to
     * recovery for the whole run.
     *
     * The case that forces this is the narrowest one: a crash between a stream's **first** byte
     * commit and its **first** frame. The bytes are durable and readable, the index has never heard
     * of the stream, and nothing later can reconstruct where they belong in the interleaving.
     * Declaring up front is what makes that case recoverable instead of lost.
     *
     * Idempotent, and it costs one durable append per **stream** — not per chunk — so it does not
     * scale with output volume. Declaring a stream that never receives a byte is harmless: it holds
     * no committed extent and contributes nothing to recovery.
     */
    fun declareStream(stream: OutputStreamId, channel: OutputChannel)

    /**
     * Records that `[from, to)` of [stream] is committed, and returns the frame that carries it.
     *
     * The ordinal is assigned here, inside this call, under the index's own ordering — which is
     * what makes the stored sequence monotonic even when two channel pumps call concurrently.
     * A caller cannot and must not assign it.
     *
     * @param to the stream's committed offset after the write; `to` must exceed `from`
     * @throws IllegalArgumentException if `from` is not the offset this range begins at
     */
    fun append(stream: OutputStreamId, channel: OutputChannel, from: Long, to: Long): OutputFrame

    /**
     * Frames of one run after [afterOrdinal], ascending, at most [limit] of them.
     *
     * `limit` bounds a read rather than a write: the writer's granularity is its own business and
     * a caller that wants coarser frames merges adjacent ones itself.
     */
    fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame>

    /** The highest ordinal in this index, or `null` when it is empty. */
    fun lastOrdinal(runId: String): Long?

    /**
     * Closes the gap between committed bytes and indexed frames, and returns what it closed.
     *
     * This is the recovery half of the contract, and the reason the two authorities are separate.
     * A crash between a byte commit and its frame leaves bytes that are durable, readable, and
     * **unattributed**. They are not lost, and they are not silently absent from a merged view:
     * for every stream whose committed extent runs past its last frame, one frame is appended
     * covering `[lastIndexedEnd, committedExtent)`.
     *
     * The channel comes from the stream's own shape, so a recovered frame attributes correctly
     * without the recovery having to guess. The ordinal it receives is a *later* ordinal than any
     * frame the interrupted write would have had, because that ordinal was never assigned — which
     * is the honest answer. Those bytes are placed after everything that was published before the
     * crash, because nothing durable said where they belonged.
     *
     * Idempotent: a second call with no new committed bytes appends nothing and returns an empty
     * list, so recovery can run on every open.
     */
    fun recoverUnframedBytes(): List<OutputFrame>
}