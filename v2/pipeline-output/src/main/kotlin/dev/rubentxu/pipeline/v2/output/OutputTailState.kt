package dev.rubentxu.pipeline.v2.output

/**
 * Whether more bytes can still arrive on a stream.
 *
 * ## The gap this closes
 *
 * `OutputPage.next == null` means **"you have reached the committed extent right now"**. It is not
 * a statement about the future, and the difference is not academic:
 *
 * ```text
 * a step printing slowly   -> next == null, and 200 KiB arrive one minute later
 * a step that has finished -> next == null, and nothing will ever arrive
 * ```
 *
 * A `--follow` consumer that reads `next == null` as an end of stream stops tailing a process that
 * is still running. A consumer that reads it as "not yet" cannot distinguish a quiet step from a
 * finished one and polls forever. Both failures come from the same missing third answer, and this
 * type is that answer.
 *
 * ## Why this carries an offset and not an outcome
 *
 * [Open] and [Sealed] say only whether the byte run can grow. They deliberately do **not** carry
 * `SUCCESS`, `FAILURE` or `UNSTABLE`: those belong to the run and event planes, which already know
 * how the run finished. Putting an outcome here would make the Output Plane a second authority over
 * execution results — the exact duplication ADR-M1 §D2 forbids — and it would force the store to
 * learn what a step *meant* rather than what it *wrote*.
 *
 * A reader that wants both joins the two authorities: this tells it whether to keep tailing, the
 * event plane tells it why the run ended.
 *
 * ## Why SEALED is durable rather than a live view of a writer
 *
 * A stream is sealed by a **fact that was recorded**, not by asking whether a writer is currently
 * attached. That distinction is what makes the answer survive the crash that produced it: a
 * consumer in a fresh JVM must be able to learn "this stream is finished" without the producer
 * being alive to tell it. An implementation that answered from live writer state would report
 * `Open` forever after a crash, and a consumer would tail a dead run indefinitely.
 *
 * @property committedEnd the stream's committed extent when this state was observed
 */
sealed interface OutputTailState {

    /**
     * More bytes may still arrive.
     *
     * Says nothing about whether a producer is currently attached: a stream whose process died is
     * still [Open] until something records its end, because "nobody is writing" is not the same fact
     * as "nothing will ever be written".
     */
    data class Open(val committedEnd: Long) : OutputTailState {
        init {
            require(committedEnd >= 0) { "committedEnd must be non-negative, got $committedEnd" }
        }
    }

    /**
     * No further bytes will be written to this stream.
     *
     * [finalEnd] is the extent the stream had when it was sealed, which equals its committed extent
     * afterwards: a sealed stream cannot grow, so the two are the same number by construction. It is
     * carried explicitly so a reader never has to ask a second question to know where the tail is.
     */
    data class Sealed(val finalEnd: Long) : OutputTailState {
        init {
            require(finalEnd >= 0) { "finalEnd must be non-negative, got $finalEnd" }
        }
    }
}

/**
 * The **tail** question, kept apart from [OutputReadPort].
 *
 * ## Why it is a new port and not a new method on [OutputReadPort]
 *
 * `OutputReadPort` is published and already compiled against by an external consumer, so widening
 * it is a breaking change to a contract that works. This is the additive shape ADR-M1 §D3 allows:
 * a consumer that only wants bytes keeps implementing exactly what it implements, and one that can
 * use the tail asks for [OutputTailPort] explicitly. Detection is therefore a compile-time choice
 * rather than a capability probe on a hot path.
 *
 * `null` means the stream is unknown to this store, which is deliberately distinct from
 * [OutputTailState.Open]: "I have never heard of it" and "I know it can still grow" are different
 * answers, and collapsing them would let a mis-aimed reader tail a stream that does not exist.
 */
interface OutputTailPort {

    /**
     * The tail state of [stream], or `null` when this store does not know the stream.
     *
     * Total: every refusal the read side can produce is folded into `null` here, because "cannot say"
     * is one answer and this method has no other way to express it. A caller that needs the difference
     * between "unrecovered" and "absent" asks [OutputReadPort] for bytes and reads its refusal.
     */
    fun tailState(stream: OutputStreamId): OutputTailState?
}