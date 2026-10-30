package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * The reading of the code a child left with.
 *
 * ## Why the code needs a reader
 *
 * The platform reports the code of a child that ended to the object that STARTED it, and to
 * nothing else. A handle says whether a process is alive and who its parent is; it never says
 * what the process exited with. So the code of a child that ended cannot be read from the
 * identity the kill uses — it can only be read from the object the launch started, and the
 * launch is where that object lives.
 *
 * The reading is therefore a value the launch hands over, and this is what it is.
 *
 * ## Why a code that was not observed is never invented
 *
 * A child that has not ended yet, and a platform that does not report codes, both leave the
 * reason unknown. The reading says so with null, and null is not zero: a wrapper that ended
 * without a code is a different fact from a wrapper that ended with one.
 */
internal fun interface ShellExitCode {

    /**
     * The code the child left with, or null when the platform reported none.
     */
    fun read(): Int?
}

/**
 * The code the child left with, or null when the platform has not reported one.
 *
 * The platform refuses to say the code of a child that has not ended: it throws rather than
 * report a number that does not exist yet. That refusal is the same answer as "the platform
 * does not report codes" — the code is unknown — so it is read as null rather than as a failure.
 */
internal fun Process.exitCodeOrNull(): Int? = try {
    exitValue()
} catch (_: IllegalThreadStateException) {
    null
}
