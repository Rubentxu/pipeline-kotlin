package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinId
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock

/**
 * M3 — file-backed durable store of [OutputPin] records.
 *
 * ## Layout
 *
 * ```text
 * <root>/pins.tsv     the durable pin manifest (TSV: tab-separated)
 * <root>/pins.tsv.tmp the staging file used by [save] for atomic rename
 * ```
 *
 * TSV (tab-separated values, one record per line) is the on-disk format
 * because it is simpler than JSON, has no escaping concerns, and parses
 * in one pass without an external library. The design's default cap of
 * 1024 pins per stream means the file stays small (a few hundred KiB
 * even at max), and an atomic temp-write-rename is a few-ms operation.
 *
 * ## Threading
 *
 * All public methods take a single [lock]. The lock is held for the
 * duration of the I/O so two concurrent pin ops cannot observe a torn
 * read. This mirrors the per-stream lock discipline
 * [SegmentOutputStore] uses.
 *
 * ## Compactness
 *
 * The on-disk shape omits any framing. Adding fields later is a format
 * change and will be noted in the `M1-P1` adapter rule.
 */
internal class OutputPinStore(
    private val root: Path,
    private val maxPinsPerStream: Int = 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()

    init {
        Files.createDirectories(root)
    }

    /** Pin [range] of [stream]. Returns the new pin, or `null` on refusal / errors. */
    fun pin(
        stream: OutputStreamId,
        range: LongRange,
        holder: String,
        reason: String,
        expiresAtMs: Long?,
    ): OutputPin? = withLock {
        val current = readAll()
        val activeForStream = current.filter { it.stream == stream && !it.isExpiredAt(now()) }
        if (activeForStream.size >= maxPinsPerStream) return@withLock null
        val pin = OutputPin(
            pinId = OutputPinId.newId(),
            stream = stream,
            range = range,
            holder = holder,
            reason = reason,
            createdAtMs = now(),
            expiresAtMs = expiresAtMs,
        )
        save(current + pin)
        return@withLock pin
    }

    /** Release the pin with [pinId]. Returns `true` if released, `false` if not found. */
    fun release(pinId: OutputPinId): Boolean = withLock {
        val current = readAll()
        val kept = current.filter { it.pinId != pinId }
        if (kept.size == current.size) return@withLock false
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
     *
     * This is the consult primitive the canPrune adapter uses: it iterates
     * run-scoped stream directories (whose file names ARE the safe fold of
     * their stream id) and asks the pin store for any pin that touches the
     * bytes of that directory.
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

    /** Drop every expired pin. Returns the number of pins removed. */
    fun evictExpired(): Int = withLock {
        val current = readAll()
        val kept = current.filter { !it.isExpiredAt(now()) }
        if (kept.size == current.size) return@withLock 0
        save(kept)
        current.size - kept.size
    }

    private fun readAll(): List<OutputPin> {
        val file = root.resolve(PINS_FILE)
        if (!Files.exists(file)) return emptyList()
        return try {
            val text = Files.readString(file)
            if (text.isBlank()) emptyList()
            else text.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { parseLine(it) }
                .toList()
        } catch (_: Exception) {
            // A torn or unreadable file is treated as empty. Recovery can
            // rebuild the truth; refusing would lock out the run.
            emptyList()
        }
    }

    private fun save(pins: List<OutputPin>) {
        val file = root.resolve(PINS_FILE)
        val tmp = root.resolve("$PINS_FILE.tmp")
        Files.createDirectories(root)
        val builder = StringBuilder()
        for (pin in pins) {
            builder.append(encodeLine(pin))
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

    private fun encodeLine(pin: OutputPin): String {
        // pinId\tstream\trangeFirst\trangeLast\tholder\treason\tcreatedAtMs\texpiresAtMs
        // The holder and reason are validated not to contain newlines at
        // the OutputPin init { } check, so no escaping is required.
        return buildString {
            append(pin.pinId.value).append('\t')
            append(pin.stream.value).append('\t')
            append(pin.range.first).append('\t')
            append(pin.range.last).append('\t')
            append(pin.holder).append('\t')
            append(pin.reason).append('\t')
            append(pin.createdAtMs).append('\t')
            append(pin.expiresAtMs?.toString() ?: "")
        }
    }

    private fun parseLine(line: String): OutputPin? {
        val parts = line.split('\t')
        if (parts.size != 8) return null
        return try {
            val rangeFirst = parts[2].toLong()
            val rangeLast = parts[3].toLong()
            val createdAtMs = parts[6].toLong()
            val expiresAtMs = parts[7].takeIf { it.isNotEmpty() }?.toLong()
            OutputPin(
                pinId = OutputPinId(parts[0]),
                stream = OutputStreamId(parts[1]),
                range = rangeFirst..rangeLast,
                holder = parts[4],
                reason = parts[5],
                createdAtMs = createdAtMs,
                expiresAtMs = expiresAtMs,
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        internal const val PINS_FILE = "pins.tsv"
    }
}