package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinId
import dev.rubentxu.pipeline.v2.output.OutputPinPort
import dev.rubentxu.pipeline.v2.output.OutputPinResult
import dev.rubentxu.pipeline.v2.output.OutputReadPort
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.PinRefusal
import dev.rubentxu.pipeline.v2.output.PinReleaseOutcome
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M3 — production [OutputPinPort] backed by [OutputPinStore].
 *
 * The adapter is a thin composition: it does NOT introduce a new
 * authority, a new lock, a new file format, or a new persistence
 * mechanism. It translates the port's sealed ADTs onto the store's
 * simpler CRUD.
 *
 * ## What the adapter refuses
 *
 * - A pin request against a stream the store has never opened
 *   (`PinRefusal.UnknownStream`).
 * - A pin request whose range starts past the stream's committed extent
 *   (`PinRefusal.RangeBeyondCommitted`).
 * - A pin request beyond [OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM]
 *   active pins for the same stream (`PinRefusal.TooManyPins`).
 *
 * Expired pins are NOT a refusal — they are released on read so a
 * consumer that holds an expired pin does not get blocked. The audit
 * §B.3's "expired pin is RELEASED, not refused" rule.
 *
 * ## Threading
 *
 * The internal [OutputPinStore] takes its own lock; the adapter holds
 * no lock of its own. Reads ([pinsOf], [isPinned]) are O(active-pins)
 * and O(active-pins), respectively; the audit's O(1) target for
 * [isPinned] is approximated by the linear scan being O(constant) for
 * the default cap.
 */
class OutputPinPortStoreAdapter(
    private val root: Path,
    private val reads: OutputReadPort,
    private val maxPinsPerStream: Int = OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM,
    private val now: () -> Long = System::currentTimeMillis,
) : OutputPinPort, AutoCloseable {

    private val closed = AtomicBoolean(false)
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
        if (closed.get()) {
            return OutputPinResult.Refused(PinRefusal.StorageError("pin store is closed"))
        }
        if (range.first < 0) {
            return OutputPinResult.Refused(
                PinRefusal.RangeBeyondCommitted(stream, range, 0L),
            )
        }
        if (reads.committedExtent(stream) == null) {
            return OutputPinResult.Refused(PinRefusal.UnknownStream(stream))
        }
        val committed = reads.committedExtent(stream)!!
        if (range.first > committed) {
            return OutputPinResult.Refused(
                PinRefusal.RangeBeyondCommitted(stream, range, committed),
            )
        }
        return try {
            val pin = store.pin(stream, range, holder, reason, expiresAtMs)
                ?: return OutputPinResult.Refused(
                    PinRefusal.TooManyPins(
                        stream = stream,
                        limit = maxPinsPerStream,
                        active = store.pinsOf(stream).size,
                    ),
                )
            OutputPinResult.Pinned(pinId = pin.pinId, expiresAtMs = pin.expiresAtMs)
        } catch (t: Throwable) {
            OutputPinResult.Refused(PinRefusal.StorageError(t.shortDiagnostic()))
        }
    }

    /**
     * Release the pin identified by [pinId]. Idempotent: a second
     * call returns [PinReleaseOutcome.AlreadyReleased].
     */
    override fun release(pinId: OutputPinId): PinReleaseOutcome {
        if (closed.get()) {
            return PinReleaseOutcome.StorageError("pin store is closed")
        }
        return try {
            val firstCall = store.release(pinId)
            if (firstCall) PinReleaseOutcome.Released(pinId)
            else PinReleaseOutcome.AlreadyReleased(pinId)
        } catch (t: Throwable) {
            PinReleaseOutcome.StorageError(t.shortDiagnostic())
        }
    }

    /** Active pins for [stream], optionally restricted to [range]. */
    override fun pinsOf(
        stream: OutputStreamId,
        range: LongRange?,
    ): List<OutputPin> {
        if (closed.get()) return emptyList()
        return try {
            store.pinsOf(stream, range)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Active pins whose stream id folds to [safeName] — the on-disk
     * directory name. Used by the canPrune consult to enumerate a run's
     * streams by directory rather than by id reconstruction.
     */
    fun pinsForSafeName(safeName: String): List<OutputPin> {
        if (closed.get()) return emptyList()
        return try {
            store.pinsForSafeName(safeName)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Whether ANY active pin covers [stream]'s byte at [offset]. */
    override fun isPinned(stream: OutputStreamId, offset: Long): Boolean {
        if (closed.get()) return false
        return try {
            store.isPinned(stream, offset)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Mark the adapter as closed; subsequent calls return refusal cases.
     *
     * The pin store file is not deleted — durability outlives revocation of
     * the live adapter.
     */
    override fun close() {
        closed.set(true)
    }

    /** Whether the adapter is closed — used by canPrune tests. */
    fun isClosed(): Boolean = closed.get()

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }
}