package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import kotlinx.serialization.Serializable

/**
 * `core-utils.zip` typed Input (LFC-2E2 utilities, Slice 2 / S2.4).
 *
 * Jenkins reference: pipeline-utility-steps-plugin::ZipStep.
 * Behaviour summary in `docs/v2/07-uat/S2_ZIP_JENKINS_REFERENCE.md`.
 *
 * The shape is closed: exactly one of the three [ZipSources] cases is
 * chosen by the caller. The dst `path` is workspace-relative unless
 * absolute.
 */
@Serializable
data class ZipInput(
    val path: String,
    val overwrite: Boolean = false,
    val sources: ZipSources,
) {
    init {
        require(path.isNotBlank()) {
            "core-utils.zip: path must not be blank"
        }
    }
}

/**
 * Closed Sources ADT for the zip step. Modelled after Jenkins' single
 * `archive: '...'` string, but typed: each case carries its own
 * payload and mixing them is unrepresentable at compile time.
 *
 * - [FromGlob] resolves a glob against the workspace root and walks
 *   every match (file or directory, recursively).
 * - [FromDirectory] archives a literal directory subtree.
 * - [FromFiles] archives a list of literal file paths, each resolved
 *   against the workspace root.
 */
@Serializable
sealed interface ZipSources {
    @Serializable
    data class FromGlob(val glob: String) : ZipSources {
        init {
            require(glob.isNotBlank()) {
                "core-utils.zip: glob must not be blank"
            }
        }
    }

    @Serializable
    data class FromDirectory(val directory: String) : ZipSources {
        init {
            require(directory.isNotBlank()) {
                "core-utils.zip: directory must not be blank"
            }
        }
    }

    @Serializable
    data class FromFiles(val paths: List<String>) : ZipSources {
        init {
            require(paths.isNotEmpty()) {
                "core-utils.zip: paths list must not be empty"
            }
        }
    }
}
