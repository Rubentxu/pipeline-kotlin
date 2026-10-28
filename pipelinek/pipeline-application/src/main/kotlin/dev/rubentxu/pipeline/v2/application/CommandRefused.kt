package dev.rubentxu.pipeline.v2.application

/**
 * A refusal the operator has already been told about, carrying the exit code it means.
 *
 * ## WHY THE REASON IS NOT CARRIED HERE
 * The place that knows WHY a request was refused is the place that says it: the reason is
 * printed where the refusal is decided, in the operator's words, exactly as it was before this
 * type existed. This exception carries only what the process owner cannot know on its own —
 * the exit code the refusal means — so the text the operator reads is decided in one place and
 * cannot drift away from the decision that produced it.
 *
 * ## WHY THE PROCESS DOES NOT END HERE
 * A refusal is a decision about the run, not a reason to abandon what the run already owns.
 * A process that ends while it holds a lock does not release it: the operating system does,
 * and the record the lock left behind still claims a live owner, so the run reads as owned by
 * a process that no longer exists. Ending the process at the point of the refusal skips the
 * release of every resource acquired before it.
 *
 * So the refusal UNWINDS instead of ending: the branch that owns resources gives them back
 * and reports the code, and the branch that owns nothing ends the process. Either way the
 * operator reads the same text and the process ends with the same code.
 */
internal class CommandRefused(val exitCode: Int) :
    RuntimeException("the command was refused with exit code $exitCode")
