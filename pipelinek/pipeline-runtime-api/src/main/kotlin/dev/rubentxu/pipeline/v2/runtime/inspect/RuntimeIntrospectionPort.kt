package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * M2 (extended in M6) — public read-only port that answers "what does this run
 * look like right now?" for a given runId.
 *
 * ## M2 surface (preserved)
 *
 * `RuntimeIntrospectionPort.inspect(runId)` reconciles the existing PK
 * primitives — the event-plane reads (`EventRecordReadPort`), the output-plane
 * reads (`OutputReadPort`, `OutputFrameIndex`, `OutputTailPort`), the journal
 * (`OperationJournal.listForRun`), the cursor (`ReplayCursorStore.load`), and
 * the pure lease decider (`RunExecutionLease.acquire`) — into one typed
 * `RuntimeObservation`. It does NOT introduce a new event store, output store,
 * journal, or lease authority. Every primitive the port composes is named with
 * module:file:line in the M2 design's composition table (§6 of the M2 design).
 *
 * ## M6 surface (additive)
 *
 * Two new methods answer the audit's F.1 / F.2 / F.3 gaps:
 *
 *  - `runStructure(runId, options)` returns a bounded, paginated typed
 *    enumeration of the run's stages / steps / operations, projected from the
 *    same journal + `OutputFrameIndex` primitives `inspect` composes.
 *  - `inspectOperation(operationId, attempt)` returns a typed
 *    [OperationInspection] for a single (operation, attempt) pair.
 *
 * Both methods are read-only by construction; the M6 design's §C composition
 * table names the existing primitives they compose. They do NOT introduce a
 * new lease, scheduler, Governor, fairness scheduler, or query engine in PK.
 * The audit's F.4 / F.5 negative-scope rules are preserved.
 *
 * ## Read-only by construction
 *
 * Every method on this port MUST NOT write to durable state. The composition
 * rule (M2 design §3.4, M6 design §C) limits them to SELECTs, tail-state
 * queries, and `load`. A consumer that wants recovery invokes
 * `RuntimeRecoverPort.recover`, not `inspectOperation`.
 *
 * ## Concurrency and ordering
 *
 * Two concurrent `inspect(runId)` calls on the same run return observations
 * that differ only in monotonic progress. The same rule applies to
 * `runStructure` and `inspectOperation`: a second call observes a state that
 * is `>=` the previous call's state. The implementation MUST NOT race the
 * writer; the contract test pins this.
 *
 * ## Stability
 *
 * The M2 surface is `EXPERIMENTAL` (`runtime.inspect.v1`). The M6 surface
 * ships as `runtime.structure.v1` and `runtime.operation.v1`, also
 * `EXPERIMENTAL` until the M6 candidate is `CERTIFIED`. Promotion to
 * `PUBLICADA` happens only after the corresponding candidate is certified by
 * `pipelinek-release-harness`.
 *
 * @see M2_INSPECT_RECOVER_CANCEL_DESIGN.md §3.
 * @see M6_CONTEXT_PRESSURE_DESIGN.md §A and §C.
 */
interface RuntimeIntrospectionPort {

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

    /**
     * M6 — return the typed structure of [runId], bounded by [options].
     *
     * Composes `OperationJournal.listForRun` (internal) and
     * `OutputFrameIndex.streamsOfRun` (published) over the same primitives
     * `inspect` composes. The result is a closed [RunStructure] (Stages /
     * Empty / Unavailable) wrapped in [RunStructureResult.Structure], or a
     * [RunStructureResult.Refused] carrying a closed [IntrospectionRefusal].
     *
     * The call is bounded by [StructureRunOptions.pageSize]; a consumer that
     * needs the next page passes the result's cursor back as `options.cursor`.
     * Forward-only pagination; the cursor is opaque to consumers.
     *
     * NEVER throws. A substrate that is unobservable is returned as a typed
     * refusal (StorageError / UnknownRun / etc.), not an exception.
     */
    fun runStructure(
        runId: String,
        options: StructureRunOptions = StructureRunOptions.Default,
    ): RunStructureResult

    /**
     * M6 — return the typed view of one (operation, attempt) pair.
     *
     * Composes `OperationJournal.get(opId, attempt)` (internal) and
     * `OutputFrameIndex.streamsOfRun` (published). The result is a
     * [OperationInspection] wrapped in [OperationInspectionResult.Inspection],
     * or a [OperationInspectionResult.Refused] carrying a closed
     * [IntrospectionRefusal].
     *
     * @param operationId The durable operation id (the journal's `id` column).
     * @param attempt 1-based attempt ordinal. The init guard rejects
     *   `attempt < 1`.
     *
     * NEVER throws. A substrate that is unobservable is returned as a typed
     * refusal (UnknownRun / StorageError), not an exception.
     */
    fun inspectOperation(operationId: String, attempt: Int): OperationInspectionResult

    companion object {
        /**
         * Upper bound on a single `inspect` call's wall time.
         *
         * Implementation default, NOT a contract surface. Documented so the UAT
         * matrix can pin it; the implementation MUST honour it. The M6 methods
         * `runStructure` and `inspectOperation` inherit the same budget by
         * construction — the M6 design §C names the rule.
         */
        const val DEFAULT_INSPECT_TIMEOUT_MS: Long = 5_000L

        /**
         * M7 — upper bound on a single `runStructure` call's wall time.
         *
         * Documented so the UAT matrix can pin it; the implementation
         * MUST honour it. The M7 audit's B.1 hardening item is to
         * name the budget for the bounded-scan / paginated read path
         * the same way `DEFAULT_INSPECT_TIMEOUT_MS` names the budget
         * for the single-page inspect path. The value is 10_000L
         * (twice `DEFAULT_INSPECT_TIMEOUT_MS`) because paginated
         * reads may legitimately take longer than a single-page
         * inspect.
         */
        const val DEFAULT_STRUCTURE_TIMEOUT_MS: Long = 10_000L
    }
}
