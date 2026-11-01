package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.CapturedRun
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.task.runCaptured

/**
 * Runs one command and publishes what it printed to the host's transcript.
 *
 * ## Why this is ONE function and not one per caller
 * The capture, the publication and the refusal check are one fact split in three: a command's
 * output is captured, published, and then checked for a refusal. Doing that in each caller
 * would leave three places where the check could be forgotten, and a forgotten check is a
 * checkout that reports a transcript nobody can read.
 *
 * ## Why the refusal ends the command
 * The bytes left the pipe when the sink refused them, so this command's transcript is short and
 * plausible. The command's own answer is still true — the process did exit — but the record of
 * WHY is gone. The refusal therefore travels as [GitTranscriptRefused] and the caller ends in
 * failure: what the run could not record, the run does not claim.
 *
 * @param request the command to run.
 * @param transcript the host's destination, or null when this composition has none. Null means
 *   "there is nowhere to publish", not "publish nothing quietly": the capture is still whole.
 */
internal suspend fun DurableTaskRuntime.runCapturedTo(
    request: TaskExecutionRequest,
    transcript: GitTranscript?,
): CapturedRun {
    val writer = transcript?.let { GitTranscriptWriter(it) }
    val captured = try {
        runCaptured(request, writer)
    } finally {
        // The tail is flushed BEFORE the refusal is read: the flush is the last write, and a
        // write is where a refusal appears. Reading earlier would report a transcript that
        // looked complete when its last write had failed.
        writer?.finish()
    }
    writer?.refusal()?.let { throw GitTranscriptRefused(it) }
    return captured
}
