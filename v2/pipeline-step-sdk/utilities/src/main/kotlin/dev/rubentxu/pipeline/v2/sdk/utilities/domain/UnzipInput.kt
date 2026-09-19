package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * `core-utils.unzip` typed Input (LFC-2E2 utilities, Slice 2 / S2.5).
 *
 * Jenkins reference: pipeline-utility-steps-plugin::UnZipStep.
 * Behaviour summary in `docs/v2/07-uat/S2_UNZIP_JENKINS_REFERENCE.md`.
 *
 * Mode is closed: a caller picks exactly one of the three
 * [UnzipMode] cases. Mixing `read` and `test` is unrepresentable at
 * compile time. The mode is a sibling of `glob` and `destination` so
 * that the entire operational shape is one type.
 */
@Serializable
data class UnzipInput(
    val path: String,
    val destination: String? = null,
    val glob: String? = null,
    val mode: UnzipMode = UnzipMode.Extract,
) {
    init {
        require(path.isNotBlank()) {
            "core-utils.unzip: path must not be blank"
        }
        if (destination != null) {
            require(destination.isNotBlank()) {
                "core-utils.unzip: destination must not be blank when present"
            }
        }
        if (glob != null) {
            require(glob.isNotBlank()) {
                "core-utils.unzip: glob must not be blank when present"
            }
        }
    }
}

/**
 * Closed mode ADT for the unzip step. Exactly one of three cases:
 *
 * - [Extract] writes the archive's files to disk (default Jenkins
 *   behaviour).
 * - [Read] reads the matched entries as UTF-8 strings and returns them
 *   in [UnzipOutput.readEntries].
 * - [Test] performs a CRC32 sweep without writing, returning
 *   [UnzipOutput.testReport] with the verification outcome.
 */
@Serializable
sealed interface UnzipMode {
    @Serializable
    data object Extract : UnzipMode

    @Serializable
    data object Read : UnzipMode

    @Serializable
    data object Test : UnzipMode
}
