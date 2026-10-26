package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import java.nio.file.Path

/**
 * What one durable shell execution asked for.
 *
 * ## Why it is a value
 *
 * The launch takes the REQUEST and not its pieces, so it cannot be called with the environment of
 * one execution and the sandbox of another. That was the reason it was introduced as a value, and
 * it stays one.
 *
 * ## Why it lives here
 *
 * Two collaborators need it — the launcher, which hands it to the child, and the executor, which
 * coordinates the execution around it — and neither of them owns it. Declaring it beside the
 * façade would make the launcher depend on the façade's file for the shape of its own work; the
 * dependency is on the REQUEST, and the request is declared here.
 *
 * The two projections it carries are about what the execution did with the child's bytes (the
 * value file or the Jenkins log) and about what the watchdog does when the deadline passes. They
 * are declared here with the request that carries them, and they are not used by the launcher: the
 * launcher does not decide what a timeout means.
 */
internal data class DurableShellExecutionRequest(
    /** The deadline of the execution, in milliseconds. Zero means no deadline. */
    val timeoutMs: Long,
    /** The caller's variables, still secret-bearing. */
    val env: Map<String, SecretHandle>,
    /** The active sandbox profile, its allow-extra set and its path keep-set. */
    val sandbox: SandboxConfig,
    /** Where the child runs, or null to run it in the control directory. */
    val workspaceRoot: Path?,
    /** Whether the child's stdout is captured as the step's typed value. */
    val captureStdout: Boolean,
    /** Where the child's transcript ends up once the execution has finished. */
    val outputProjection: DurableShellOutputProjection,
    /** What the watchdog does when the deadline passes. */
    val timeoutCompletion: DurableShellTimeoutCompletion,
)

/** Where a finished execution's transcript is projected. */
internal enum class DurableShellOutputProjection {
    /** The captured value file: the stdout the step returned. */
    CAPTURE_FILE,

    /** The Jenkins log: the transcript kept for a reader. */
    JENKINS_LOG,
}

/** What the watchdog does when the deadline passes. */
internal enum class DurableShellTimeoutCompletion {
    /** The verdict follows the cookie-scan kill's own result. */
    KILL_RESULT,

    /** The verdict follows the attempt: a failed kill is still a timeout. */
    WATCHDOG_ATTEMPT,
}
