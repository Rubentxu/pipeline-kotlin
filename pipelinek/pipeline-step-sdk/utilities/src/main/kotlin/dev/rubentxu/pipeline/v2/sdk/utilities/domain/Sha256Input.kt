package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * Typed input for [dev.rubentxu.pipeline.v2.sdk.utilities.step.CoreUtilsSha256StepDefinition].
 *
 * - [path]: workspace-relative path of the file to hash.
 * - [algorithm]: only `"SHA-256"` (default) and `"SHA-1"` are accepted in this
 *   first slice. We refuse any other name so a caller asking for `MD5` (which
 *   is already in `LEGACY_PLUGIN_IDS` rejection territory) is rejected at
 *   admission time rather than silently substituted.
 *
 * Hashing a 0-byte file is valid and yields the well-known empty-input digest.
 */
@Serializable
data class Sha256Input(
    val path: String,
    val algorithm: String = "SHA-256",
)
