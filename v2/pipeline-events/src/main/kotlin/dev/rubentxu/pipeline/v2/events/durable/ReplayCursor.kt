package dev.rubentxu.pipeline.v2.events.durable

import kotlinx.serialization.Serializable

/**
 * Persisted replay cursor tracking the last successfully journaled operation
 * for a given run.
 *
 * @param runId      The run identifier.
 * @param lastOpId   The ID of the last successfully journaled operation, or `null` if no operation has been journaled.
 * @param stageIndex The stage index at which execution should resume.
 * @param savedAt    Epoch milliseconds at which this cursor was last saved.
 *
 * @see <a href="design.md §E4-04">Design §E4-04</a>
 */
@Serializable
data class ReplayCursor(
    val runId: String,
    val lastOpId: String?,
    val stageIndex: Int,
    val savedAt: Long,
)
