package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinId
import dev.rubentxu.pipeline.v2.output.OutputStreamId

/**
 * The parsed content of the pin manifest.
 *
 * ## Why this is a sealed pair
 *
 * The manifest used to be read by a function that returned `emptyList()` when the file could not be
 * read. That is a **fail-open** reading of a durable file: an unreadable manifest became "nobody
 * holds anything", and the retention side, which consults the pins before deleting, would then
 * delete bytes somebody was holding.
 *
 * An unreadable manifest is [Unreadable], and [Unreadable] is never read as "empty". The caller that
 * cannot tell them apart is the caller that deletes.
 */
internal sealed interface PinManifest {

    /** The manifest was read, and these are the pins it records. */
    data class Readable(val pins: List<OutputPin>) : PinManifest

    /** The manifest could not be read; [cause] says why, in one bounded line. */
    data class Unreadable(val cause: String) : PinManifest
}

/**
 * The failure that a substrate raises when its manifest cannot be read.
 *
 * It exists so that the layers above can say "the substrate failed" instead of "there were no
 * pins": the difference between the two is the difference between a correct refusal and a deletion.
 */
internal class PinManifestUnreadable(cause: String) : RuntimeException(cause)

/**
 * Parse the manifest's text.
 *
 * A malformed LINE makes the whole manifest unreadable rather than being skipped: a line that does
 * not parse is a manifest whose remaining lines cannot be trusted either, and a partial answer to
 * "who holds this" is a partial deletion.
 */
internal fun parsePinManifest(text: String): PinManifest {
    if (text.isBlank()) return PinManifest.Readable(emptyList())
    val pins = mutableListOf<OutputPin>()
    var lineNumber = 0
    for (line in text.lineSequence()) {
        lineNumber++
        if (line.isBlank()) continue
        val pin = parsePinLine(line)
            ?: return PinManifest.Unreadable("malformed pin manifest line $lineNumber")
        pins.add(pin)
    }
    return PinManifest.Readable(pins)
}

/**
 * Parse one manifest line, or `null` when it does not describe a pin.
 *
 * The failures are the ones a durable file can actually have: the wrong number of fields, a field
 * that is not a number, and a field the [OutputPin] itself refuses. All of them are the same fact —
 * the line does not describe a pin — and all of them are reported the same way.
 */
private fun parsePinLine(line: String): OutputPin? {
    val parts = line.split('\t')
    if (parts.size != PIN_FIELD_COUNT) return null
    return try {
        OutputPin(
            pinId = OutputPinId(parts[0]),
            stream = OutputStreamId(parts[1]),
            range = parts[2].toLong()..parts[3].toLong(),
            holder = parts[4],
            reason = parts[5],
            createdAtMs = parts[6].toLong(),
            expiresAtMs = parts[7].takeIf { it.isNotEmpty() }?.toLong(),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Encode one pin as a manifest line, or `null` when it cannot be encoded.
 *
 * The manifest is TAB-separated, so a TAB inside the stream id, the holder or the reason would
 * make the line describe something else entirely. [OutputPin] already refuses NEWLINES in the
 * holder and the reason; the TAB is the remaining hole, and it is closed here.
 */
internal fun encodePinLine(pin: OutputPin): String? {
    if ('\t' in pin.stream.value) return null
    if ('\t' in pin.holder) return null
    if ('\t' in pin.reason) return null
    return buildString {
        append(pin.pinId.value).append('\t')
        append(pin.stream.value).append('\t')
        append(pin.range.first).append('\t')
        append(pin.range.last).append('\t')
        append(pin.holder).append('\t')
        append(pin.reason).append('\t')
        append(pin.createdAtMs).append('\t')
        append(pin.expiresAtMs?.toString().orEmpty())
    }
}

/** The number of TAB-separated fields a manifest line carries. */
private const val PIN_FIELD_COUNT: Int = 8
