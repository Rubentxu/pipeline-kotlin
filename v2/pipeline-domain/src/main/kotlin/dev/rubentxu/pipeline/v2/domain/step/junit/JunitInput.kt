package dev.rubentxu.pipeline.v2.domain.step.junit

/**
 * Typed input envelope of `core.junit`.
 *
 * The handler resolves [glob] against the workspace root; the codec
 * does NOT pre-resolve (the workspace path is capability-injected at
 * handler time). A glob that matches no file is a typed failure
 * (MISSING_FILE), not a partial report.
 *
 * @property glob path pattern matching exactly one JUnit XML file.
 *               The cycle's first release supports glob patterns that
 *               resolve to a single file; multi-match is the caller's
 *               job (invoke the Step N times).
 */
data class JunitInput(
    val glob: String,
) {
    init {
        require(glob.isNotEmpty()) { "glob must not be empty" }
        require(glob.isNotBlank()) { "glob must not be blank" }
    }
}
