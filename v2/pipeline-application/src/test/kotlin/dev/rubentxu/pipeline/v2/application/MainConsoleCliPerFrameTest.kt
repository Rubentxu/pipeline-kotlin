package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.MainConsoleCli.ConsoleFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLines
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
 * R4 / WU-R4.6 — `console --frames` (per-frame records for the JSONL path).
 *
 * ## Why this is the natural follow-up to WU-R4.3
 *
 * WU-R4.3 left the JSONL envelope bounded to the operation's merged page: one synthetic
 * `ObservationRecord.Output` over a synthetic `OutputFrame`, which throws away the real
 * `from`/`to`/`ordinal` of every frame. WU-R4.1.3 made the per-operation frame index the
 * public seam (`FrameIndexedObservationOutputReader.readTail(runId, opId, N)`), and this
 * slice is the `--format jsonl` counterpart: the reader walks the same frame index and
 * emits ONE record per frame.
 *
 * The closed payload is `ConsoleReadRequest.Frames`; the dispatch in
 * [MainConsoleCli.execute] now has three variants (Paged / Range / Frames) and the
 * `when (request)` is exhaustive. The new flag is `--frames`; it is OPT-IN so the
 * WU-R4.3 "one record per page" path stays untouched.
 *
 * ## What is NOT in this slice
 *
 * - NOT a wholesale fold of the console parser into `CliParser.parseObservation` (CC-1).
 * - NOT `--stage / --step / --kind / --run` filters on `console` (CON-2.2). Those are the
 *   next slice after this one; they need event→opId resolution which is its own work.
 * - NOT a `--follow` flag (CON-2.3). The byte-budgeted path is the one that already has
 *   a continuation story; the per-frame path will get one when the wakeup-based drain
 *   is ported to frames.
 */
@Timeout(60)
@DisplayName("R4 / WU-R4.6 — console --frames (per-frame records for the JSONL path)")
class MainConsoleCliPerFrameTest {

    // ------------------------------------------------------------------ HF0 closed ADT

    @Test
    fun `ConsoleReadRequest is now a three-case closed ADT (Paged, Range, Frames)`() {
        // Pinned at compile time: the when over `request` in `MainConsoleCli.execute` is
        // exhaustive. Adding a fourth case MUST be a deliberate edit, not an oversight.
        val paged = ConsoleReadRequest.Paged("c", "r", "o", null, 1024, ConsoleFormat.TEXT)
        val range = ConsoleReadRequest.Range("c", "r", "o", 0L, 1024L, ConsoleFormat.TEXT)
        val frames = ConsoleReadRequest.Frames("c", "r", "o", -1L, 1024, ConsoleFormat.JSONL)
        assertNotNull(paged)
        assertNotNull(range)
        assertNotNull(frames)
        assertTrue(paged is ConsoleReadRequest.Paged)
        assertTrue(range is ConsoleReadRequest.Range)
        assertTrue(frames is ConsoleReadRequest.Frames)
    }

    @Test
    fun `Frames payload round-trips afterOrdinal, frameLimit, and format`() {
        val req = ConsoleReadRequest.Frames(
            controlDir = "control",
            runId = "run-1",
            opId = "op-1",
            afterOrdinal = 7L,
            frameLimit = 256,
            format = ConsoleFormat.JSONL,
        )
        assertEquals("control", req.controlDir)
        assertEquals("run-1", req.runId)
        assertEquals("op-1", req.opId)
        assertEquals(7L, req.afterOrdinal)
        assertEquals(256, req.frameLimit)
        assertEquals(ConsoleFormat.JSONL, req.format)
    }

    @Test
    fun `Frames require frameLimit to be positive at construction`() {
        // frameLimit is the loop bound for the per-frame walk. A non-positive value would
        // either return nothing (frameLimit == 0) or loop forever (frameLimit < 0). The
        // constructor rejects, so the caller learns the rule at compile + admission.
        try {
            ConsoleReadRequest.Frames("c", "r", "o", -1L, 0, ConsoleFormat.JSONL)
            error("expected IllegalArgumentException for frameLimit=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frameLimit"), "message must name frameLimit; got: ${e.message}")
        }
    }

    @Test
    fun `IncompatibleWithFrames refusal names the offending option and the per-frame path's reason`() {
        // The refusal class is a `data class IncompatibleWithFrames(val option: String)`. The
        // rendered form names the option so the caller deletes one token, not edits two.
        val refusal = ConsoleCliRefusal.IncompatibleWithFrames("--max-bytes")
        val rendered = renderConsoleCliRefusal(refusal)
        assertTrue(rendered.contains("--max-bytes"), "rendered refusal must name --max-bytes; got: $rendered")
        assertTrue(rendered.contains("frames") || rendered.contains("frame"), "rendered refusal must mention the per-frame context; got: $rendered")
    }

    /**
     * H3 mandate: "UX observable" requires an operator running `pipelinek console`
     * with no args to see every verb the parser accepts. The legacy single-line
     * usage emitted by [renderConsoleCliRefusal] for [ConsoleCliRefusal.MissingArgument]
     * used to list only the byte-budgeted options (`--max-bytes`, `--after-cursor`,
     * `--format`, `--range`); the R4 per-frame options (`--frames`, `--frame-limit`,
     * `--after-ordinal`, `--follow`, `--stage`, `--step`, `--kind`) were accepted by
     * the parser but not advertised in the help text, so an operator would not
     * know they exist without reading the source. This test pins the new usage
     * line so a future regression that drops a per-frame option fails here.
     */
    @Test
    fun `MissingArgument usage line advertises every per-frame option the parser accepts`() {
        val rendered = renderConsoleCliRefusal(ConsoleCliRefusal.MissingArgument)
        // The legacy byte-budgeted options stay in the help text.
        assertTrue(rendered.contains("--max-bytes"), "usage must still list --max-bytes; got: $rendered")
        assertTrue(rendered.contains("--after-cursor"), "usage must still list --after-cursor; got: $rendered")
        assertTrue(rendered.contains("--range"), "usage must still list --range; got: $rendered")
        // The per-frame options the R4 work added must now be in the help text.
        for (flag in listOf(
            "--frames",
            "--frame-limit",
            "--after-ordinal",
            "--follow",
            "--stage",
            "--step",
            "--kind",
        )) {
            assertTrue(
                rendered.contains(flag),
                "usage must list $flag so an operator learns it exists; got: $rendered",
            )
        }
    }

    // ------------------------------------------------------------------ HF1 parser + emit wiring

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Drive [MainConsoleCli.main] with a NON-EXISTENT control-dir. The admission runs BEFORE
     * the control-dir existence check, so a parser-level refusal (IncompatibleWithFrames,
     * NotAnInteger, MissingOptionValue) is observable even when the path does not exist.
     * A successful admission against a non-existent path produces `ControlDirNotFound`
     * (exit 2), which is the right shape for the "parser accepted --frames" rows below.
     */
    private fun cli(vararg args: String): CliResult {
        val nonexistentDir = "/tmp/r4-wu-r4-6-nonexistent-${java.util.UUID.randomUUID()}"
        val all = arrayOf("--control-dir", nonexistentDir) + args
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    MainConsoleCli.main(all)
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `main() admits --frames against a non-existent control dir and refuses on the dir check (HF1 admission only)`() {
        // The admission runs first; the control-dir existence check runs after. So a
        // non-existent path produces a ControlDirNotFound refusal AFTER the --frames
        // path is admitted — that is the right shape for "the parser accepted --frames".
        val r = cli("run-1", "op-1", "--frames", "--format", "jsonl")
        assertEquals(2, r.exitCode, "non-existent control dir must fail with exit 2")
        assertTrue(
            r.stderr.contains("control dir not found"),
            "stderr must carry the control-dir refusal (not a parser refusal); got: ${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --frames together with --range`() {
        val r = cli("run-1", "op-1", "--frames", "--range", "0:1024")
        assertEquals(2, r.exitCode, "frames + range must refuse; got exit ${r.exitCode}")
        assertTrue(
            r.stderr.contains("--range") && r.stderr.contains("frames"),
            "stderr must name --range AND the per-frame context; got: ${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --frames together with --max-bytes`() {
        val r = cli("run-1", "op-1", "--frames", "--max-bytes", "4096")
        assertEquals(2, r.exitCode, "frames + max-bytes must refuse; got exit ${r.exitCode}")
        assertTrue(
            r.stderr.contains("--max-bytes"),
            "stderr must name --max-bytes; got: ${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --frames together with --after-cursor`() {
        val r = cli("run-1", "op-1", "--frames", "--after-cursor", "out-cursor-v1:run-1%2Fop-1%2Fstdout:42")
        assertEquals(2, r.exitCode, "frames + after-cursor must refuse; got exit ${r.exitCode}")
        assertTrue(
            r.stderr.contains("--after-cursor"),
            "stderr must name --after-cursor; got: ${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --frame-limit with a non-positive integer at the CLI layer`() {
        val r = cli("run-1", "op-1", "--frames", "--frame-limit", "0")
        assertEquals(2, r.exitCode, "frame-limit=0 must refuse; got exit ${r.exitCode}")
        assertTrue(
            r.stderr.contains("--frame-limit") && r.stderr.contains("positive integer"),
            "stderr must name --frame-limit AND the integer rule; got: ${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --after-ordinal with a negative integer other than -1 at the CLI layer`() {
        val r = cli("run-1", "op-1", "--frames", "--after-ordinal", "-7")
        assertEquals(2, r.exitCode, "after-ordinal=-7 must refuse; got exit ${r.exitCode}")
        assertTrue(
            r.stderr.contains("--after-ordinal") && r.stderr.contains("positive integer"),
            "stderr must name --after-ordinal AND the integer rule; got: ${r.stderr}",
        )
    }

    @Test
    fun `main() accepts --after-ordinal -1 as the explicit-from-start sentinel`() {
        // -1 is the closed value meaning "from the start" (the index convention the
        // WU-R4.1.3 readTail primitives already use). A script that wants to walk
        // from the beginning passes --after-ordinal -1 explicitly.
        val r = cli("run-1", "op-1", "--frames", "--after-ordinal", "-1", "--format", "jsonl")
        assertEquals(2, r.exitCode, "after-ordinal=-1 must be admitted (control-dir refusal is the expected next step)")
        assertTrue(
            !r.stderr.contains("after-ordinal"),
            "stderr must NOT carry an after-ordinal refusal; got: ${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --frame-limit without a value at the CLI layer`() {
        val r = cli("run-1", "op-1", "--frame-limit")
        assertEquals(2, r.exitCode, "missing value must refuse; got exit ${r.exitCode}")
        assertTrue(
            r.stderr.contains("--frame-limit requires a value"),
            "stderr must name the missing-value reason; got: ${r.stderr}",
        )
    }

    // ------------------------------------------------------------------ HF1 emit wiring (jsonl, per-frame)

    private fun emitJsonlFor(records: List<ObservationRecord.Output>): String {
        val out = ByteArrayOutputStream()
        // ObservationJsonLines.writeTo takes a Writer; wrap the byte sink in an
        // OutputStreamWriter so the per-frame records can flow through the same
        // encoder the production emitFrames() path uses.
        java.io.OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
            ObservationJsonLines.writeTo(records, writer)
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun fakeFrame(ordinal: Long, opId: String, channel: OutputChannel, payload: String): ObservationRecord.Output {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        return ObservationRecord.Output(
            frame = OutputFrame(
                ordinal = ordinal,
                stream = OutputStreamId("run-1/$opId/${channel.token}"),
                channel = channel,
                from = ordinal * 100L,
                to = ordinal * 100L + bytes.size,
            ),
            bytes = bytes,
            text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault(""),
        )
    }

    @Test
    fun `jsonl emitter encodes one record per frame with the real ordinal, from, to, channel, and operation`() {
        // The per-frame jsonl path must produce N lines, each carrying the frame's
        // real `ordinal`, `from`, `to`, and `channel`, plus the operation id parsed
        // from the stream. This pins the wire shape so a refactor cannot silently
        // re-introduce the WU-R4.3 synthetic-frame shape.
        val records = listOf(
            fakeFrame(0L, "op-1", OutputChannel.STDOUT, "hello "),
            fakeFrame(1L, "op-1", OutputChannel.STDOUT, "world\n"),
        )
        val out = emitJsonlFor(records)
        val lines = out.split("\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size, "two frames must produce two jsonl lines; got: $out")
        for ((i, line) in lines.withIndex()) {
            assertTrue(line.contains("\"type\":\"output\""), "line $i must tag type=output; got: $line")
            assertTrue(line.contains("\"channel\":\"stdout\""), "line $i must carry channel=stdout; got: $line")
            assertTrue(line.contains("\"operation\":\"op-1\""), "line $i must carry operation=op-1; got: $line")
            assertTrue(line.contains("\"ordinal\":$i"), "line $i must carry its ordinal; got: $line")
            assertTrue(line.contains("\"from\":${i * 100L}"), "line $i must carry its from; got: $line")
            assertTrue(line.contains("\"to\":${i * 100L + 6}"), "line $i must carry its to; got: $line")
        }
    }

    @Test
    fun `jsonl emitter uses base64 for non-utf8 frame bytes and never fabricates a replacement character`() {
        // The WU-R4.4 lesson: a `text` field with U+FFFD is a lie the process never
        // wrote. The per-frame path must keep that contract — a non-utf8 frame emits
        // base64 and `text: null`, exactly like the merged-page path.
        val bytes = byteArrayOf(0x48, 0x69, 0x21, 0xC3.toByte(), 0x28) // "Hi!" + invalid 0xC3
        val frame = OutputFrame(
            ordinal = 0L,
            stream = OutputStreamId("run-1/op-1/stdout"),
            channel = OutputChannel.STDOUT,
            from = 0L,
            to = bytes.size.toLong(),
        )
        val record = ObservationRecord.Output(
            frame = frame,
            bytes = bytes,
            text = "",
        )
        val out = emitJsonlFor(listOf(record))
        val line = out.split("\n").first()
        assertTrue(line.contains("\"text\":null"), "non-utf8 frame must set text=null; got: $line")
        assertTrue(line.contains("\"base64\":"), "non-utf8 frame must carry base64; got: $line")
        assertTrue(
            !line.contains("\\ufffd") && !line.contains("�"),
            "emitter must NEVER fabricate U+FFFD; got: $line",
        )
    }
}
