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
     * The child was killed, and the kill was the point.
     *
     * ## Why this is not [TimedOut] and not [Exited]
     *
     * A kill-versus-timeout test asks for a process to die, so its `destroyForcibly().waitFor()`
     * returns **whether the kill landed**, not an exit code. Reporting it as a timeout would be a
     * lie about what happened — nothing ran out of time — and reporting it as an exit would be a
     * lie about what the number means, since a SIGKILLed process has an exit code of its own that
     * says nothing about the work it was doing.
     *
     * The case exists so that a caller who kills a process on purpose is forced to decide what a
     * kill means for its assertion, which is usually "did the thing I was watching survive it".
     *
     * @property hadAlreadyExited true when the process finished on its own between the decision to
     *   kill it and the kill landing. A test that kills a process it expected to be alive needs to
     *   know which of the two happened, or it passes for the wrong reason
     * @property exitCode the code the process finished with, or `null` when the kill ended it and
     *   there was therefore no code of its own to report
     */
    data class Killed(
        override val command: List<String>,
        val pid: Long,
        val descendantsTerminated: Int,
        val hadAlreadyExited: Boolean,
        val exitCode: Int?,
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
            is Killed -> "$command (pid $pid) killed with $descendantsTerminated descendant(s); " +
                "had already exited=$hadAlreadyExited, exitCode=$exitCode"
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
    is SubprocessOutcome.Killed -> throw AssertionError(
        "expected the command to finish on its own, but it was killed: $description",
    )
}

/**
 * The kill that was asked for, with a failure saying what happened instead.
 *
 * The mirror of [requireExited] for a test whose subject is a process dying. Forgetting to handle
 * the other four outcomes is a compile error here too, which is the whole reason it exists: the
 * interesting question in a kill test is usually not "did it exit 0" but "was it still alive when
 * we killed it", and that is [SubprocessOutcome.Killed.hadAlreadyExited].
 */
fun SubprocessOutcome.requireKilled(): SubprocessOutcome.Killed = when (this) {
    is SubprocessOutcome.Killed -> this
    is SubprocessOutcome.Exited -> throw AssertionError(
        "expected to kill a running process, but $description",
    )
    is SubprocessOutcome.TimedOut -> throw AssertionError(
        "expected to kill a running process, but it ran out of time first: $description",
    )
    is SubprocessOutcome.NotStarted -> throw AssertionError(
        "expected to kill a running process, but $description",
    )
    is SubprocessOutcome.Interrupted -> throw AssertionError(
        "expected to kill a running process, but $description",
    )
}
