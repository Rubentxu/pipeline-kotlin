package dev.rubentxu.pipeline.v2.events.shape

/**
 * M7 — typed read port that returns the typed events the journal recorded
 * for a run, in execution order.
 *
 * ## What this port is and is not
 *
 * `EventShapeReadPort` is the source of truth for the M6 PARTIAL closure
 * (`M6_CONTEXT_PRESSURE_AUDIT.md` §D I.6, "synthetic single-stage /
 * single-step view"): the published `PipelineEventEnvelope` strips the
 * type-specific fields of the typed events (stageIndex, stepOrdinal,
 * stageName, stepKey), so reading them from envelopes is NOT in scope of
 * any existing port. This port returns the typed events directly, so the
 * `RuntimeIntrospectionPort.runStructure` adapter can drive
 * event-driven grouping.
 *
 * The port composes the same primitives `EventRecordReadPort` composes
 * (the event sink + the typed event decoder) but projects to typed
 * events instead of envelopes. The composition rule (M7 design §A) is:
 *
 *  - `eventReads` (the M1 EventRecordReadPort) for the read-page
 *  - the typed event decoder for `StageStarted` / `StageFinished` /
 *    `StageSkipped` / `StepStarted` / `StepFinished` (the five cases
 *    the M6 audit names)
 *
 * ## Read-only by construction
 *
 * `eventShapesForRun` MUST NOT write to durable state. The composition
 * rule limits it to SELECTs and a decode pass. A consumer that wants
 * to write a typed event uses the event sink; this port is read-only.
 *
 * ## Stability
 *
 * The port ships as `EXPERIMENTAL` in `v0.54.0-rc1` (the M7 candidate).
 * Promotion to `PUBLICADA` happens after the certifier's `CERTIFIED`
 * verdict. The five-case `TypedEvent` ADT is closed; a sixth case would
 * be a breaking change requiring consumer recompile.
 *
 * @see M7_FINAL_HARDENING_DESIGN.md §A.
 */
fun interface EventShapeReadPort {

    /**
     * Read the typed events for [runId], bounded by [options].
     *
     * The result is a closed [EventShapeResult] (Events / Empty /
     * Refused). When the result is `Events`, the list is in execution
     * order (the order the events arrived). The call is bounded by
     * [EventShapeOptions.pageSize]; a consumer that needs the next
     * page passes the result's cursor back as `options.cursor`.
     * Forward-only pagination; the cursor is opaque to consumers.
     *
     * @param runId The run to read. `String`, NOT a typed `RunId` (the
     *   M1 design §7 pins `runId` as `String`).
     * @param options The bounded-scan + resume-cursor options. A
     *   convenience overload below accepts no options; this method
     *   is the SAM so it cannot have a default parameter value.
     *
     * NEVER throws. A substrate that is unobservable is returned as a
     * typed refusal, not an exception.
     */
    fun eventShapesForRun(runId: String, options: EventShapeOptions): EventShapeResult

    companion object {
        /**
         * Upper bound on a single `eventShapesForRun` call's wall time.
         *
         * Implementation default, NOT a contract surface. Documented so
         * the UAT matrix can pin it; the implementation MUST honour it.
         */
        const val DEFAULT_EVENT_SHAPE_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * Convenience extension that calls [EventShapeReadPort.eventShapesForRun]
 * with [EventShapeOptions.Default]. The SAM cannot have a default
 * parameter; this extension is the consumer-friendly form.
 */
fun EventShapeReadPort.eventShapesForRun(runId: String): EventShapeResult =
    eventShapesForRun(runId, EventShapeOptions.Default)
