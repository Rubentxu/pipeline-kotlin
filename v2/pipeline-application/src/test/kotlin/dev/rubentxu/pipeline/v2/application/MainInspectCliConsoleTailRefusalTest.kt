package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OpId
import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * `inspect --log-tail` must not tell a consumer that a step printed nothing when the truth is that
 * the Output Plane could not answer.
 *
 * ## The defect this pins
 *
 * `MainInspectCli` mapped the Output Plane's read ADT onto a single string:
 *
 * ```kotlin
 * is ObservationOutputRead.Page    -> decodePageBytesAsUtf8(r.page.records)
 * is ObservationOutputRead.Refused -> ""            // the byte plane still has the bytes
 * ```
 *
 * Both arms produced a `String`, so a refusal became an EMPTY TRANSCRIPT. That is a semantic drop
 * in the plainest form: the consumer is told a fact — *"this step printed nothing"* — which is
 * different from the truth — *"the committed bytes are unread"*, and it is the fact a reader of a
 * failed step acts on. An unreadable tail is the interesting case; a silent empty one is the
 * diagnostic equivalent of a missing stack trace.
 *
 * The fix is the closed `InspectConsoleTail` ADT (`NotRequested | Bytes | Unreadable`), projected by
 * `InspectField.ConsoleTail`. This class pins the OBSERVABLE consequence at the CLI boundary, which
 * is where a consumer actually reads it, and not the ADT in isolation.
 *
 * ## The production entry point this crosses
 *
 * ```text
 * MainInspectCli.main(argv)                              in-process, real stdout/stderr
 *   -> CliParser.parseInspect
 *   -> SqliteEventStore.eventsFor(runId)                  REAL SQLite file, real decoder
 *   -> OutputPlaneProvider.storeForReading(controlRoot)   REAL SegmentOutputStore, recoveryPermitted=false
 *   -> FrameIndexedObservationOutputReader.readTail(runId, n)
 *   -> InspectField.ConsoleTail.extract
 *   -> emitDocument                                          TEXT and JSON, both read extract()
 * ```
 *
 * **HF1 — in-process (HF1), not HF2, and not a model.** The store is the real
 * `SegmentOutputStore` over a real directory, the event store is the real `SqliteEventStore` over a
 * real SQLite file, and the frames are published through the production write order
 * (`declareStream -> reserve -> write -> commit -> frameIndex().append`). Nothing is stubbed and no
 * reader is reimplemented: the refusal this test induces comes from the store's own
 * `SegmentReader.readRange` noticing that a committed extent exceeds what is on disk.
 *
 * **HF5 — the mutation is named below and is observed, not asserted to exist.** See
 * `## The mutation that must kill this`.
 *
 * ## How the refusal is induced WITHOUT a double
 *
 * The reader store is built with `recoveryPermitted = false` and never recovers (ADR-OBS-002), so
 * durable debris is never reconciled away underneath it. The test therefore writes real bytes,
 * commits them honestly, and then truncates `cur.seg` while `cur.cmt` still claims the full extent
 * — the exact on-disk shape a crash between write and flush leaves behind. `readRange` returns
 * `DanglingCommit(requestedEnd, readableBytes)`, deterministically, from production code.
 *
 * This is the production shape of the condition, not a mock of it. A store double would have proven
 * that `MainInspectCli` renders whatever reason it is handed, which is the trivial half; what this
 * pins is that a REAL plane in that state produces a refusal at all, and that the CLI does not eat it.
 *
 * ## The control row, and why it exists
 *
 * `TAIL-0` runs the identical fixture WITHOUT the truncation and asserts the transcript is the real
 * bytes. Without it, `TAIL-2` could be green because the fixture never publishes anything readable —
 * a fixture whose base is already empty makes the injected violation unobservable, and the row would
 * be characterizing its own plumbing. The delta between `TAIL-0` and `TAIL-2` is produced by the
 * truncation and by nothing else.
 *
 * ## The mutation that must kill this
 *
 * ```text
 * M-1  InspectField.ConsoleTail.extract:
 *        is InspectConsoleTail.Unreadable -> "<unreadable: ${tail.reason}>"
 *      reverted to  -> ""
 * ```
 *
 * This is the plausible wrong code and the exact pre-fix line. It kills `TAIL-2` on both counts — the
 * marker assertion and the not-an-empty-transcript assertion — and `TAIL-3` with it, because both
 * shapes read the same `extract`. It does NOT touch `TAIL-0` or `TAIL-1`: an intact plane has no
 * refusal to render, and an un-requested field never reaches the arm. That pair is the point — the
 * mutation is attributed to exactly the rows whose subject it is, and to no other.
 *
 * Recorded attribution, measured 2026-10-18 on this class (XML `failures="2"` after the mutation):
 * `M-1` flips `TAIL-2` and `TAIL-3`; `TAIL-0` and `TAIL-1` stay green.
 */
class MainInspectCliConsoleTailRefusalTest {

    @TempDir
    lateinit var root: Path

    private val runId = "run-inspect-tail"
    private val transcript = "step-3 said something important\n"

    private val controlRoot: Path get() = root.resolve("control")
    private val db: Path get() = root.resolve("events.db")

    /**
     * The step whose output this fixture publishes.
     *
     * `--log-tail` is attributed PER OPERATION, so the run must actually declare one: a run with no
     * step has nothing to attach a tail to and every row projects silence. The refusal these rows
     * are about is then a refusal on a row that genuinely owns a stream.
     */
    private val stepOp = OpId(runId, stageIndex = 0, stepIndex = 0)

    @AfterEach
    fun dropCachedStores() = OutputPlaneProvider.forgetAll()

    /**
     * The base of the fixture: real committed bytes the reader CAN serve.
     *
     * If this row ever goes red, every other row in the class is measuring nothing.
     */
    @Test
    fun TAIL_0_untruncated_plane_serves_the_committed_bytes() {
        val stdout = givenACommittedTranscript()

        assertTrue(stdout.contains(transcript.trim()), "the base fixture must publish readable bytes: $stdout")
        assertFalse(stdout.contains(UNREADABLE_MARKER), "an intact plane has nothing to refuse: $stdout")
    }

    /**
     * `--log-tail` is what was not requested: the field stays silent.
     *
     * Pinned because the fix introduced a third state next to this one, and "not asked" is the state
     * a consumer may mistake for "asked and empty" — the same confusion the fix removed, one level up.
     */
    @Test
    fun TAIL_1_without_log_tail_no_field_is_invented() {
        givenACommittedTranscript()
        appendRunStarted()

        val result = cli(
            runId,
            "--db", db.toString(),
            "--control-root", controlRoot.toString(),
            "--fields", "CONSOLETAIL",
        )

        assertEquals(0, result.exitCode)
        // Every line after the two header lines is a record row. They must NOT be filtered for
        // blankness here: a blank record row is precisely what this row is about.
        val rows = result.stdout.lines().drop(2).dropLastWhile { it.isEmpty() }
        assertTrue(rows.isNotEmpty(), "the run must still produce rows: ${result.stdout}")
        assertTrue(
            rows.all { it.isBlank() },
            "an un-requested tail must project as empty, not as a refusal or as bytes: $rows",
        )
    }

    /**
     * A committed extent the plane cannot serve is reported as unread, never as silence.
     *
     * This is the row `M-1` kills.
     */
    @Test
    fun TAIL_2_torn_commit_is_rendered_as_unreadable_not_as_an_empty_transcript() {
        givenACommittedTranscript()
        truncateTheCommittedSegment()
        appendRunStarted()

        val result = cli(
            runId,
            "--db", db.toString(),
            "--control-root", controlRoot.toString(),
            "--log-tail", "1024",
            "--fields", "CONSOLETAIL",
        )

        assertEquals(0, result.exitCode)
        val tail = result.stdout.lines().drop(2).first { it.isNotBlank() }.trim()
        assertTrue(
            tail.startsWith(UNREADABLE_MARKER),
            "a refusal must be visible to the consumer, was: '$tail'",
        )
        assertTrue(
            tail.contains("DanglingCommit"),
            "the marker must NAME the refusal so it can be triaged, was: '$tail'",
        )
        assertFalse(
            tail == transcript.trim(),
            "the truncated plane must not render bytes it did not serve",
        )
    }

    /**
     * The JSON projection carries the same refusal.
     *
     * Not a second bug claim: `emitDocument` builds JSON and TEXT through the same `f.extract(rec)`,
     * so this row exists to prove the fix reached BOTH shapes and to catch the plausible future edit
     * that special-cases one of them.
     */
    @Test
    fun TAIL_3_json_projection_carries_the_same_refusal() {
        givenACommittedTranscript()
        truncateTheCommittedSegment()
        appendRunStarted()

        val result = cli(
            runId,
            "--db", db.toString(),
            "--control-root", controlRoot.toString(),
            "--log-tail", "1024",
            "--fields", "CONSOLETAIL",
            "--format", "json",
        )

        assertEquals(0, result.exitCode)
        assertTrue(
            result.stdout.contains("\"consoletail\": \"$UNREADABLE_MARKER"),
            "the JSON shape stays a string; only the refusal VALUE may change: ${result.stdout}",
        )
    }

    // ---- fixture -------------------------------------------------------------------------------

    /**
     * The stream id, in the shape the engine MINTS — `<runId>/<opId>/<channel>`, read out of a real
     * run's frame index.
     *
     * It used to be `"$runId/build/sh-0/stdout"`, which no engine ever produces. The fixture was
     * green anyway, because the run-scoped read matched on `runId` alone and ignored the middle
     * segments; the day the tail became per-operation, the fabricated shape stopped matching and
     * the three rows above went red. That is the fixture telling the truth late: a stream id whose
     * shape the product does not produce cannot be the base of a claim about how the product
     * attributes a stream to a step. See `MainInspectCliConsoleTailAttributionTest` for the
     * measured shape and the mutation that pins it.
     */
    private fun stdoutStream() = OutputStreamId("$runId/${stepOp.format()}/stdout")

    /**
     * Publishes [transcript] through the production write order, and returns the stdout the CLI
     * renders for the intact case.
     */
    private fun givenACommittedTranscript(): String {
        val store = OutputPlaneProvider.storeForWriting(controlRoot)
        val stream = stdoutStream()
        store.frameIndex().declareStream(stream, OutputChannel.STDOUT)
        val payload = transcript.toByteArray(Charsets.UTF_8)
        val from = store.committedExtent(stream) ?: 0L
        store.open(stream).reserve(payload.size).let { reservation ->
            reservation.write(payload)
            reservation.commit()
        }
        store.frameIndex().append(stream, OutputChannel.STDOUT, from, from + payload.size)
        // The CLI opens its OWN reader store for this root; drop the cached ones so the reading
        // side is constructed against the state on disk as it stands right now.
        OutputPlaneProvider.forgetAll()
        appendRunStarted()
        return cli(
            runId,
            "--db", db.toString(),
            "--control-root", controlRoot.toString(),
            "--log-tail", "1024",
            "--fields", "CONSOLETAIL",
        ).stdout
    }

    /**
     * The crash shape: the segment file is shorter than the offset `cur.cmt` still advertises.
     *
     * `recoveryPermitted = false` on the reading side means nothing repairs this, so the store's own
     * `readRange` is what refuses.
     */
    private fun truncateTheCommittedSegment() {
        val segment = controlRoot
            .resolve(OutputPlaneProvider.OUTPUT_DIR)
            .resolve("streams")
            .resolve(stdoutStream().value.replace('/', '_'))
            .resolve("cur.seg")
        assertTrue(Files.isRegularFile(segment), "the writer must have created $segment")
        val current = Files.size(segment)
        val shortened = ByteArray((current - 1).toInt())
        Files.write(segment, shortened, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    /**
     * The run must DECLARE the step that owns the stream, or `--log-tail` has nothing to attach to.
     *
     * Both events carry `sequence = 0L` on purpose: `SqliteEventStore.appendAssigned` is the
     * durable authority for `UNIQUE(run_id, sequence)` and assigns the sequence itself, so a
     * fixture that invents one collides with the counter.
     */
    private fun appendRunStarted() {
        SqliteEventStore(db.toString()).use { store ->
            val at = Instant.parse("2026-10-18T09:00:00Z")
            store.append(
                RunStarted(
                    eventId = "evt-0",
                    runId = runId,
                    sequence = 0L,
                    occurredAt = at,
                    scriptPath = "/tmp/inspect-tail.pipeline.kts",
                ),
            )
            store.append(
                StepStarted(
                    eventId = "evt-1",
                    runId = runId,
                    sequence = 0L,
                    occurredAt = at,
                    stageIndex = stepOp.stageIndex,
                    stepIndex = stepOp.stepIndex,
                    stepName = "build/sh-0",
                    stepType = "core.sh",
                ),
            )
        }
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun cli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    MainInspectCli.main(arrayOf(*args))
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private companion object {
        const val UNREADABLE_MARKER = "<unreadable:"
    }
}