package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * `core-utils.findFiles` typed Output (LFC-2E2 utilities, Slice 2 / S2.3).
 *
 * Mirrors the Jenkins `FileWrapper` shape: every match produces a
 * [FileEntry] with name, workspace-relative path, directory flag,
 * length and last-modified epoch millis.
 */
@Serializable
data class FindFilesOutput(
    val basePath: String,
    val patternEcho: FindFilesPattern,
    val files: List<FileEntry>,
)

/**
 * Closed ADT for an entry returned by findFiles. Modelled after
 * Jenkins' `FileWrapper` but typed as a data class: equality is total
 * over the five fields, not just `path`.
 */
@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val length: Long,
    val lastModified: Long,
)
