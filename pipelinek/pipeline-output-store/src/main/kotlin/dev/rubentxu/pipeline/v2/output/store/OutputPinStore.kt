package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinId
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock

/**
 * File-backed durable store of [OutputPin] records.
 * ## Layout
 * ```text
 * <root>/pins.tsv     the durable pin manifest (TSV: tab-separated)
 * <root>/pins.tsv.tmp the staging file used by [save] for atomic rename
 * ```
 * TSV (tab-separated values, one record per line) is the on-disk format
 * because it is simpler than JSON, has no escaping concerns, and parses
 * in one pass without an external library. The design's default cap of
 * 1024 pins per stream means the file stays small (a few hundred KiB
 * even at max), and an atomic temp-write-rename is a few-ms operation.
 * ## Threading
 * All public methods take a single [lock]. The lock is held for the
 * duration of the I/O so two concurrent pin ops cannot observe a torn
 * read. This mirrors the per-stream lock discipline
 * [SegmentOutputStore] uses.
 * ## Compactness
 * The on-disk shape omits any framing. Adding fields later is a format
 * change and will be noted in the adapter rule
 * ## An unreadable manifest is NOT an empty manifest
 * The manifest is the record of who holds the bytes. Reading it as "empty"
 * when it cannot be read would tell the retention side that nobody holds
 * anything, and the retention side deletes what nobody holds — so a torn
 * file would turn into a deletion. The store therefore raises
 * [PinManifestUnreadable] and lets the layer above decide what an
 * unreadable substrate means. It never answers "empty" for a file it could
 * not read.
 * ## Construction touches no filesystem
 * The directory is created when there is something to write, not when the
 * store is constructed: a store that exists to be asked whether anybody
 * holds a directory must not create that directory by being asked.
*/
internal class OutputPinStore(
    private val root: Path,
    private val maxPinsPerStream: Int = 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()

    /**
     * Pin [range] of [stream].
     *
     * The refusal is a closed set rather than a `null`, because the three
     * refusals are different facts and the caller has to route on them.
     */
    fun pin(
        stream: OutputStreamId,
        range: LongRange,
        holder: String,
        reason: String,
        expiresAtMs: Long?,
    ): PinOutcome = withLock {
        val current = readAll()
        val activeForStream = current.filter { it.stream == stream && !it.isExpiredAt(now()) }
        if (activeForStream.size >= maxPinsPerStream) {
            return@withLock PinOutcome.TooManyPins(activeForStream.size)
        }
        val pin = OutputPin(
            pinId = OutputPinId.newId(),
            stream = stream,
            range = range,
            holder = holder,
            reason = reason,
            createdAtMs = now(),
            expiresAtMs = expiresAtMs,
        )
        // The pin is encoded once to prove it CAN be encoded, and the manifest writes it again
        // through the same encoder: two calls, one definition of the line format.
        encodePinLine(pin)
            ?: return@withLock PinOutcome.Unencodable("the pin's identity, holder or reason carries a tab")
        save(current + pin)
        PinOutcome.Pinned(pin)
    }

    /** Release the pin with [pinId]. Returns `true` if released, `false` if not found. */
    fun release(pinId: OutputPinId): Boolean = withLock {
        val current = readAll()
        val wallClock = now()
        // Treat expired pins as already released — the user observable behaviour is the same.
        val target = current.firstOrNull { it.pinId == pinId } ?: return@withLock false
        if (target.isExpiredAt(wallClock)) return@withLock false
        val kept = current.filter { it.pinId != pinId }
        save(kept)
        true
    }

    /** All pins currently recorded for [stream], optionally restricted to [range]. */
    fun pinsOf(stream: OutputStreamId, range: LongRange? = null): List<OutputPin> = withLock {
        readAll().asSequence()
            .filter { it.stream == stream && !it.isExpiredAt(now()) }
            .filter { range == null || rangesOverlap(it.range, range) }
            .sortedBy { it.pinId.value }
            .toList()
    }

    /**
     * Pins whose [OutputStreamId] folds to [safeName] (the on-disk directory
     * name).
     * This is the consult primitive the retention side uses: it iterates
     * run-scoped stream directories (whose file names ARE the safe fold of
     * their stream id) and asks the pin store for any pin that touches the
     * bytes of that directory.
     *
     * The fold is applied in the only direction that is exact — from the
     * stream to its name — so the answer names the real stream and no id has
     * to be reconstructed from a directory name.
     */
    fun pinsForSafeName(safeName: String): List<OutputPin> = withLock {
        readAll().asSequence()
            .filter { !it.isExpiredAt(now()) }
            .filter { safeStreamName(it.stream.value) == safeName }
            .sortedBy { it.pinId.value }
            .toList()
    }

    /** Whether ANY active pin covers [stream]'s byte at [offset]. */
    fun isPinned(stream: OutputStreamId, offset: Long): Boolean = withLock {
        readAll().any { it.stream == stream && !it.isExpiredAt(now()) && offset in it.range }
    }

    /**
     * Every pin the manifest records.
     *
     * @throws PinManifestUnreadable when the manifest exists and cannot be read.
     */
    private fun readAll(): List<OutputPin> {
        val file = root.resolve(PINS_FILE)
        if (!Files.exists(file)) return emptyList()
        val text = try {
            Files.readString(file)
        } catch (e: Exception) {
            throw PinManifestUnreadable("the pin manifest could not be read: ${e.shortDiagnostic()}")
        }
        return when (val manifest = parsePinManifest(text)) {
            is PinManifest.Readable -> manifest.pins
            is PinManifest.Unreadable -> throw PinManifestUnreadable(manifest.cause)
        }
    }

    /**
     * Write the manifest, atomically.
     *
     * Every pin is encoded here, once, by the same encoder that parsed them:
     * the pins already on disk were parsed FROM lines, so they are encodable by
     * construction, and the pin being added was checked before it reached
     * this point. A pin that nevertheless cannot be encoded is a defect in the
     * caller, not a condition to write a partial manifest around.
     */
    private fun save(pins: List<OutputPin>) {
        val file = root.resolve(PINS_FILE)
        val tmp = root.resolve("$PINS_FILE.tmp")
        Files.createDirectories(root)
        val builder = StringBuilder()
        for (pin in pins) {
            val line = checkNotNull(encodePinLine(pin)) {
                "a recorded pin cannot be encoded: ${pin.pinId.value}"
            }
            builder.append(line)
            builder.append('\n')
        }
        Files.writeString(tmp, builder.toString())
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun rangesOverlap(a: LongRange, b: LongRange): Boolean =
        a.first <= b.last && b.first <= a.last

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        internal const val PINS_FILE = "pins.tsv"
    }
}

/**
 * What [OutputPinStore.pin] did.
 *
 * The refusal set is closed: the caller routes on it, so a new refusal is a
 * compile error at every `when` site rather than a silent `null`.
 */
internal sealed interface PinOutcome {

    /** The pin was recorded. */
    data class Pinned(val pin: OutputPin) : PinOutcome

    /** The stream already holds the maximum number of active pins. */
    data class TooManyPins(val active: Int) : PinOutcome

    /** The pin cannot be written to the manifest, so it was not written. */
    data class Unencodable(val cause: String) : PinOutcome
}
