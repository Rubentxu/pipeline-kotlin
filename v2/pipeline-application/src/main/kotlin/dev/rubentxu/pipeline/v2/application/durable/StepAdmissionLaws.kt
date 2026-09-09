package dev.rubentxu.pipeline.v2.application.durable

/**
 * Pure value-object describing the durable-protocol admission laws observed at the spine.
 *
 * Slice S2.5.7 / B1.2c3 — LB-01 spine consolidation. WU-1 introduces the primitive
 * shape so that later WUs can wire the spine without touching this file.
 *
 * Invariants:
 *  - Pure, no I/O. No `runBlocking`, no clock, no random, no event sink, no logger.
 *  - The result is a closed [AdmissionReport] ADT; exhaustive `when` is mandatory
 *    at every consumer. `Other` is the explicit catch-all for future resolutions
 *    that have not yet been named here.
 *  - The same `(resolution, executorCalls)` pair is total: exactly one case.
 */
object StepAdmissionLaws {

    /**
     * The 6 named reconciliation outcomes currently distinguished at the spine,
     * plus `Other` for any value not yet named here. The spine's `when` will
     * exhaustively match this enum in WU-3..5.
     */
    enum class ReconciliationResolution {
        Fresh,
        ReuseCompleted,
        RejectedDecode,
        Diverged,
        SchemaPrecedence,
        OverlayApplied,
        Other,
    }

    /**
     * The closed ADT a step's admission observation maps to. Each variant carries
     * the executor-call count seen for THIS resolution so that the spine can
     * distinguish "executed once" (C1/C6) from "did not execute" (C2..C5).
     */
    sealed interface AdmissionReport {
        val executorCalls: Int

        /** C1 — fresh: executor was called exactly once. */
        data class C1FreshExecutedOnce(override val executorCalls: Int) : AdmissionReport

        /** C2 — completed reuse: executor was not called. */
        data class C2ReuseNoExecute(override val executorCalls: Int) : AdmissionReport

        /** C3 — decode rejected during preparation: executor was not called. */
        data class C3DecodeRejectNoExecute(override val executorCalls: Int) : AdmissionReport

        /** C4 — divergent: executor was not called. */
        data class C4DivergenceNoExecute(override val executorCalls: Int) : AdmissionReport

        /** C5 — schema takes precedence over reuse: executor was not called. */
        data class C5SchemaPrecedenceOverReuse(override val executorCalls: Int) : AdmissionReport

        /** C6 — overlay applied on reuse: executor was called exactly once. */
        data class C6OverlayAppliedOnReuse(override val executorCalls: Int) : AdmissionReport

        /** Catch-all for any future / unmapped resolution. */
        data class Other(override val executorCalls: Int) : AdmissionReport
    }

    /**
     * Pure total function: maps a [ReconciliationResolution] + executor-call count
     * to the corresponding [AdmissionReport] case. Same input always yields the
     * same output; no side effects.
     */
    fun observe(
        resolution: ReconciliationResolution,
        executorCalls: Int,
    ): AdmissionReport = when (resolution) {
        ReconciliationResolution.Fresh -> AdmissionReport.C1FreshExecutedOnce(executorCalls)
        ReconciliationResolution.ReuseCompleted -> AdmissionReport.C2ReuseNoExecute(executorCalls)
        ReconciliationResolution.RejectedDecode -> AdmissionReport.C3DecodeRejectNoExecute(executorCalls)
        ReconciliationResolution.Diverged -> AdmissionReport.C4DivergenceNoExecute(executorCalls)
        ReconciliationResolution.SchemaPrecedence -> AdmissionReport.C5SchemaPrecedenceOverReuse(executorCalls)
        ReconciliationResolution.OverlayApplied -> AdmissionReport.C6OverlayAppliedOnReuse(executorCalls)
        ReconciliationResolution.Other -> AdmissionReport.Other(executorCalls)
    }
}
