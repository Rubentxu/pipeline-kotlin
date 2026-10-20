package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed output for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsWriteJsonStepDefinition].
 *
 * - [absolutePath]: where the file was actually written (after workspace-root resolution).
 * - [byteSize]: size of the file on disk AFTER write completion.
 * - [sha256Hex]: lowercase hex SHA-256 of the file content (so callers can verify
 *   the durable write or correlate later with another read).
 * - [bytesWritten]: bytes flushed to the OS (may be less than [byteSize] for sparse files; we treat them as equal here).
 *
 * Determinism: identical inputs always produce identical [sha256Hex].
 */
@Serializable
data class WriteJsonOutput(
    val absolutePath: String,
    val byteSize: Long,
    val sha256Hex: String,
    val bytesWritten: Long,
)
