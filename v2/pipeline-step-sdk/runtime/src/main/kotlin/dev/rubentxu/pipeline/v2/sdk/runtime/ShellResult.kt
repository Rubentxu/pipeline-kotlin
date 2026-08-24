package dev.rubentxu.pipeline.v2.sdk.runtime

/**
 * Result of a shell execution.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
