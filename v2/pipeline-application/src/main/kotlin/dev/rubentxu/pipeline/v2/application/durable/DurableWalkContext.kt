package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.application.BranchReconciler

/**
 * Context passed through a durable walk (step execution loop).
 *
 * Collapses the 5 shared parameters that flow through every step execution
 * into a single data class, making the `executeDurableStep` signature
 * more readable and refactorable.
 *
 * @property clock The clock used for timestamps and deadline computation.
 * @property opJournal The operation journal for durable recording.
 * @property cursorStore The replay cursor store for checkpoint tracking.
 * @property branchReconciler The branch reconciler for kill+resume recovery.
 * @param eventSink The event sink for emitting domain events.
 *
 * Designed to be constructed once per pipeline run and passed through
 * all step executions. Immutable after construction.
 */
data class DurableWalkContext(
    val clock: Clock,
    val opJournal: OperationJournal,
    val cursorStore: ReplayCursorStore,
    val branchReconciler: BranchReconciler,
    val eventSink: EventSink,
)
