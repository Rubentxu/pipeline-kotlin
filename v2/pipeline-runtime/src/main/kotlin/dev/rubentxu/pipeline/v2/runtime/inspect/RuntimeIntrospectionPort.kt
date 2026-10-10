package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * M2 — public read-only port that answers "what does this run look like right now?"
 * for a given runId.
 *
 * ## What this port is and is not
 *
 * `RuntimeIntrospectionPort` reconciles the existing PK primitives — the event-plane
 * reads (`EventRecordReadPort`), the output-plane reads (`OutputReadPort`,
 * `OutputFrameIndex`, `OutputTailPort`), the journal (`OperationJournal.listForRun`),
 * the cursor (`ReplayCursorStore.load`), and the pure lease decider
 * (`RunExecutionLease.acquire`) — into one typed `RuntimeObservation`. It does NOT
 * introduce a new event store, output store, journal, or lease authority. Every
 * primitive the port composes is named with module:file:line in the design's
 * composition table (§6 of the M2 design).
 *
 * ## Read-only by construction
 *
 * `inspect` MUST NOT write to durable state. The composition rule (§3.4 of the
 * design) limits it to SELECTs, tail-state queries, and `load`. A consumer that
 * wants recovery invokes `RuntimeRecoverPort.recover`, not `inspect`.
 *
 * ## Concurrency and ordering
 *
 * Two concurrent `inspect(runId)` calls on the same run return observations that
 * differ only in monotonic progress (a journal sequence that advanced, a frame
 * ordinal that advanced). The implementation MUST NOT race the writer; the
 * contract test pins this.
 *
 * ## Stability
 *
 * This port is `EXPERIMENTAL` for the M2 first cut (the capability IDs in the
 * `Capabilities` companion are advertised; this module pins the ports and sealed
 * refusal ADTs that the capability IDs advertise). Promotion to `PUBLICADA` happens
 * only after the M2 candidate is `CERTIFIED`.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §3.
 */
fun interface RuntimeIntrospectionPort {

    /**
     * Inspect the live state of [runId] right now.
     *
     * @param runId The run to inspect. `String`, NOT a typed `RunId` (the M1 design
     *   §7 pins `runId` as `String`; typed identity is a future M-block concern).
     * @return A [RuntimeIntrospectionResult] that is either an
     *   [RuntimeIntrospectionResult.Observation] carrying the closed
     *   [RuntimeObservation], or a [RuntimeIntrospectionResult.Refused] carrying a
     *   closed [IntrospectionRefusal].
     *
     *   NEVER throws. A substrate that is unobservable is returned as a typed
     *   refusal, not an exception.
     */
    fun inspect(runId: String): RuntimeIntrospectionResult

    companion object {
        /**
         * Upper bound on a single `inspect` call's wall time.
         *
         * Implementation default, NOT a contract surface. Documented so the UAT
         * matrix can pin it; the implementation MUST honour it.
         */
        const val DEFAULT_INSPECT_TIMEOUT_MS: Long = 5_000L
    }
}
