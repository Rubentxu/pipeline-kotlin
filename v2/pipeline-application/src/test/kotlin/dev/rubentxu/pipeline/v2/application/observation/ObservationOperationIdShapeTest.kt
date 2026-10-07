package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * OBS-E3: an operation id is an OPERATION identity, and the identity it gets confused with produces
 * a reader that silently finds nothing.
 *
 * ## The defect this pins
 *
 * `OutputStreamAddress`'s KDoc used to state that "the canonical operation id is `build/sh-0` — stage
 * and step", and worked an example from it: the stdout stream is `run-7/build/sh-0/stdout`, four
 * segments. Both halves were wrong, and together they were a trap for exactly the code this block
 * was about to write.
 *
 * Production mints the operation id with [OpId.format], whose shape is
 * `{runId}-s{stageIndex}-{stepIndex}[-b{branch}][-bp{N}-{childIndex}:{pluginStepId}...]`. An `sh` in
 * stage 0, step 0 of run `run-7` therefore writes to `run-7/run-7-s0-0/stdout`: **three** segments,
 * with the run id appearing both as the run and inside the operation.
 *
 * `build/sh-0` is a **StepId** — the definition-local node id, and the value that reaches the journal
 * as `OperationInput.stepId`. It is a real identity with a real consumer; it is simply not this one.
 *
 * ## Why this is worth a test at all, given that `parse` accepts both
 *
 * Because `parse` accepts both, and the failure is invisible. A reader that built `build/sh-0` from a
 * step event and asked the store for that stream gets nothing for every step in the run — which reads
 * as "this step printed nothing", a completely ordinary fact, rather than as the mistake it is. The
 * rows therefore do not assert that the step id is rejected; they assert that the two identities
 * resolve to **different** streams, so the mistake surfaces as a mismatch instead of passing unnoticed.
 *
 * ## Harness fidelity
 *
 * This crosses the productive authority on the derivation side: [OpId] is the same type
 * `ShExecution` and `StepDispatchEngine` call to address their output, and [OutputStreamAddress.of] is
 * what `OutputPlaneProvider.streamsOf` builds from it. It does NOT fork a real `sh`, so the byte rows
 * live in `ObsC23ChannelSeparationUatTest`; what is pinned here is the SHAPE, which is a pure
 * derivation and would be identical in a fork.
 *
 * ## Mutation
 *
 * M-E4 — replacing the operation id with the step id `build/sh-0` — is expected to kill ROW-SHAPE-1
 * and ROW-DIVERGE-2. M-E5 — writing `build/sh-0` back into the KDoc as the canonical operation id —
 * is expected to kill ROW-KDOC-3, which is a scan rather than a behavioural row because the defect is
 * in a comment and no runtime assertion can observe it.
 */
class ObservationOperationIdShapeTest {

    private val runId = "run-7"

    /** The StepId that must never be presented as an operation id. Named so the scan can quote it. */
    private val STEP_ID = "build/sh-0"

    /**
     * The identity production writes and reads under. Kept as one named function so the mutation has a
     * single site to change, and so a reader of this file can see the derivation rather than infer it.
     */
    private fun operationIdOf(step: OpId): String = step.format()

    @Test
    fun `ROW-SHAPE-1 - the operation id is the operation identity and not the step identity`() {
        val step = OpId(runId = runId, stageIndex = 0, stepIndex = 0)
        val operationId = operationIdOf(step)

        assertEquals(
            "run-7-s0-0",
            operationId,
            "the operation id production writes is OpId.format(), so a stream id is " +
                "run-7/run-7-s0-0/stdout with THREE segments — not the four-segment " +
                "run-7/build/sh-0/stdout that OutputStreamAddress's KDoc used to claim. If this " +
                "changed, every stream id already on disk would stop being an id production reads " +
                "back, and the failure would surface as a silent empty transcript, not an error.",
        )

        val stream = OutputStreamAddress.of(runId, operationId, OutputChannel.STDOUT).stream
        assertEquals(
            "run-7/run-7-s0-0/stdout",
            stream.value,
            "the stream is {runId}/{operationId}/{channel} with the operation id from OpId.format().",
        )
        assertEquals(
            3,
            stream.value.split('/').size,
            "a real operation id carries no separator, so a real stream has three segments. This " +
                "is a property of today's plugin step ids, NOT a guarantee: PluginStepId is only " +
                "required to be non-blank, so a bodyPath segment could make the middle span " +
                "several segments. That is why parse reads from the ends rather than counting.",
        )
    }

    @Test
    fun `ROW-DIVERGE-2 - the step id and the operation id address different streams`() {
        val operationId = operationIdOf(OpId(runId = runId, stageIndex = 0, stepIndex = 0))

        // The step id is a real identity with a real consumer — it is what reaches the journal as
        // OperationInput.stepId — which is precisely why conflating the two is easy.
        val stepId = "build/sh-0"

        val byOperation = OutputStreamAddress.of(runId, operationId, OutputChannel.STDOUT).stream
        val byStep = OutputStreamAddress.of(runId, stepId, OutputChannel.STDOUT).stream

        assertNotEquals(
            byOperation,
            byStep,
            "the operation id ($operationId) and the step id ($stepId) must address different " +
                "streams. They are different identities, and the step id is the one a step event " +
                "carries by name — which is why a reader that builds a stream from an event without " +
                "going through the journal finds nothing for every step in the run and reads it as " +
                "'this step printed nothing'.",
        )

        // Both are well-formed addresses. Nothing REFUSES the wrong one: it is simply a stream that
        // was never written, which is why the divergence has to be caught at derivation time.
        val parsedByStep = OutputStreamAddress.parse(byStep)
        assertTrue(
            parsedByStep != null && parsedByStep.operationId == stepId,
            "the step-id stream is ALSO well-formed, and parse recovers the step id verbatim. This " +
                "is exactly why the mistake is invisible: it is not a malformed id, it is a " +
                "well-formed id for a stream nobody wrote, and no parser can tell it apart from a " +
                "correct one without asking which operations exist. Only the journal can.",
        )
    }

    @Test
    fun `ROW-KDOC-3 - the output contract names the step id only when it says it is a StepId`() {
        val contract = locateOutputContract()

        // Naming `build/sh-0` is allowed — the contract NEEDS it to warn readers off it. What is
        // forbidden is naming it while presenting it as the operation id. So the rule is not
        // "the string may not appear" but "every line carrying it must label it".
        //
        // This scan deliberately does NOT skip comment lines. The first version of this row did, by
        // habit copied from ObsC23NoChannelFusionFitnessTest, where skipping comments is right
        // because a comment legitimately names the call it forbids. Here it was exactly backwards:
        // the defect lives in the KDoc, so skipping KDoc lines made the row unable to fail. It
        // passed against the very mutation it was written to catch — see the mutation evidence.
        val offenders = contract.readLines()
            .withIndex()
            .filter { (_, line) -> line.contains(STEP_ID) }
            .filterNot { (_, line) -> line.contains("StepId") || line.contains("step id") }
            .map { (index, line) -> "line ${index + 1}: ${line.trim()}" }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "the published output contract may mention the step id $STEP_ID, but only while " +
                "labelling it a StepId. Naming it without that label is how it became the " +
                "documented canonical operation id, and a reader who copies an example that is " +
                "well-formed but names nothing that exists finds no bytes for any step in the run " +
                "and reads that as a quiet step rather than as a mistake. Found: $offenders",
        )
    }

    @Test
    fun `ROW-KDOC-4 - the documented shape matches the derived one`() {
        val contract = locateOutputContract().readText()

        // The KDoc must carry a REAL example, because the failure mode was an example that parsed
        // fine and described nothing that exists. An example that is never checked is how
        // `build/sh-0` survived long enough to be believed.
        assertTrue(
            contract.contains("run-7/run-7-s0-0/stdout"),
            "the contract must document the operation id production actually mints " +
                "(OpId.format() -> run-7-s0-0), so the shape a reader copies is one that exists.",
        )
    }

    @Test
    fun `ROW-PARSE-5 - parse round-trips an operation id that spans several segments`() {
        // Production plugin step ids carry no '/', so this shape does not occur today. PluginStepId
        // is only required to be non-blank, so it could — and a bodyPath segment is exactly where it
        // would arrive. This is the case that would break on the day it happened if parse counted
        // segments, which is why ROW-SHAPE-1's count of three is a fact and not a promise.
        val multiSegmentOperationId = "run-7-s0-1-bp1-0:build/sh-0"

        val parsed = requireNotNull(OutputStreamAddress.parse(streamOf(multiSegmentOperationId, OutputChannel.STDERR))) {
            "an operation id containing a separator still parses, because parse reads the run from " +
                "the first segment and the channel from the last and keeps everything between " +
                "verbatim. A parser that required exactly three segments would return null here " +
                "and silently drop a real stream the day a plugin step id carried one."
        }

        assertEquals(
            multiSegmentOperationId,
            parsed.operationId,
            "the separator inside the operation id is preserved, which is what makes " +
                "{runId}/{operationId}/{channel} injective instead of merely plausible.",
        )
    }

    @Test
    fun `ROW-PARSE-6 - a pre-OBS-C2 transcript stream is not a channel stream`() {
        assertNull(
            OutputStreamAddress.parse(OutputStreamId("run-7/run-7-s0-0/transcript")),
            "a `transcript` stream has no channel, so parsing it as one would invent an " +
                "attribution for bytes whose channel was never recorded. Refusing is the honest " +
                "answer; defaulting to STDOUT would be a fabrication.",
        )
    }

    @Test
    fun `ROW-PARSE-7 - the ends are what parse uses`() {
        val operationId = operationIdOf(OpId(runId = runId, stageIndex = 0, stepIndex = 1))

        val parsed = requireNotNull(OutputStreamAddress.parse(streamOf(operationId, OutputChannel.STDERR))) {
            "a real operation id parses."
        }

        assertEquals(runId, parsed.runId, "the first segment is the run.")
        assertEquals(operationId, parsed.operationId, "everything between the ends is the operation id.")
        assertEquals(OutputChannel.STDERR, parsed.channel, "the last segment is the channel.")
    }

    private fun streamOf(operationId: String, channel: OutputChannel): OutputStreamId =
        OutputStreamAddress.of(runId, operationId, channel).stream

    private fun locateOutputContract(): File {
        val candidates = listOf(
            "../pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputChannel.kt",
            "pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputChannel.kt",
        )
        return candidates
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error(
                "could not locate OutputChannel.kt from ${File(".").absolutePath}; tried $candidates",
            )
    }
}