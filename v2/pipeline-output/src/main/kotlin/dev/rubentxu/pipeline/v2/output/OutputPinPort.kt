package dev.rubentxu.pipeline.v2.output

/**
 * M3 — public retention-pin port: a pin attaches to a `(stream, range)`
 * and survives retention prunes until released or expired.
 *
 * ## What a pin is and is NOT
 *
 * A pin is a **retention hold**: "these bytes may not be GC'd until
 * the pin is released". It is NOT a lease: a lease fences a writer
 * (only one publisher per run); a pin holds bytes (no consumer of
 * the bytes may have them GC'd while the pin is active). The two
 * authorities are deliberately separate.
 *
 * ## Lifecycle
 *
 * ```text
 * pin()   -> Pinned(pinId)        OR Refused(PinRefusal)
 * release(pinId) -> Released      OR AlreadyReleased OR UnknownPin OR Refused(...)
 * list(stream?) -> List<OutputPin>           (deterministic order: pinId lexicographic)
 * isPinned(stream, offset) -> Boolean        (consult-before-act)
 * pinsOf(stream, range?) -> List<OutputPin>  (consult-before-act)
 * ```
 *
 * ## Authority composition
 *
 * - The pin store lives in `:pipeline-output-store` (non-published).
 * - A pin does NOT take a lease. A pin's `holder` is a `String`.
 * - A pin MAY carry an optional `expiresAtMs`; an expired pin is
 *   treated as RELEASED, not refused.
 *
 * ## Default limit
 *
 * [DEFAULT_MAX_PINS_PER_STREAM] is the cap on the number of active
 * pins per `(stream, runId)`.
 */
interface OutputPinPort {

    /**
     * Pin [range] of [stream] against retention pruning. The pin
     * survives any subsequent `OutputRetentionPort.prune(intent)` UNLESS
     * the pin's `holder` explicitly `release`s it OR `expiresAtMs` passes.
     *
     * The pin id is opaque and assigned by the implementation; consumers
     * carry it through `release(pinId)` and `pinsOf(stream)` filters.
     */
    fun pin(
        stream: OutputStreamId,
        range: LongRange,
        holder: String,
        reason: String,
        expiresAtMs: Long? = null,
    ): OutputPinResult

    /**
     * Release the pin identified by [pinId]. Idempotent: a second
     * call returns [PinReleaseOutcome.AlreadyReleased].
     */
    fun release(pinId: OutputPinId): PinReleaseOutcome

    /**
     * Every active pin for [stream], optionally restricted to
     * [range]. The list is ordered by `pinId` lexicographic.
     */
    fun pinsOf(
        stream: OutputStreamId,
        range: LongRange? = null,
    ): List<OutputPin>

    /**
     * Whether ANY active pin covers [stream]'s byte at [offset].
     * The consult-before-act primitive for `OutputRetentionPort.prune`:
     * a consumer that wants to pre-flight a release calls `isPinned`
     * for every byte in the range it intends to delete.
     *
     * O(1) lookup against the pin index; the implementation MAY
     * index by stream + offset.
     */
    fun isPinned(stream: OutputStreamId, offset: Long): Boolean

    companion object {
        /**
         * Default cap on the number of active pins per `(stream, runId)`.
         * Configurable baseline. The implementation MUST refuse with
         * [PinRefusal.TooManyPins] above this default.
         */
        const val DEFAULT_MAX_PINS_PER_STREAM: Int = 1024
    }
}