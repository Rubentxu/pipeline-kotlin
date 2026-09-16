package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId

/**
 * The durable identity of the AGGREGATE operation a block Step's body belongs to
 * (B10 / W1d).
 *
 * ## Why these are not routing debt
 *
 * The W1a ledger (`PinnedConcreteBodyRoutingDebt`) counts concrete Step identities the
 * coordinator BRANCHES on. Two identities in the coordinator were never branches:
 * `core.retry` names the RETRY-D retry control row and `core.parallel` names the PAR-D
 * stage aggregate. They are used as durable KEYS — the `stepId` of an `OperationInput` and
 * the fingerprint argument of `Fingerprint.compute` — so they are part of the replay
 * contract, not part of a routing decision.
 *
 * W1d therefore does two things instead of one:
 *
 * ```text
 * 1. the literals leave the coordinator:  the engine names a typed identity
 * 2. the identities stay pinned here:     a new one is a deliberate, reviewed edit
 * ```
 *
 * Removing the literals alone would have been a syntactic burn-down that hid two real
 * identities; leaving them in the routing ledger would have kept counting them as debt
 * forever. This model is the honest classification: the identity is still concrete and
 * still pinned, but pinned as what it IS.
 *
 * ## Law
 *
 * A [key] is a durable key. Changing it, or adding a case, changes the fingerprint of an
 * already-journaled aggregate and is a REPLAY-BREAKING change to be treated as such
 * (ADR-0075 / ADR-0076), never as a cosmetic rename.
 */
sealed interface BodyAggregateIdentity {

    /** The concrete durable key; used verbatim as the `stepId` of the aggregate operation. */
    val key: PluginStepId

    /** What the identity is durable FOR. */
    val durableRole: AggregateDurableRole

    /**
     * RETRY-D (ADR-0075) — identity of the retry CONTROL ROW.
     *
     * The control row is the durable anchor of a retry aggregate: persisted before any
     * child effect, so a second invocation with the same run identity reconciles against it
     * instead of re-running the loop. The key is a stable per-aggregate fingerprint input,
     * NOT a per-attempt key.
     */
    data object RetryControlRow : BodyAggregateIdentity {
        override val key: PluginStepId = PluginStepId("core.retry")
        override val durableRole: AggregateDurableRole = AggregateDurableRole.RETRY_CONTROL_ROW
    }

    /**
     * PAR-D (ADR-0076) — identity of the parallel STAGE AGGREGATE.
     *
     * The aggregate row records the stage's branch terminal outcome, so a durable rerun
     * reconstructs the join from journaled branch facts instead of re-launching branches.
     */
    data object ParallelStageAggregate : BodyAggregateIdentity {
        override val key: PluginStepId = PluginStepId("core.parallel")
        override val durableRole: AggregateDurableRole = AggregateDurableRole.PARALLEL_STAGE_AGGREGATE

        /**
         * The aggregate fingerprint key of one stage: the identity key, the stage index,
         * and the branch names in declaration order.
         *
         * Built here, once, so the key shape cannot drift between the writer and the
         * reconciler. The value is replay-relevant and therefore a pure function of its
         * arguments: no environment input, no clock, no collection order not stated here.
         */
        fun fingerprintKey(stageIndex: Int, branchNames: List<String>): String =
            "${key.value}[$stageIndex]" + branchNames.joinToString("|")
    }

    /**
     * WU-G5R.5 (ADR-0075 analog) — identity of the waitUntil CONTROL ROW.
     *
     * The control row is the durable anchor of a waitUntil aggregate: persisted before any
     * predicate body effect, so a second invocation with the same run identity reconciles
     * against it instead of re-running the polling loop. The key is a stable per-aggregate
     * fingerprint input, NOT a per-poll key.
     */
    data object WaitUntilControlRow : BodyAggregateIdentity {
        override val key: PluginStepId = PluginStepId("wait-until-control")
        override val durableRole: AggregateDurableRole = AggregateDurableRole.WAIT_UNTIL_CONTROL_ROW
    }

    companion object {
        /**
         * Every declared aggregate identity. Pinned so a THIRD durable aggregate identity
         * has to be added deliberately (here, with its owning ADR), instead of appearing as
         * a string literal inside the coordinator.
         */
        val ALL: List<BodyAggregateIdentity> = listOf(RetryControlRow, ParallelStageAggregate, WaitUntilControlRow)
    }
}

/** The durable role an aggregate identity plays. Closed: a new role is a new ADR. */
enum class AggregateDurableRole(val authority: String) {
    RETRY_CONTROL_ROW("ADR-0075"),
    PARALLEL_STAGE_AGGREGATE("ADR-0076"),
    /** WU-G5R.5 — durable waitUntil predicate polling loop control row. */
    WAIT_UNTIL_CONTROL_ROW("WU-G5R.5 / ADR-0075 analog"),
}
