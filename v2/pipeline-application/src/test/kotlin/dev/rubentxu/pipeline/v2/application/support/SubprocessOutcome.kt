package dev.rubentxu.pipeline.v2.application.support

import java.time.Duration

/**
 * What happened to a child process under [Subprocess]'s ownership.
 *
 * ## Why a closed ADT and not an exit code
 *
 * The shape this replaces was `val exitCode = process.waitFor()` returning an `Int`. Under that
 * shape a hung process and a fast failure are the same line of code, so a hang produced no evidence
 * at all — the build simply stopped, and whoever read it could not tell a product defect from a
 * broken harness.
 *
 * Four outcomes, each carrying what a reader needs to decide what it means:
 *
 * ```text
 * Exited       the child finished; this is the only case that carries an exit code
 * TimedOut     it did not, and here is the pid, the bound, what it had printed, and what was killed
 * NotStarted   it never ran, so the command itself is what is wrong
 * Interrupted  the test thread was interrupted; the tree was killed before unwinding
 * ```
 *
 * There is deliberately no "still running, carry on" outcome. A test that leaves a process behind
 * is the harness defect this type exists to make impossible to express.
 */
sealed interface SubprocessOutcome {

    /** The command that was attempted, for every failure message. */
    val command: List<String>

    /** The child ran to completion. */
    data class Exited(
        override val command: List<String>,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : SubprocessOutcome

    /**
     * The child outlived its bound and was killed.
     *
     * @property stillAliveBeforeKill whether the process was alive when the bound expired, recorded
     *   separately from [descendantsTerminated] so "hung" and "left orphans" stay distinguishable
     * @property stdout whatever had been emitted when the kill happened, which is usually the
     *   diagnosis rather than the payload
     */
    data class TimedOut(
        override val command: List<String>,
        val pid: Long,
        val timeout: Duration,
        val stillAliveBeforeKill: Boolean,
        val descendantsTerminated: Int,
        val stdout: String,
        val stderr: String,
    ) : SubprocessOutcome

    /** The process was never created, so nothing ran and nothing was killed. */
    data class NotStarted(
        override val command: List<String>,
        val cause: Throwable,
    ) : SubprocessOutcome

    /** The test thread was interrupted; the tree was killed before this was returned. */
    data class Interrupted(
        override val command: List<String>,
        val pid: Long,
        val destroyed: Subprocess.DestroyedTree,
        val cause: InterruptedException,
    ) : SubprocessOutcome

    /**
     * The outcome as a sentence naming the command.
     *
     * Every assertion that uses this helper should be able to print the command, because "expected
     * exit 0, got 1" without the invocation that produced it costs the reader a rebuild to
     * reproduce.
     */
    val description: String
        get() = when (this) {
            is Exited -> "$command exited ${exitCode}"
            is TimedOut -> "$command (pid $pid) exceeded $timeout while still alive; killed with " +
                "$descendantsTerminated descendant(s). Last stdout: ${stdout.takeLast(DIAGNOSTIC_TAIL_CHARS).trim()}. " +
                "Last stderr: ${stderr.takeLast(DIAGNOSTIC_TAIL_CHARS).trim()}"
            is NotStarted -> "$command was never started: $cause"
            is Interrupted -> "$command (pid $pid) was interrupted and its tree killed: $cause"
        }

    private companion object {
        /** Enough of a stream to identify where the child got to, little enough to stay readable. */
        const val DIAGNOSTIC_TAIL_CHARS = 800
    }
}

/**
 * [SubprocessOutcome.Exited] with its exit code, or a failure that says what happened instead.
 *
 * Exists so that the many call sites which only care about the code do not each grow a
 * `when` with an identical "unexpected" arm, and — more importantly — so that forgetting to handle
 * the other three outcomes is a **compile** error rather than a runtime surprise.
 */
fun SubprocessOutcome.requireExited(): SubprocessOutcome.Exited = when (this) {
    is SubprocessOutcome.Exited -> this
    is SubprocessOutcome.TimedOut -> throw AssertionError(
        "expected the command to finish, but $description",
    )
    is SubprocessOutcome.NotStarted -> throw AssertionError(
        "expected the command to finish, but $description",
    )
    is SubprocessOutcome.Interrupted -> throw AssertionError(
        "expected the command to finish, but $description",
    )
}