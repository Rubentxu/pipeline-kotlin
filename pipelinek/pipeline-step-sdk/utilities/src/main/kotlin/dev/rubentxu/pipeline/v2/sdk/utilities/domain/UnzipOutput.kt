package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * `core-utils.unzip` typed Output (LFC-2E2 utilities, Slice 2 / S2.5).
 *
 * Three fields are all nullable because the operation mode is what
 * determines which one is populated. The codec rejects envelopes
 * where more than one is set or where none is set.
 */
@Serializable
data class UnzipOutput(
    val extracted: ExtractedFiles? = null,
    val readEntries: Map<String, String>? = null,
    val testReport: TestReport? = null,
)

/**
 * Closed ADT for a single extracted entry. Modelled after Jenkins'
 * FileWrapper, but typed.
 */
@Serializable
data class ExtractedFiles(
    val destination: String,
    val files: List<ExtractedFile>,
)

@Serializable
data class ExtractedFile(
    val name: String,
    val path: String,
    val size: Long,
)

/**
 * Result of `unzip zipFile: '...', test: true`. `ok` is true when
 * every entry's CRC32 matched; `badEntries` lists the names that
 * failed.
 */
@Serializable
data class TestReport(
    val ok: Boolean,
    val entryCount: Int,
    val badEntries: List<String>,
)
