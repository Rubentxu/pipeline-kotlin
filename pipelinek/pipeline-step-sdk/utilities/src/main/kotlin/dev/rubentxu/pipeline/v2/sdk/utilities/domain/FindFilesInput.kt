package dev.rubentxu.pipeline.v2.sdk.utilities.domain

import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import kotlinx.serialization.Serializable

/**
 * `core-utils.findFiles` typed Input (LFC-2E2 utilities, Slice 2 / S2.3).
 *
 * Jenkins reference: pipeline-utility-steps-plugin::FindFilesStep.
 * Behaviour summary: enumerate entries under the workspace, optionally
 * filtered by a glob. An empty pattern lists direct children only.
 *
 * The shape is closed: every findFiles invocation picks exactly one of
 * the two Pattern cases. Mixing them is unrepresentable at compile time.
 */
@Serializable
data class FindFilesInput(
    val base: String,
    val pattern: FindFilesPattern = FindFilesPattern.None,
) {
    init {
        require(base.isNotBlank()) {
            "core-utils.findFiles: base path must not be blank"
        }
    }

    /** The dispatch policy for this step (E-EM-11 NEVER-1). */
    val replayPolicy: ReplayPolicy get() = ReplayPolicy.NEVER
}

/**
 * Closed Pattern ADT for findFiles. Modelled after Jenkins' `glob` /
 * `excludes` setters with two observations:
 *
 *   - `Pattern.None` -> direct children only (Jenkins default).
 *   - `Pattern.Glob(glob, excludes)` -> recursive scan filtered by an
 *     include glob and an optional exclude glob.
 */
@Serializable
sealed interface FindFilesPattern {
    /** No include pattern; list direct children of [base] only. */
    @Serializable
    data object None : FindFilesPattern

    /**
     * Include by glob, then optionally drop by another glob.
     * `excludes = null` means "do not exclude anything".
     */
    @Serializable
    data class Glob(
        val glob: String,
        val excludes: String? = null,
    ) : FindFilesPattern {
        init {
            require(glob.isNotBlank()) {
                "core-utils.findFiles: glob pattern must not be blank"
            }
        }
    }
}
