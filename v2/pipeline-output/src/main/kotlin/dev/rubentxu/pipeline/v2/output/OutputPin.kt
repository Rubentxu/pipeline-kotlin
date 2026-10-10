package dev.rubentxu.pipeline.v2.output

import java.util.UUID

/**
 * M3 — opaque identifier for an active pin.
 *
 * The id is assigned by [OutputPinPort.pin] and must be carried
 * through `release(pinId)` and `pinsOf(stream)` filters. The
 * implementation chooses the format (UUID v4 is the recommended
 * baseline — collision-resistant, opaque, no PK-internal encoding).
 */
@JvmInline
value class OutputPinId(val value: String) {
    init {
        require(value.isNotBlank()) { "OutputPinId must not be blank" }
    }
    override fun toString(): String = value

    companion object {
        /** Mint a new UUID-based pin id. */
        fun newId(): OutputPinId = OutputPinId(UUID.randomUUID().toString())
    }
}

/**
 * M3 — the durable record of one active pin.
 *
 * @property pinId       the opaque pin id (assigned at pin time)
 * @property stream      the stream whose bytes are held
 * @property range       the half-open byte range held; `range.last >=
 *                       range.first`
 * @property holder      the durable identity that asked for the pin;
 *                       `String` mirrors the existing `RunOwnerId`
 *                       discipline
 * @property reason      a bounded diagnostic (PIN_REASON_MAX_LEN chars)
 * @property createdAtMs epoch-ms when the pin was created
 * @property expiresAtMs epoch-ms when the pin expires, or `null` for
 *                       "no expiry"
 */
data class OutputPin(
    val pinId: OutputPinId,
    val stream: OutputStreamId,
    val range: LongRange,
    val holder: String,
    val reason: String,
    val createdAtMs: Long,
    val expiresAtMs: Long?,
) {
    init {
        require(range.first >= 0) { "range.first must be non-negative, got ${range.first}" }
        require(range.last >= range.first) {
            "range.last (${range.last}) must be >= range.first (${range.first})"
        }
        require(holder.length <= OutputPin.PIN_HOLDER_MAX_LEN) {
            "OutputPin.holder must be <= ${OutputPin.PIN_HOLDER_MAX_LEN} chars, got ${holder.length}"
        }
        require(reason.length <= OutputPin.PIN_REASON_MAX_LEN) {
            "OutputPin.reason must be <= ${OutputPin.PIN_REASON_MAX_LEN} chars, got ${reason.length}"
        }
        require('\n' !in reason) { "OutputPin.reason must not contain newlines" }
        require('\n' !in holder) { "OutputPin.holder must not contain newlines" }
    }

    /** Whether this pin has expired given the supplied wall-clock epoch-ms. */
    fun isExpiredAt(epochMs: Long): Boolean = expiresAtMs?.let { it <= epochMs } ?: false

    companion object {
        const val PIN_HOLDER_MAX_LEN: Int = 256
        const val PIN_REASON_MAX_LEN: Int = 256
    }
}

/**
 * M3 — closed result of [OutputPinPort.pin].
 *
 * Two cases: pinned (with the new pin id) OR refused (with a closed
 * [PinRefusal] reason). Adding a new refusal mode is a compile error
 * at every `when` site.
 */
sealed interface OutputPinResult {

    /** The pin was created; [pinId] is the opaque id, [expiresAtMs] is the effective expiry. */
    data class Pinned(
        val pinId: OutputPinId,
        val expiresAtMs: Long?,
    ) : OutputPinResult

    /** The pin was refused; [reason] names the closed cause. */
    data class Refused(val reason: PinRefusal) : OutputPinResult
}

/**
 * M3 — closed ADT of reasons [OutputPinPort.pin] refused.
 *
 * Mirrors the M1 / M2 convention: sealed interface per port, no
 * `Either`/`Result` re-use, every case named after a real failure mode.
 */
sealed interface PinRefusal {

    /** No stream with this id has ever been opened. */
    data class UnknownStream(val stream: OutputStreamId) : PinRefusal

    /**
     * The range extends past the stream's committed extent. The pin
     * store MUST refuse closed rather than clamping.
     */
    data class RangeBeyondCommitted(
        val stream: OutputStreamId,
        val range: LongRange,
        val committed: Long,
    ) : PinRefusal

    /**
     * The cap on the number of active pins per `(stream, runId)` was
     * exceeded. The default cap is
     * [OutputPinPort.DEFAULT_MAX_PINS_PER_STREAM]; a custom cap is
     * permitted at construction.
     */
    data class TooManyPins(
        val stream: OutputStreamId,
        val limit: Int,
        val active: Int,
    ) : PinRefusal

    /** The underlying storage failed; [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : PinRefusal
}

/**
 * M3 — closed result of [OutputPinPort.release].
 *
 * Idempotent: a second call returns [AlreadyReleased] rather than a
 * refusal. Mirrors the M1-F.3 `SealOutcome.AlreadySealed` discipline.
 */
sealed interface PinReleaseOutcome {

    /** The pin was released by this call. */
    data class Released(val pinId: OutputPinId) : PinReleaseOutcome

    /** The pin had already been released (or expired). */
    data class AlreadyReleased(val pinId: OutputPinId) : PinReleaseOutcome

    /** No pin with this id exists. */
    data class UnknownPin(val pinId: OutputPinId) : PinReleaseOutcome

    /** The underlying storage failed; [cause] is a short diagnostic. */
    data class StorageError(val cause: String) : PinReleaseOutcome
}