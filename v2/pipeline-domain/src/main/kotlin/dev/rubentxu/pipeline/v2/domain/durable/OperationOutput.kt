package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Immutable, serializable carrier for the output of a durable operation.
 *
 * @param result     JSON-encoded result produced by the step.
 * @param durationMs Wall-clock duration in milliseconds.
 * @param finishedAt Epoch milliseconds at which execution completed.
 *
 * @see <a href="design.md §E4-01">Design §E4-01</a>
 */
@Serializable
data class OperationOutput(
    val result: JsonElement,
    val durationMs: Long,
    val finishedAt: Long,
) {
    init {
        require(durationMs >= 0) { "durationMs must be non-negative, got $durationMs" }
        require(finishedAt > 0) { "finishedAt must be positive epoch millis, got $finishedAt" }
    }
}
