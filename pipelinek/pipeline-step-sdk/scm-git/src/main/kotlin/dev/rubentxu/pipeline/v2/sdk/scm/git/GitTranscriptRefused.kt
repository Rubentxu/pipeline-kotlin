package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.sdk.runtime.durable.OUTPUT_PERSISTENCE_REFUSED
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ProcessOutputRefusal

/**
 * The host could not persist what a git command printed.
 *
 * ## What this is
 * The bytes left the pipe when the sink was handed them, so nothing can recover them: the
 * transcript of that command ends up short, ordered and plausible — the hardest kind of wrong.
 * A checkout that carried on would report a failure whose cause nobody can read, or a success
 * whose evidence nobody can check. Failing keeps the two together: what the run could not
 * record, the run does not claim.
 *
 * ## Why the same code the shell uses
 * The shell reports this exact fact with [OUTPUT_PERSISTENCE_REFUSED] and
 * `FailureKind.INFRASTRUCTURE`. A reader who knows one substrate must not have to learn a second
 * vocabulary for the same fact, so this exception carries that code in its message and the Step
 * classifies it the same way.
 *
 * @param refusal what the sink could not make durable. Its `detail` describes WHAT failed and
 *   never quotes a process byte: the detail reaches a durable event.
 */
internal class GitTranscriptRefused(
    val refusal: ProcessOutputRefusal,
) : RuntimeException(
    "$OUTPUT_PERSISTENCE_REFUSED: the git transcript could not be persisted: ${refusal.detail}",
) {
    init {
        // A write that failed carries the Throwable that failed it; a drain that did not
        // complete carries no failure at all. Keeping the Throwable when there is one costs
        // nothing and saves the operator a trip to the log.
        if (refusal is ProcessOutputRefusal.WriteFailed) initCause(refusal.cause)
    }
}
