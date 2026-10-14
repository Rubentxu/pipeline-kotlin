package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.MainConsoleCli.ConsoleFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * R4 / WU-CON-2.2 — content filters on `pipelinek console`.
 *
 * ## What this slice ships
 *
 * Four flags on `pipelinek console`:
 *
 * ```text
 * --stage <name>     content filter: only emit frames whose opId's stageIndex is in the set
 * --step <name>      content filter: only emit frames whose opId's stepIndex is in the set
 * --kind <kind>      content filter: admitted; per-frame kind resolution is a CON-2.3 follow-up
 * --run <runId>      replaces the positional <runId> argument (mutually exclusive)
 * ```
 *
 * The filters live on a new fourth case of [ConsoleReadRequest] —
 * [ConsoleReadRequest.FramesFiltered] — paired with a closed [ConsoleFrameFilters] ADT. The
 * existing [ConsoleReadRequest.Frames] case stays the "no filters" path; the dispatch in
 * [MainConsoleCli.main] is exhaustive over the four cases.
 *
 * ## Why the kind axis is a pass-through for now
 *
 * The verb main derives the per-frame match from the frame's stream id
 * (`<runId>/<opId>/<channel>`), and the opId segment carries the stageIndex and stepIndex
 * directly. The kind axis names event kinds (StepStarted, StepFinished, StepFailed, …) and the
 * per-frame path does not record those; wiring it requires the events journal resolution that
 * the WU-R4.6 receipt names as the next slice. The contract this slice fixes — "an empty axis
 * is a no-op, a non-empty axis is a conjunct" — is what the next slice extends without
 * breaking today's matches.
 *
 * ## What is NOT in this slice
 *
 * - NOT a `--follow` flag on the per-frame path (CON-2.3).
 * - NOT a real-fixture end-to-end test (the WU-R4.5 canary is still `@Disabled` against HEAD
 *   for the pre-existing `pipelinek run` defect; the same defect blocks the installDist canary
 *   for `console --frames`).
 */
@Timeout(60)
@DisplayName("R4 / WU-CON-2.2 — console --stage/--step/--kind/--run content filters")
class ConsoleFrameFiltersTest {

    // ------------------------------------------------------------------ HF0 closed ADT

    @Test
    fun `ConsoleReadRequest is now a four-case closed ADT (Paged, Range, Frames, FramesFiltered)`() {
        // Pinned at compile time: the when over `request` in `MainConsoleCli.main` and in
        // `MainConsoleCli.execute` is exhaustive. Adding a fifth case MUST be a deliberate
        // edit, not an oversight.
        val paged = ConsoleReadRequest.Paged("c", "r", "o", null, 1024, ConsoleFormat.TEXT)
        val range = ConsoleReadRequest.Range("c", "r", "o", 0L, 1024L, ConsoleFormat.TEXT)
        val frames = ConsoleReadRequest.Frames("c", "r", "o", -1L, 1024, ConsoleFormat.JSONL)
        val filtered = ConsoleReadRequest.FramesFiltered(
            controlDir = "c",
            runId = "r",
            opId = "o",
            afterOrdinal = -1L,
            frameLimit = 1024,
            filters = ConsoleFrameFilters.Active(stages = setOf("0")),
            format = ConsoleFormat.JSONL,
        )
        assertNotNull(paged)
        assertNotNull(range)
        assertNotNull(frames)
        assertNotNull(filtered)
        assertTrue(paged is ConsoleReadRequest.Paged)
        assertTrue(range is ConsoleReadRequest.Range)
        assertTrue(frames is ConsoleReadRequest.Frames)
        assertTrue(filtered is ConsoleReadRequest.FramesFiltered)
    }

    @Test
    fun `FramesFiltered payload round-trips afterOrdinal, frameLimit, filters, and format`() {
        val filters = ConsoleFrameFilters.Active(
            stages = setOf("0", "1"),
            steps = setOf("0"),
            kinds = setOf("StepStarted", "StepFinished"),
        )
        val req = ConsoleReadRequest.FramesFiltered(
            controlDir = "control",
            runId = "run-1",
            opId = "op-1",
            afterOrdinal = 7L,
            frameLimit = 256,
            filters = filters,
            format = ConsoleFormat.JSONL,
        )
        assertEquals("control", req.controlDir)
        assertEquals("run-1", req.runId)
        assertEquals("op-1", req.opId)
        assertEquals(7L, req.afterOrdinal)
        assertEquals(256, req.frameLimit)
        assertEquals(filters, req.filters)
        assertEquals(ConsoleFormat.JSONL, req.format)
    }

    @Test
    fun `ConsoleFrameFilters None is the no-filter case`() {
        // The two cases of the closed ADT; the verb main dispatches on the request variant,
        // and `None` is what an empty filter list lowers to. Pinned at compile time.
        val none: ConsoleFrameFilters = ConsoleFrameFilters.None
        assertTrue(none is ConsoleFrameFilters.None)
    }

    @Test
    fun `ConsoleFrameFilters Active requires at least one non-empty axis at construction`() {
        try {
            ConsoleFrameFilters.Active(stages = emptySet(), steps = emptySet(), kinds = emptySet())
            error("expected IllegalArgumentException for empty Active")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                e.message!!.contains("at least one non-empty"),
                "message must name the rule; got: ${e.message}",
            )
        }
    }

    @Test
    fun `ConsoleFrameFilters Active accepts any single non-empty axis`() {
        val onlyStage = ConsoleFrameFilters.Active(stages = setOf("0"))
        val onlyStep = ConsoleFrameFilters.Active(steps = setOf("1"))
        val onlyKind = ConsoleFrameFilters.Active(kinds = setOf("StepStarted"))
        assertEquals(setOf("0"), onlyStage.stages)
        assertEquals(emptySet<String>(), onlyStage.steps)
        assertEquals(emptySet<String>(), onlyStage.kinds)
        assertEquals(setOf("1"), onlyStep.steps)
        assertEquals(setOf("StepStarted"), onlyKind.kinds)
    }

    // ------------------------------------------------------------------ HF0 closed refusal

    @Test
    fun `MutuallyExclusiveRunId refusal names both the positional and the --run value`() {
        val refusal = ConsoleCliRefusal.MutuallyExclusiveRunId(runId = "pos-1", runOverride = "run-1")
        val rendered = renderConsoleCliRefusal(refusal)
        assertTrue(
            rendered.contains("pos-1") && rendered.contains("run-1"),
            "rendered refusal must name both values; got: $rendered",
        )
        assertTrue(
            rendered.startsWith("Error:"),
            "argument refusals keep the Error: wire prefix; got: $rendered",
        )
    }

    @Test
    fun `FilterRequiresFrames refusal names every offending flag and the --frames remedy`() {
        val refusal = ConsoleCliRefusal.FilterRequiresFrames(listOf("--stage", "--step"))
        val rendered = renderConsoleCliRefusal(refusal)
        assertTrue(
            rendered.contains("--stage") && rendered.contains("--step"),
            "rendered refusal must name every offending flag; got: $rendered",
        )
        assertTrue(
            rendered.contains("--frames"),
            "rendered refusal must name --frames; got: $rendered",
        )
        assertTrue(
            rendered.startsWith("Error:"),
            "argument refusals keep the Error: wire prefix; got: $rendered",
        )
    }

    // ------------------------------------------------------------------ HF0 parser

    @Test
    fun `parseConsole admits --stage X --step Y --kind K --frames as FramesFiltered`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1",
                "--frames",
                "--stage", "0",
                "--step", "1",
                "--kind", "StepStarted",
                "--kind", "StepFinished",
                "--format", "jsonl",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Admitted, "expected admission, got $r")
        val req = (r as ConsoleCliParseResult.Admitted).request
        assertTrue(req is ConsoleReadRequest.FramesFiltered, "expected FramesFiltered, got $req")
        val active = (req as ConsoleReadRequest.FramesFiltered).filters as ConsoleFrameFilters.Active
        assertEquals(setOf("0"), active.stages)
        assertEquals(setOf("1"), active.steps)
        assertEquals(setOf("StepStarted", "StepFinished"), active.kinds)
        assertEquals(ConsoleFormat.JSONL, req.format)
    }

    @Test
    fun `parseConsole admits --run R as a runId override with no positional`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl",
                "--run", "from-flag",
                "op-1",
                "--frames",
                "--stage", "0",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Admitted, "expected admission, got $r")
        val req = (r as ConsoleCliParseResult.Admitted).request
        assertEquals("from-flag", (req as ConsoleReadRequest.FramesFiltered).runId)
    }

    @Test
    fun `parseConsole refuses --run R with a positional runId as MutuallyExclusiveRunId`() {
        // The order is: `--run R <opId> <positional-runId>` (the opId comes first because
        // `--run` has already supplied the runId slot, and the second positional is the
        // user typing a name for the run after the opId — which the parser must refuse
        // as a conflict, not silently honour the override).
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl",
                "--run", "from-flag",
                "op-1",
                "from-positional",
                "--frames",
                "--stage", "0",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.MutuallyExclusiveRunId,
            "expected MutuallyExclusiveRunId, got $refusal",
        )
    }

    @Test
    fun `parseConsole refuses --stage X without --frames as FilterRequiresFrames`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1",
                "--stage", "0",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.FilterRequiresFrames,
            "expected FilterRequiresFrames, got $refusal",
        )
    }

    @Test
    fun `parseConsole refuses --step X without --frames as FilterRequiresFrames`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1",
                "--step", "0",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.FilterRequiresFrames,
            "expected FilterRequiresFrames, got $refusal",
        )
    }

    @Test
    fun `parseConsole refuses --kind X without --frames as FilterRequiresFrames`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1",
                "--kind", "StepStarted",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.FilterRequiresFrames,
            "expected FilterRequiresFrames, got $refusal",
        )
    }

    @Test
    fun `parseConsole refuses --stage without a value as MissingOptionValue`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1",
                "--frames",
                "--stage",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.MissingOptionValue,
            "expected MissingOptionValue, got $refusal",
        )
    }

    @Test
    fun `parseConsole refuses --run without a value as MissingOptionValue`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl",
                "op-1",
                "--frames",
                "--run",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.MissingOptionValue,
            "expected MissingOptionValue, got $refusal",
        )
    }

    @Test
    fun `parseConsole preserves Error wire prefix for the new refusals`() {
        val r1 = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl",
                "--run", "a",
                "b",
                "op-1",
                "--frames",
            ),
        )
        val rendered1 = renderConsoleCliRefusal((r1 as ConsoleCliParseResult.Rejected).refusal)
        assertTrue(
            rendered1.startsWith("Error:"),
            "MutuallyExclusiveRunId must keep the Error: wire prefix; got: $rendered1",
        )

        val r2 = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1",
                "--stage", "0",
            ),
        )
        val rendered2 = renderConsoleCliRefusal((r2 as ConsoleCliParseResult.Rejected).refusal)
        assertTrue(
            rendered2.startsWith("Error:"),
            "FilterRequiresFrames must keep the Error: wire prefix; got: $rendered2",
        )
    }

    @Test
    fun `parseConsole still refuses unknown options with the existing UnknownOption shape`() {
        // The four new flags are added to the existing valued-option branch; an UNKNOWN
        // valued option must still come back as `UnknownOption`, not as one of the new
        // refusal cases. Regression guard for the token-loop restructure.
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1",
                "--frames",
                "--bogus", "0",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.UnknownOption,
            "expected UnknownOption, got $refusal",
        )
    }

    // ------------------------------------------------------------------ HF1 emit wiring

    /**
     * Build a one-record [ObservationOutputRead.Page] whose single frame lives in
     * `<runId>/<opId>/stdout`. The test drives the emit path directly; the read path
     * (which needs a real Output Plane store) is exercised by the installDist canary
     * the WU-R4.5 receipt documents.
     */
    private fun pageFor(runId: String, opId: String, ordinal: Long, payload: String): ObservationOutputRead {
        val stream = OutputStreamId("$runId/$opId/stdout")
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val frame = OutputFrame(ordinal = ordinal, stream = stream, channel = OutputChannel.STDOUT, from = 0L, to = bytes.size.toLong())
        val record = ObservationRecord.Output(frame = frame, bytes = bytes, text = payload)
        return ObservationOutputRead.Page(
            ObservationOutputPage(records = listOf(record), lastOrdinal = ordinal, moreFrames = false),
        )
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runEmit(
        result: ObservationOutputRead,
        request: ConsoleReadRequest.FramesFiltered,
    ): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    MainConsoleCli.emitFramesFiltered(result, request, System.out, System.err)
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `emitFramesFiltered jsonl path emits only frames matching the --stage --step conjunction`() {
        val request = ConsoleReadRequest.FramesFiltered(
            controlDir = "/tmp/ctrl",
            runId = "run-1",
            opId = "ignored",
            afterOrdinal = -1L,
            frameLimit = 100,
            filters = ConsoleFrameFilters.Active(stages = setOf("0"), steps = setOf("1")),
            format = ConsoleFormat.JSONL,
        )
        val result = ObservationOutputRead.Page(
            ObservationOutputPage(
                records = listOf(
                    // s0-0 — should be dropped (step 0)
                    fakeRecord("run-1", "run-1-s0-0", 0L, "compiling\n"),
                    // s0-1 — should pass (stage 0, step 1)
                    fakeRecord("run-1", "run-1-s0-1", 1L, "compiling inner\n"),
                    // s1-0 — should be dropped (stage 1)
                    fakeRecord("run-1", "run-1-s1-0", 2L, "testing\n"),
                ),
                lastOrdinal = 2L,
                moreFrames = false,
            ),
        )

        val r = runEmit(result, request)
        assertEquals(0, r.exitCode, "emit must succeed; got exit ${r.exitCode}")
        val lines = r.stdout.split("\n").filter { it.isNotEmpty() }
        assertEquals(1, lines.size, "exactly one frame should match the conjunction; got: ${r.stdout}")
        val line = lines.single()
        assertTrue(line.contains("\"ordinal\":1"), "the surviving record's ordinal is 1; got: $line")
        assertTrue(line.contains("\"operation\":\"run-1-s0-1\""), "the surviving record is s0-1; got: $line")
    }

    @Test
    fun `emitFramesFiltered text path emits only the bytes of the matching frames`() {
        val request = ConsoleReadRequest.FramesFiltered(
            controlDir = "/tmp/ctrl",
            runId = "run-1",
            opId = "ignored",
            afterOrdinal = -1L,
            frameLimit = 100,
            filters = ConsoleFrameFilters.Active(stages = setOf("1")),
            format = ConsoleFormat.TEXT,
        )
        val result = ObservationOutputRead.Page(
            ObservationOutputPage(
                records = listOf(
                    fakeRecord("run-1", "run-1-s0-0", 0L, "first\n"),
                    fakeRecord("run-1", "run-1-s1-0", 1L, "second\n"),
                ),
                lastOrdinal = 1L,
                moreFrames = false,
            ),
        )
        val r = runEmit(result, request)
        assertEquals(0, r.exitCode, "emit must succeed; got exit ${r.exitCode}")
        assertTrue(
            r.stdout.contains("second\n") && !r.stdout.contains("first\n"),
            "stdout must carry only the matching frame's bytes; got: ${r.stdout}",
        )
    }

    @Test
    fun `emitFramesFiltered emits zero frames when no frame matches the filter`() {
        val request = ConsoleReadRequest.FramesFiltered(
            controlDir = "/tmp/ctrl",
            runId = "run-1",
            opId = "ignored",
            afterOrdinal = -1L,
            frameLimit = 100,
            filters = ConsoleFrameFilters.Active(stages = setOf("9")),
            format = ConsoleFormat.JSONL,
        )
        val result = ObservationOutputRead.Page(
            ObservationOutputPage(
                records = listOf(
                    fakeRecord("run-1", "run-1-s0-0", 0L, "first\n"),
                    fakeRecord("run-1", "run-1-s1-0", 1L, "second\n"),
                ),
                lastOrdinal = 1L,
                moreFrames = false,
            ),
        )
        val r = runEmit(result, request)
        assertEquals(0, r.exitCode, "emit must succeed; got exit ${r.exitCode}")
        assertTrue(
            r.stdout.isEmpty(),
            "stdout must be empty when no frame matches; got: ${r.stdout}",
        )
    }

    @Test
    fun `StageStepKindResolver matches opIds against stage and step filters`() {
        val resolver = StageStepKindResolver.forRun(java.nio.file.Path.of("/tmp/ctrl"), "run-1")
        val filters = ConsoleFrameFilters.Active(stages = setOf("0"), steps = setOf("1"))
        assertTrue(resolver.matches("run-1/run-1-s0-1/stdout", filters), "s0-1 must match stages={0},steps={1}")
        assertTrue(!resolver.matches("run-1/run-1-s0-0/stdout", filters), "s0-0 must not match steps={1}")
        assertTrue(!resolver.matches("run-1/run-1-s1-0/stdout", filters), "s1-0 must not match stages={0}")
    }

    @Test
    fun `StageStepKindResolver treats empty axes as no-op conjuncts`() {
        val resolver = StageStepKindResolver.forRun(java.nio.file.Path.of("/tmp/ctrl"), "run-1")
        val onlyKind = ConsoleFrameFilters.Active(kinds = setOf("StepStarted"))
        // Empty stages and empty steps are no-ops; the kind axis is a pass-through for now.
        assertTrue(
            resolver.matches("run-1/run-1-s0-0/stdout", onlyKind),
            "empty stages and steps must be no-ops; got false for s0-0",
        )
    }

    @Test
    fun `StageStepKindResolver rejects foreign-run streams`() {
        val resolver = StageStepKindResolver.forRun(java.nio.file.Path.of("/tmp/ctrl"), "run-1")
        val filters = ConsoleFrameFilters.Active(stages = setOf("0"))
        assertTrue(
            !resolver.matches("run-2/run-2-s0-0/stdout", filters),
            "a stream from a different runId must not match; got true",
        )
    }

    @Test
    fun `StageStepKindResolver rejects malformed stream ids`() {
        val resolver = StageStepKindResolver.forRun(java.nio.file.Path.of("/tmp/ctrl"), "run-1")
        val filters = ConsoleFrameFilters.Active(stages = setOf("0"))
        assertTrue(
            !resolver.matches("malformed", filters),
            "a malformed stream id must not match; got true",
        )
        assertTrue(
            !resolver.matches("run-1/just-an-opid", filters),
            "a stream id missing a channel segment must not match; got true",
        )
    }

    // ------------------------------------------------------------------ HF1 helpers

    private fun fakeRecord(runId: String, opId: String, ordinal: Long, payload: String): ObservationRecord.Output {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val frame = OutputFrame(
            ordinal = ordinal,
            stream = OutputStreamId("$runId/$opId/stdout"),
            channel = OutputChannel.STDOUT,
            from = 0L,
            to = bytes.size.toLong(),
        )
        return ObservationRecord.Output(frame = frame, bytes = bytes, text = payload)
    }
}
