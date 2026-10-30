package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * What the launch knows about the wrapper it started, at one moment.
 *
 * ## What this owns
 *
 * The READING of the wrapper's state, and nothing else. Two facts carry it: the cookie the
 * wrapper publishes, and the process the launcher holds. The decision that turns a reading into a
 * terminal belongs to [ShellTerminalDecision], and the wait that keeps asking belongs to
 * [ShellResultPoll].
 *
 * ## Why the process is a fact and not the absence of a file
 *
 * The launcher starts the wrapper with `setsid`, and `setsid` execs in place when its caller is
 * not already a process group leader. A JVM's child never is one: it inherits the JVM's group and
 * receives a fresh pid, so it can never lead that group and `setsid` has no reason to fail. The
 * handle the launcher keeps IS the wrapper, and the wrapper's own shell writes its pid into the
 * cookie. So the cookie and the handle are the same identity seen two ways: the cookie says the
 * wrapper published itself, and the handle says whether it is still there.
 *
 * Before this, the launch established the wrapper's readiness by starting a probe process and
 * sleeping a fixed hundred milliseconds, and then INFERRED the wrapper's state from the absence of
 * the cookie. An inference is a guess: a wrapper that had not written its cookie yet was read as a
 * wrapper that never started, and the terminal said so. The two facts remove the guess.
 *
 * ## The three readings
 *
 * - [Identified]: the wrapper published its identity and is running;
 * - [Running]: the wrapper is running and has not published its identity yet — it is starting;
 * - [Ended]: the wrapper is gone, and [exitCode] is the code it left with, when the platform
 *   reported one.
 */
internal sealed interface ShellAvailability {

    /** The wrapper published its identity and is still running. */
    data object Identified : ShellAvailability

    /** The wrapper is running and has not published its identity yet. */
    data object Running : ShellAvailability

    /**
     * The wrapper ended without publishing a result.
     *
     * @param exitCode the code the wrapper exited with, or null when the platform did not report
     *   one. A code nobody observed is never invented.
     */
    data class Ended(val exitCode: Int?) : ShellAvailability
}

/**
 * The rule that reads the wrapper's state from the two facts the launch holds.
 *
 * Pure: it reads nothing itself, and the same two facts always produce the same reading. That is
 * what lets the reading be observed without a child on disk.
 *
 * @param identityPublished whether the wrapper published its cookie.
 * @param wrapperEnded whether the wrapper's process is gone.
 * @param wrapperExitCode the code the wrapper exited with, when it is gone and the platform
 *   reported one.
 */
internal fun shellAvailability(
    identityPublished: Boolean,
    wrapperEnded: Boolean,
    wrapperExitCode: Int? = null,
): ShellAvailability = when {
    // The identity is the strongest fact, but only while the wrapper is THERE. A cookie that
    // outlives the wrapper is a wrapper that ended without publishing a result: reading the
    // identity as proof of life kept the wait open until the ceiling and reported that death as
    // a timeout, instead of as the wrapper's own reason for failing.
    identityPublished && !wrapperEnded -> ShellAvailability.Identified
    // No identity yet, but the wrapper is still there: it is starting. Reading this as an ending
    // reported a wrapper that had not finished starting as one that never started.
    !wrapperEnded -> ShellAvailability.Running
    // The wrapper is gone, whether or not it published its identity: it ended without publishing
    // a result, and the code it left with is its own reason for failing.
    else -> ShellAvailability.Ended(wrapperExitCode)
}
