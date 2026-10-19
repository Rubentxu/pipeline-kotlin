package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * Bounded-scan + resume-cursor options for [RuntimeIntrospectionPort.runStructure].
 * The audit'sF.3 names "bounded scan + resume cursor" as the F.3 gap. The
 * options pin the contract:
 *  - `pageSize` — the maximum number of operations returned in a single
 *    call. Bounded per-call, not per-run. The init guard enforces a [1, 4096]
 *    range; the lower bound stops an unbounded call, the upper bound stops a
 *    one-shot giant scan that would defeat the audit's "lectura eficiente
 *    con presupuestos explícitos" rule.
 *  - `cursor` — the [StructureCursor] from the previous call's result, or
 *    `null` for the first call. Passing a cursor that does not match the
 *    run's state returns a `RunStructureResult.Refused(...)` or a truncated
 *    result, depending on the mismatch.
 *  - `includeTerminalStages` — when `true` (the default), terminal stages
 *    are included in the enumeration. When `false`, only in-flight stages
 *    are returned. The flag exists so a Fabric that wants the live view of
 *    a long-running run can skip the historical tail.
 * The options are validated at construction (`init`); a closed ADT pattern
 * with a `Default` companion preserves the "no `Either`/`Result` reuse"
 * rule.
*/
data class StructureRunOptions(
    val pageSize: Int = DEFAULT_STRUCTURE_PAGE_SIZE,
    val cursor: StructureCursor? = null,
    val includeTerminalStages: Boolean = true,
) {
    init {
        require(pageSize in 1..MAX_STRUCTURE_PAGE_SIZE) {
            "pageSize must be in [1, $MAX_STRUCTURE_PAGE_SIZE], got $pageSize"
        }
    }

    companion object {
        /** Default page size; matches the design's `DEFAULT_INSPECT_TIMEOUT_MS` discipline */
        const val DEFAULT_STRUCTURE_PAGE_SIZE: Int = 64

        /** Hard cap on a single call's `pageSize`. The audit's F.3 answer. */
        const val MAX_STRUCTURE_PAGE_SIZE: Int = 4096

        /** The default options (`pageSize = 64`, no cursor, include all stages). */
        val Default: StructureRunOptions = StructureRunOptions()
    }
}

/**
 * Opaque-per-run resume cursor for [RuntimeIntrospectionPort.runStructure].
 * The cursor encodes three ordinals — `(stageOrdinal, stepOrdinal,
 * operationOrdinal)` — all per-run. A consumer that wants the next page
 * passes the cursor from the previous result back as
 * `StructureRunOptions.cursor`; the call returns the next page.
 * Cursor identity rules:
 *  - Per-run: a cursor from run A is not valid for run B. A consumer that
 *    re-uses it gets a `RunStructureResult.Refused(...)` or a truncated
 *    result, depending on the type-mismatch case.
 *  - Opaque to consumers: the values are ordinals, but a consumer MUST
 *    treat the cursor as a token. A consumer that tries to construct a
 *    cursor by ordinal arithmetic is out of contract.
 *  - Forward-only: the cursor is monotonic in execution order. The adapter
 *    does not support backwards pagination.
*/
data class StructureCursor(
    val stageOrdinal: Int,
    val stepOrdinal: Int,
    val operationOrdinal: Int,
) {
    init {
        require(stageOrdinal >= 0) { "stageOrdinal must be >= 0, got $stageOrdinal" }
        require(stepOrdinal >= 0) { "stepOrdinal must be >= 0, got $stepOrdinal" }
        require(operationOrdinal >= 0) { "operationOrdinal must be >= 0, got $operationOrdinal" }
    }
}
