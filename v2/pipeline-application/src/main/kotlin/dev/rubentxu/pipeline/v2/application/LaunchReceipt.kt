package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path

/**
 * typed,
 * machine-verifiable receipt of a pipeline invocation.
 *
 * Emitted on **stdout** as a single line of JSON once the launch
 * sequence is complete. A consumer that reads the line is
 * guaranteed that:
 *
 *  - The [runId] has been assigned by PK.
 *  - The [correlationId] has been bound to the [runId] in the
 *    [dev.rubentxu.pipeline.v2.events.RunCorrelated] event.
 *  - The [ownerId] is the writer authority for this run.
 *
 * **Launched is a guarantee of LAUNCH, not of DURABILITY.**
 * Two modes emit the same `pipelinek.launch/1` schema:
 *
 *  - **Durable mode** (`--db <path>`): the run is recorded in
 *    a SQLite journal and an advisory FileChannel lease is
 *    acquired on the control root. A subsequent process can
 *    `--resume` against the same `--db` to find the run by
 *    [runId] and continue from the last durable state.
 *  - **Non-durable mode** (in-memory): no journal is written
 *    and no lease is acquired. A crash of the process loses
 *    the run; `--resume` has no state to find.
 *
 * Consumers that need the durable/non-durable distinction must
 * infer it from the invocation (`--db <path>` was supplied) or
 * from the [dev.rubentxu.pipeline.v2.output.OutputRefusal]
 * stream's `recoveryGate`. The schema does not encode the
 * mode; doing so would be a breaking change to consumers
 * that already parse the receipt.
 *
 * The line is schema-versioned. The [SCHEMA] constant is the
 * authoritative version. Incompatible changes bump to
 * `pipelinek.launch/2`. Consumers MUST parse by [SCHEMA].
 *
 */
@Serializable
data class LaunchReceipt(
    val schema: String = SCHEMA,
    val runId: String,
    val correlationId: String,
    val ownerId: String,
    val startedAtMs: Long,
    val controlDir: String? = null,
    val status: LaunchStatus,
) {
    companion object {
        const val SCHEMA: String = "pipelinek.launch/1"

        /**
         * JSON encoder configured to emit a single line (no pretty-print,
         * no HTML escaping, no lenient parsing). The output is stable
         * byte-for-byte for a given input.
         */
        private val ENCODER: Json = Json {
            prettyPrint = false
            encodeDefaults = true
        }

        /**
         * Build a successful [LaunchReceipt] from typed inputs.
         */
        fun launched(
            runId: RunId,
            correlationId: CorrelationId,
            ownerId: RunOwnerId,
            startedAtMs: Long,
            controlDir: Path?,
        ): LaunchReceipt = LaunchReceipt(
            runId = runId.value,
            correlationId = correlationId.value,
            ownerId = ownerId.value,
            startedAtMs = startedAtMs,
            controlDir = controlDir?.toString(),
            status = LaunchStatus.Launched,
        )

        /**
         * Build a refused [LaunchReceipt] (admission or pre-launch failure).
         */
        fun refused(
            correlationId: CorrelationId,
            reason: String,
            startedAtMs: Long,
        ): LaunchReceipt = LaunchReceipt(
            runId = "",
            correlationId = correlationId.value,
            ownerId = "",
            startedAtMs = startedAtMs,
            controlDir = null,
            status = LaunchStatus.Refused(reason = reason),
        )

        /**
         * Encode a [LaunchReceipt] to a single-line JSON string. The
         * output ends with a single `\n` so a consumer can `readLine()`
         * it cleanly.
         */
        fun encode(receipt: LaunchReceipt): String {
            val obj: JsonObject = buildJsonObject {
                put("schema", JsonPrimitive(receipt.schema))
                put("runId", JsonPrimitive(receipt.runId))
                put("correlationId", JsonPrimitive(receipt.correlationId))
                put("ownerId", JsonPrimitive(receipt.ownerId))
                put("startedAtMs", JsonPrimitive(receipt.startedAtMs))
                receipt.controlDir?.let { put("controlDir", JsonPrimitive(it)) }
                put("status", JsonPrimitive(encodeStatus(receipt.status)))
            }
            return Json { prettyPrint = false }.encodeToString(JsonObject.serializer(), obj) + "\n"
        }

        private fun encodeStatus(status: LaunchStatus): String = when (status) {
            is LaunchStatus.Launched -> "Launched"
            is LaunchStatus.Refused -> "Refused:${status.reason}"
        }
    }
}

/** Status of a launch attempt. [Launched] is success; [Refused] is typed failure. */
@Serializable
sealed interface LaunchStatus {
    @Serializable
    data object Launched : LaunchStatus

    @Serializable
    data class Refused(val reason: String) : LaunchStatus
}
