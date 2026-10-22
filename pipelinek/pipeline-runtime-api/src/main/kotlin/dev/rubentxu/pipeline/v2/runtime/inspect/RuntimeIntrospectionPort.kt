package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * Public read-only port that answers "what does this run look like right
 * now?" for a given runId.
 *
 * `inspect(runId)` reconciles the primitives the product already has — the
 * event-plane reads (`EventRecordReadPort`), the output-plane reads
 * (`OutputReadPort`, `OutputFrameIndex`, `OutputTailPort`), the journal
 * (`OperationJournal.listForRun`), the cursor (`ReplayCursorStore.load`) and
 * the lease decider (`RunExecutionLease.acquire`) — into one typed
 * `RuntimeObservation`. It introduces no new event store, output store,
 * journal or lease authority: the port reads, and it never owns.
 *
 * `runStructure(runId, options)` and `inspectOperation(operationId, attempt)`
 * answer the same question with more shape. The first returns a bounded,
 * paginated typed enumeration of the run's stages, steps and operations,
 * projected from the same journal and `OutputFrameIndex` that `inspect`
 * composes. The second returns a typed [OperationInspection] for a single
 * (operation, attempt) pair. Both are read-only by construction, and neither
 * introduces a lease, a scheduler, a governor or a query engine.
 *
 * ## Read-only by construction
 * Every method on this port MUST NOT write to durable state. The composition
 * rule limits them to SELECTs, tail-state queries and `load`. A consumer that
 * wants recovery invokes `RuntimeRecoverPort.recover`, not `inspectOperation`.
 *
 * ## Concurrency and ordering
 * Two concurrent `inspect(runId)` calls on the same run return observations
 * that differ only in monotonic progress. The same rule applies to
 * `runStructure` and `inspectOperation`: a second call observes a state that
 * is `>=` the previous call's state. The implementation MUST NOT race the
 * writer.
 *
 * ## Stability
 * `runtime.inspect.v1` is the capability id of this port. It is EXPERIMENTAL,
 * and it is the id the product composes: the `inspect --state` verb reads it.
 *
 * The two methods that answer with more shape carry their own ids,
 * `runtime.structure.v1` and `runtime.operation.v1`, and those are
 * DEPRECATED — which here means *announced and not delivered*. The adapter
 * implements them and no consumer in the tree composes them yet. The
 * capability catalogue is the authority on that status, so a consumer reads
 * the catalogue before it trusts an id.
 *
 * ## Who implements it
 * The store-backed implementation is an internal module: the ports are
 * published and the adapters are not. The composition happens inside the
 * product, which is the only place that holds the event store, the journal
 * and the lease.
 */
interface RuntimeIntrospectionPort {

    /**
     * Inspect the live state of [runId] right now.
     * @param runId The run to inspect. `String`, NOT a typed `RunId` (the design
     * pins `runId` as `String`; typed identity is a future concern)
     * @return A [RuntimeIntrospectionResult] that is either an
     *   [RuntimeIntrospectionResult.Observation] carrying the closed
     *   [RuntimeObservation], or a [RuntimeIntrospectionResult.Refused] carrying a
     *   closed [IntrospectionRefusal].
     *   NEVER throws. A substrate that is unobservable is returned as a typed
     *   refusal, not an exception.
*/
    fun inspect(runId: String): RuntimeIntrospectionResult

    /**
     * Return the typed structure of [runId], bounded by [options].
     * Composes `OperationJournal.listForRun` (internal) and
     * `OutputFrameIndex.streamsOfRun` (published) over the same primitives
     * `inspect` composes. The result is a closed [RunStructure] (Stages /
     * Empty / Unavailable) wrapped in [RunStructureResult.Structure], or a
     * [RunStructureResult.Refused] carrying a closed [IntrospectionRefusal].
     * The call is bounded by [StructureRunOptions.pageSize]; a consumer that
     * needs the next page passes the result's cursor back as `options.cursor`.
     * Forward-only pagination; the cursor is opaque to consumers.
     * NEVER throws. A substrate that is unobservable is returned as a typed
     * refusal (StorageError / UnknownRun / etc.), not an exception.
*/
    fun runStructure(
        runId: String,
        options: StructureRunOptions = StructureRunOptions.Default,
    ): RunStructureResult

    /**
     * Return the typed view of one (operation, attempt) pair.
     * Composes `OperationJournal.get(opId, attempt)` (internal) and
     * `OutputFrameIndex.streamsOfRun` (published). The result is a
     * [OperationInspection] wrapped in [OperationInspectionResult.Inspection],
     * or a [OperationInspectionResult.Refused] carrying a closed
     * [IntrospectionRefusal].
     * @param operationId The durable operation id (the journal's `id` column).
     * @param attempt 1-based attempt ordinal. The init guard rejects
     *   `attempt < 1`.
     * NEVER throws. A substrate that is unobservable is returned as a typed
     * refusal (UnknownRun / StorageError), not an exception.
*/
    fun inspectOperation(operationId: String, attempt: Int): OperationInspectionResult

    companion object {
        /**
         * Upper bound on a single `inspect` call's wall time.
         * Implementation default, NOT a contract surface. Documented so the UAT
         * Matrix can pin it; the implementation MUST honour it. The methods
         * `runStructure` and `inspectOperation` inherit the same budget by
         * construction.
*/
        const val DEFAULT_INSPECT_TIMEOUT_MS: Long = 5_000L

        /**
         * Upper bound on a single `runStructure` call's wall time.
         * Documented so the UAT matrix can pin it; the implementation
         * MUST honour it. The audit's B.1 hardening item is to
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
