package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed output for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsSha256StepDefinition].
 *
 * - [hexDigest]: lowercase hex digest (64 chars for SHA-256, 40 for SHA-1).
 * - [byteSize]: file size read from disk.
 * - [algorithm]: the algorithm that actually ran (mirrors [Sha256Input.algorithm]).
 */
@Serializable
data class Sha256Output(
    val hexDigest: String,
    val byteSize: Long,
    val algorithm: String,
)
