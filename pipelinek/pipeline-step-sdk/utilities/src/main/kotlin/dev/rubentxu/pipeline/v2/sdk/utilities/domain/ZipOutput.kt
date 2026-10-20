package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * `core-utils.zip` typed Output (LFC-2E2 utilities, Slice 2 / S2.4).
 *
 * Reports the archive's on-disk location, byte size, SHA-256 digest
 * and entry count. Useful for chaining into `core-utils.sha256` for
 * verification or into `core-utils.writeJson` for log enrichment.
 */
@Serializable
data class ZipOutput(
    val absolutePath: String,
    val byteSize: Long,
    val sha256Hex: String,
    val entryCount: Int,
)
