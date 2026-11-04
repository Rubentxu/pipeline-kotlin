package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.durable.FailureOrigin
import dev.rubentxu.pipeline.v2.domain.durable.FailureRecord
import java.nio.file.Path

internal fun Exception.launchFailureRecord(opId: String, controlDir: Path): FailureRecord = FailureRecord(
    code = "DURABLE_LAUNCH_FAILED",
    kind = FailureKind.INFRASTRUCTURE,
    message = message ?: "could not launch durable task",
    origin = FailureOrigin.LAUNCHER,
    retryable = false,
    operationId = opId,
    workerId = "local",
    taskId = "durable-shell",
    details = mapOf("controlDir" to controlDir.toString()),
)

internal fun lostFailureRecord(opId: String, controlDir: Path, message: String): FailureRecord = FailureRecord(
    code = "DURABLE_TASK_LOST",
    kind = FailureKind.INFRASTRUCTURE,
    message = message,
    origin = FailureOrigin.RECONCILIATION,
    retryable = true,
    operationId = opId,
    workerId = "local",
    taskId = "durable-shell",
    details = mapOf("controlDir" to controlDir.toString()),
)

