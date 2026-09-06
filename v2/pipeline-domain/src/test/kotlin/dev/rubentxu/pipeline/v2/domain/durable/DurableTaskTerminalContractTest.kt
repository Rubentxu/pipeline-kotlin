package dev.rubentxu.pipeline.v2.domain.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DurableTaskTerminalContractTest {
    @Test
    fun `failure record is a serializable durable contract without a throwable`() {
        val record = FailureRecord(
            code = "DURABLE_LAUNCH_FAILED",
            kind = FailureKind.INFRASTRUCTURE,
            message = "could not create durable process",
            origin = FailureOrigin.LAUNCHER,
            retryable = false,
            operationId = "run-1-step-2",
            workerId = "local",
            taskId = "durable-shell",
            details = mapOf("controlDir" to "/tmp/control"),
        )

        val encoded = Json.encodeToString(FailureRecord.serializer(), record)

        assertEquals(record, Json.decodeFromString(FailureRecord.serializer(), encoded))
    }
}
