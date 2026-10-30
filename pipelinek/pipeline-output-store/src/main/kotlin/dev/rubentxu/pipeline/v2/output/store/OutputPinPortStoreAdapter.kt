package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinId
import dev.rubentxu.pipeline.v2.output.OutputPinPort
import dev.rubentxu.pipeline.v2.output.OutputPinResult
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.PinRefusal
import dev.rubentxu.pipeline.v2.output.PinReleaseOutcome
import java.nio.file.Path

/**
 * The production [OutputPinPort] AND the production [DirectoryPinConsult], backed by
 * [OutputPinStore].
 *
 * ## Why one class answers two questions
 *
 * The published port answers by **stream**; the byte store reaches a stream by its **directory**.
 * Those are different vocabularies, and the previous composition bridged them by *guessing* the
 * stream back from the directory name — a fold that is lossy in general, and a guess is not an
 * answer a deletion can be authorised on. So this class answers both questions directly, from the
 * same manifest, with one encoder and one parser:
 * - [pinsOf] and [isPinned] answer by stream, for the published port.
 * - [pinsOfDirectory] answers by directory NAME, for the retention side.
 *
 * The manifest is the single record of who holds the bytes, so there is one authority and not two
 * that can disagree about the same file.
 *
 * ## What the adapter refuses
 * - A pin request against a stream the store has never opened
 *   (`PinRefusal.UnknownStream`).
 * - A pin request whose range starts past the stream's committed extent
 *   (`PinRefusal.RangeBeyondCommitted`).
 * - A pin request beyond [OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM]
 *   active pins for the same stream (`PinRefusal.TooManyPins`).
 * Expired pins are NOT a refusal — an expired pin is released on read, not refused.
 *
 * ## A failure to read is NOT an absence of pins
 *
 * [pinsOf] and [isPinned] let the substrate's failure travel to the caller instead of swallowing
 * it. The previous shape answered `emptyList()` and `false` when the manifest could not be read,
 * and that is a **fail-open** reading of a durable file: "nobody holds anything" is exactly the
 * answer the retention side acts on, and it deletes bytes somebody is holding.
 *
 * The caller that cannot tell "there are no pins" from "I could not find out" is the caller that
 * deletes, so the two answers are kept apart: the port methods propagate, and the directory
 * consult answers [DirectoryPinAnswer.Unavailable].
 *
 * ## Threading
 *
 * The internal [OutputPinStore] takes its own lock; the adapter holds no lock of its own. Reads are
 * O(active-pins), and the O(1) target for [isPinned] is approximated by the linear scan being
 * O(constant) for the default cap.
 */
class OutputPinPortStoreAdapter(
    root: Path,
    /**
     * The committed extent of a stream, or `null` when the stream has never been opened.
     *
     * A function rather than an [dev.rubentxu.pipeline.v2.output.OutputReadPort] because the
     * adapter needs exactly one fact, and because the reader that supplies it may not exist yet
     * when the adapter is built: a function is asked when the answer is needed.
     */
    private val committedExtent: (OutputStreamId) -> Long?,
    private val maxPinsPerStream: Int = OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM,
    now: () -> Long = System::currentTimeMillis,
) : OutputPinPort, DirectoryPinConsult {

    private val store: OutputPinStore = OutputPinStore(
        root = root,
        maxPinsPerStream = maxPinsPerStream,
        now = now,
    )

    /**
     * Pin [range] of [stream] against retention pruning. The pin
     * survives any subsequent `OutputRetentionPort.prune(intent)` UNLESS
     * the pin's `holder` explicitly `release`s it OR `expiresAtMs` passes.
     */
    override fun pin(
        stream: OutputStreamId,
        range: LongRange,
        holder: String,
        reason: String,
        expiresAtMs: Long?,
    ): OutputPinResult {
        if (range.first < 0) {
            return OutputPinResult.Refused(
                PinRefusal.RangeBeyondCommitted(stream, range, 0L),
            )
        }
        // ONE read of the committed extent, and its answer is kept: asking twice could answer
        // twice, and a pin authorised against a committed extent that has since moved is a pin
        // over bytes nobody promised.
        val committed = committedExtent(stream)
            ?: return OutputPinResult.Refused(PinRefusal.UnknownStream(stream))
        if (range.first > committed) {
            return OutputPinResult.Refused(
                PinRefusal.RangeBeyondCommitted(stream, range, committed),
            )
        }
        return try {
            when (val outcome = store.pin(stream, range, holder, reason, expiresAtMs)) {
                is PinOutcome.Pinned ->
                    OutputPinResult.Pinned(pinId = outcome.pin.pinId, expiresAtMs = outcome.pin.expiresAtMs)
                is PinOutcome.TooManyPins ->
                    OutputPinResult.Refused(
                        PinRefusal.TooManyPins(
                            stream = stream,
                            limit = maxPinsPerStream,
                            active = outcome.active,
                        ),
                    )
                is PinOutcome.Unencodable ->
                    OutputPinResult.Refused(PinRefusal.StorageError(outcome.cause))
            }
        } catch (t: Throwable) {
            OutputPinResult.Refused(PinRefusal.StorageError(t.shortDiagnostic()))
        }
    }

    /**
     * Release the pin identified by [pinId]. Idempotent: a second
     * call returns [PinReleaseOutcome.AlreadyReleased].
     */
    override fun release(pinId: OutputPinId): PinReleaseOutcome = try {
        if (store.release(pinId)) {
            PinReleaseOutcome.Released(pinId)
        } else {
            PinReleaseOutcome.AlreadyReleased(pinId)
        }
    } catch (t: Throwable) {
        PinReleaseOutcome.StorageError(t.shortDiagnostic())
    }

    /**
     * Active pins for [stream], optionally restricted to [range].
     *
     * The substrate's failure is NOT swallowed: it travels to the caller, because "there are no
     * pins" and "I could not read the pins" are different facts and only the second one is safe to
     * answer for the retention side.
     */
    override fun pinsOf(
        stream: OutputStreamId,
        range: LongRange?,
    ): List<OutputPin> = store.pinsOf(stream, range)

    /**
     * Whether ANY active pin covers [stream]'s byte at [offset].
     *
     * A failure to read the manifest travels to the caller for the same reason as in [pinsOf]: a
     * `false` here would tell the retention side that nobody holds the byte.
     */
    override fun isPinned(stream: OutputStreamId, offset: Long): Boolean =
        store.isPinned(stream, offset)

    /**
     * Active pins whose stream id folds to [directoryName] — the on-disk directory name.
     *
     * The fold is applied in the only direction that is exact — from the stream to its name — so
     * the answer names the REAL stream and no id has to be reconstructed from a directory name.
     * The manifest that records the pins is the same one [pinsOf] reads, so the two answers cannot
     * disagree about the same file.
     */
    override fun pinsOfDirectory(directoryName: String): DirectoryPinAnswer = try {
        DirectoryPinAnswer.Answered(store.pinsForSafeName(directoryName))
    } catch (t: Throwable) {
        DirectoryPinAnswer.Unavailable(t.shortDiagnostic())
    }

    companion object {
        /**
         * The name of the directory that holds a root's pin manifest, relative to the root.
         *
         * The name lives HERE, with the adapter that owns the manifest, so that the composition
         * that owns the ROOT does not have to spell the layout of a substrate it does not own: a
         * second spelling of the same path is a second place to change it.
         */
        const val PINS_DIR: String = "pins"
    }
}
