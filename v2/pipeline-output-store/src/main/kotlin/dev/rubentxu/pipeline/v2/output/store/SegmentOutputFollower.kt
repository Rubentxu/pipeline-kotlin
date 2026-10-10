package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputTailPort
import dev.rubentxu.pipeline.v2.output.OutputTailState
import dev.rubentxu.pipeline.v2.output.follow.OutputFollower
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowHandle
import dev.rubentxu.pipeline.v2.output.follow.OutputFollowOptions

/**
 * M1-B — production [OutputFollower] backed by the real
 * [SegmentOutputStore] + [SegmentFrameIndex] + [OutputTailPort] composition.
 *
 * ## What this is and what it is not
 *
 * The follower is a **factory**: it owns no state of its own, and each call
 * to [open] returns a fresh [SegmentOutputFollowHandle] driven by a polling
 * loop on the consumer's thread. No background thread, no coroutine, no new
 * write path. The composition rule lives in `M1_FOLLOW_DESIGN.md` §4 in
 * `docs/pipelinek-coordinated-evolution/m1-design/`.
 *
 * The constructor takes the three read-side ports the design requires
 * ([OutputReadPort], [OutputFrameIndex], [OutputTailPort]) plus a
 * [runExists] callback that decides whether a runId is known to the
 * underlying store. The callback matches the
 * `EventRecordReadPortStoreAdapter` convention from M1-A: a separation
 * between the port (the published contract) and the store-side authority
 * the implementation does not own directly. For the segment store the
 * natural implementation is `SegmentOutputStore::hasOutputFor`, which is
 * cheap (it checks the run prefix) and does not enumerate.
 *
 * ## Why a callback and not a new port
 *
 * The published contract in `:pipeline-output` does not need a
 * "does this run exist" question on the read side. The byte store already
 * answers it for retention purposes (`OutputRetentionPort.hasOutputFor`),
 * and a reader that needs it gets it through the same authority by way of
 * a constructor argument. A new port would be additive but it would also
 * be a second way to ask the store about the same fact, and two ways to
 * ask the same store the same question drift.
 *
 * ## Why this is in `:pipeline-output-store` and not in `:pipeline-output`
 *
 * `:pipeline-output` is the published contract; this implementation
 * depends on the segment-backed store and the frame index in
 * `:pipeline-output-store`. Publishing it would hand an external consumer
 * a dependency on the storage implementation, which is exactly the
 * separation ADR-M1 D2 made non-negotiable.
 */
class SegmentOutputFollower(
    private val read: OutputReadPort,
    private val frames: OutputFrameIndex,
    private val tails: OutputTailPort,
    /**
     * The store-side authority that distinguishes a run with no declared
     * streams from a run that does not exist. The handle calls this
     * BEFORE the first poll so an empty [OutputFrameIndex.streamsOfRun]
     * answer is unambiguous. The segment store supplies
     * `SegmentOutputStore::hasOutputFor`.
     */
    private val runExists: (String) -> Boolean,
) : OutputFollower {

    /**
     * Open a follow handle. The handle is `AutoCloseable`; the consumer
     * MUST close it when finished (a `use { }` block in Kotlin, or
     * try-with-resources in Java).
     *
     * @param runId the run to follow. If no stream is declared for this
     *   runId at the time of `open`, the handle emits an `Unobservable`
     *   state change (or, when the runId is unknown to the store, a
     *   `Refused(UnknownStream)`) and terminates with `Completed`.
     * @param options the polling knobs (`pageMaxBytes`, `pollIntervalMs`,
     *   `maxRecords`, `afterOrdinal`, `until`).
     */
    override fun open(runId: String, options: OutputFollowOptions): OutputFollowHandle =
        SegmentOutputFollowHandle(
            read = read,
            frames = frames,
            tails = tails,
            runExists = runExists,
            runId = runId,
            options = options,
        )

    companion object {
        /**
         * The standard 25 ms polling cadence that the in-tree `pipeline observe
         * --follow` loop has used since S5. The M1 design pins it as the
         * baseline, on the explicit reasoning that the wakeup transport
         * (`ObservationWakeup`) is not wired to a real emitter in this
         * repository (cf. `M1_FOLLOW_DESIGN.md` §6).
         */
        const val FOLLOW_IDLE_MILLIS: Long = 25L

        /**
         * Read the tail state of a stream as a [Boolean] of the same shape
         * the public port uses: `true` for [OutputTailState.Open], `false`
         * for [OutputTailState.Sealed], and `null` for "the store does not
         * know this stream" — which is the form the design names
         * [dev.rubentxu.pipeline.v2.output.OutputRefusal.UnknownStream].
         *
         * Exposed because the design uses it as part of the composition
         * contract (cf. `M1_FOLLOW_DESIGN.md` §4) and tests assert it
         * directly. Implementation is a one-liner; the constant is here
         * so a test does not have to import the [OutputTailState] ADT to
         * express the same question.
         */
        fun tailStateOrNull(tails: OutputTailPort, stream: dev.rubentxu.pipeline.v2.output.OutputStreamId): OutputTailState? =
            tails.tailState(stream)
    }
}
