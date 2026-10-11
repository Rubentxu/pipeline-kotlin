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
 * PK-1 / ADR-PK1-IDENTITY — typed, machine-verifiable receipt of a
 * pipeline invocation.
 *
 * Emitted on **stdout** as a single line of JSON once durable state is
 * established (lease acquired, journal opened). A consumer that reads
 * the line is guaranteed that:
 *
 *  - The [runId] has been assigned by PK.
 *  - The [correlationId] has been bound to the [runId] in durable state
 *    and in the [dev.rubentxu.pipeline.v2.events.RunCorrelated] event.
 *  - The [ownerId] is the only writer authority for this run until the
 *    lease is released.
 *
 * The line is schema-versioned. The [SCHEMA] constant is the
 * authoritative version. Incompatible changes bump to
 * `pipelinek.launch/2`. Consumers MUST parse by [SCHEMA].
 *
 * @see ADR-PK1-IDENTITY for the design rationale.
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
