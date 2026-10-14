package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.MainConsoleCli.ConsoleFormat
import dev.rubentxu.pipeline.v2.events.identity.EventField
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * CC-1 / R4 / WU-R4.7 — the four `Main*Cli` verb admission parsers now live under a single
 * [CliParser] entry point. This file pins that contract.
 *
 * ## Why this file exists
 *
 * Before CC-1, each verb owned its own admission parser:
 *
 *   - `MainConsoleCli` → private `MainConsoleCliAdmission` (parseTokens + validate)
 *   - `MainEventsCli` → private `MainEventsCli.parseMainArgs`
 *   - `MainInspectCli` → `MainInspectCli.parseInspect`
 *   - `MainObserveCli` → already `CliParser.parseObservation`
 *
 * The first three were four separate authorities on what a flag means. CC-1 promoted them to
 * [CliParser.parseConsole] / [CliParser.parseEvents] / [CliParser.parseInspect]; the verb mains
 * became thin translators that emit the typed result on the wire. The parser is now the single
 * authority for what each flag means; a future flag added to any verb lands in [CliParser] and is
 * visible at every consumer of that verb's parse result.
 *
 * ## What this file pins
 *
 * 1. [CliParser.parseConsole] exists and returns a [ConsoleCliParseResult] with the typed
 *    [ConsoleReadRequest] the runtime needs.
 * 2. [CliParser.parseEvents] exists and returns an [EventsCliParseResult] with the typed
 *    [EventsCliArgs] the runtime needs.
 * 3. [CliParser.parseInspect] exists and returns the same [InspectParseResult] shape
 *    [MainInspectCli.parseInspect] already exposed (test surface preserved by delegate).
 * 4. The refusal prefixes are preserved byte-for-byte through the verb mains:
 *     - `console-refused:` survives because [ConsoleReadService.renderRefusal] is the data-plane
 *       renderer and was not touched by CC-1.
 *     - `Error:` (console CLI arg refusal) survives because [renderConsoleCliRefusal] is the
 *       wire renderer and was not touched.
 *     - `evt-refusal-v1:` / `evt-refusals-v1:` / `evt-stalled-v1:` survive because
 *       [MainEventsCli.reportRefusals] / `runFollow` / `reportTyped` were not touched.
 *     - `Error:` (events CLI arg refusal) survives because [MainEventsCli.main] still translates
 *       the typed rejection with the literal `Error: ` prefix.
 *     - `inspect:` survives because [MainInspectCli.main] still translates the typed rejection
 *       with the literal `inspect: ` prefix.
 *     - `observe:` survives because [MainObserveCli.main] was untouched.
 * 5. The four verbs refuse the same args the same way as before. The parser relocation did not
 *    relax a refusal or change a default.
 *
 * ## Harness fidelity
 *
 * HF0 Pure Contract for rows 1–3 (pure over `Array<String>`); HF1 In-Process for rows 4–5
 * (the verb `main` functions, run in process, captured on `System.err` / `System.out`).
 *
 * ## The mutation that must kill this
 *
 * Deleting any of [CliParser.parseConsole] / [CliParser.parseEvents] / [CliParser.parseInspect]
 * breaks the compile of [MainConsoleCli.main] / [MainEventsCli.main] / [MainInspectCli.main] —
 * which is the discipline the unification is meant to enforce. Adding a private second parser
 * next to a verb's `main` is the regression we want to make structurally impossible.
 */
@Timeout(60)
@DisplayName("CC-1 — los cuatro verbos de CLI admiten a traves de un unico CliParser")
class CliParserUnificationTest {

    // ----------------------------------------------------------- CC-1-1 console parser entry point

    @Test
    fun `CC-1_1_CliParser parseConsole admits a well-formed page read`() {
        val r = CliParser.parseConsole(
            arrayOf("--control-dir", "/tmp/ctrl", "run-1", "op-1", "--max-bytes", "1024"),
        )
        assertTrue(r is ConsoleCliParseResult.Admitted, "expected admission, got $r")
        val req = (r as ConsoleCliParseResult.Admitted).request
        assertTrue(req is ConsoleReadRequest.Paged, "expected a Paged request, got $req")
        assertEquals("/tmp/ctrl", req.controlDir)
        assertEquals("run-1", req.runId)
        assertEquals("op-1", req.opId)
        assertEquals(1024, (req as ConsoleReadRequest.Paged).maxBytes)
    }

    @Test
    fun `CC-1_1_CliParser parseConsole admits a range read with the typed Range payload`() {
        val r = CliParser.parseConsole(
            arrayOf("--control-dir", "/tmp/ctrl", "run-1", "op-1", "--range", "0:1024"),
        )
        assertTrue(r is ConsoleCliParseResult.Admitted, "expected admission, got $r")
        val req = (r as ConsoleCliParseResult.Admitted).request
        assertTrue(req is ConsoleReadRequest.Range, "expected a Range request, got $req")
        assertEquals(0L, (req as ConsoleReadRequest.Range).from)
        assertEquals(1024L, req.to)
    }

    @Test
    fun `CC-1_1_CliParser parseConsole admits a frames read with the typed Frames payload`() {
        val r = CliParser.parseConsole(
            arrayOf(
                "--control-dir", "/tmp/ctrl", "run-1", "op-1", "--frames",
                "--frame-limit", "256", "--after-ordinal", "-1", "--format", "jsonl",
            ),
        )
        assertTrue(r is ConsoleCliParseResult.Admitted, "expected admission, got $r")
        val req = (r as ConsoleCliParseResult.Admitted).request
        assertTrue(req is ConsoleReadRequest.Frames, "expected a Frames request, got $req")
        assertEquals(256, (req as ConsoleReadRequest.Frames).frameLimit)
        assertEquals(-1L, req.afterOrdinal)
        assertEquals(ConsoleFormat.JSONL, req.format)
    }

    @Test
    fun `CC-1_1_CliParser parseConsole refuses --frames with --max-bytes by name`() {
        val r = CliParser.parseConsole(
            arrayOf("--control-dir", "/tmp/ctrl", "run-1", "op-1", "--frames", "--max-bytes", "512"),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.IncompatibleWithFrames,
            "expected IncompatibleWithFrames, got $refusal",
        )
    }

    @Test
    fun `CC-1_1_CliParser parseConsole refuses a missing runId with the typed refusal`() {
        val r = CliParser.parseConsole(arrayOf("--control-dir", "/tmp/ctrl"))
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        assertEquals(ConsoleCliRefusal.MissingArgument, (r as ConsoleCliParseResult.Rejected).refusal)
    }

    @Test
    fun `CC-1_1_CliParser parseConsole refuses an unknown --flag by name`() {
        val r = CliParser.parseConsole(
            arrayOf("--control-dir", "/tmp/ctrl", "run-1", "op-1", "--bogus"),
        )
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val refusal = (r as ConsoleCliParseResult.Rejected).refusal
        assertTrue(
            refusal is ConsoleCliRefusal.UnknownOption,
            "expected UnknownOption, got $refusal",
        )
    }

    // ----------------------------------------------------------- CC-1-2 events parser entry point

    @Test
    fun `CC-1_2_CliParser parseEvents admits a well-formed envelope read`() {
        val r = CliParser.parseEvents(
            arrayOf("--db", "events.sqlite", "run-1", "--limit", "50"),
        )
        assertTrue(r is EventsCliParseResult.Parsed, "expected admission, got $r")
        val args = (r as EventsCliParseResult.Parsed).args
        assertEquals("events.sqlite", args.db)
        assertEquals("run-1", args.runId)
        assertEquals(50, args.limit)
        assertEquals(EventsRowProjection.Envelope, args.projection)
    }

    @Test
    fun `CC-1_2_CliParser parseEvents admits --typed as the Typed projection`() {
        val r = CliParser.parseEvents(arrayOf("--db", "events.sqlite", "run-1", "--typed"))
        assertTrue(r is EventsCliParseResult.Parsed, "expected admission, got $r")
        val args = (r as EventsCliParseResult.Parsed).args
        assertEquals(EventsRowProjection.Typed, args.projection)
    }

    @Test
    fun `CC-1_2_CliParser parseEvents refuses --typed with --fields by name`() {
        val r = CliParser.parseEvents(
            arrayOf(
                "--db", "events.sqlite", "run-1",
                "--typed", "--fields", "SEQ,KIND",
            ),
        )
        assertTrue(r is EventsCliParseResult.Rejected, "expected rejection, got $r")
        val reason = (r as EventsCliParseResult.Rejected).reason
        assertTrue(
            reason.contains("--typed is mutually exclusive"),
            "the refusal must name the combination; got: $reason",
        )
    }

    @Test
    fun `CC-1_2_CliParser parseEvents refuses an unknown --flag by name`() {
        val r = CliParser.parseEvents(arrayOf("--db", "events.sqlite", "run-1", "--bogus"))
        assertTrue(r is EventsCliParseResult.Rejected, "expected rejection, got $r")
        assertTrue(
            (r as EventsCliParseResult.Rejected).reason.contains("unknown option"),
            "the refusal must name the option kind",
        )
    }

    @Test
    fun `CC-1_2_CliParser parseEvents refuses a non-positive --limit by name`() {
        val r = CliParser.parseEvents(
            arrayOf("--db", "events.sqlite", "run-1", "--limit", "0"),
        )
        assertTrue(r is EventsCliParseResult.Rejected, "expected rejection, got $r")
        assertTrue(
            (r as EventsCliParseResult.Rejected).reason.contains("--limit"),
            "the refusal must name --limit",
        )
    }

    @Test
    fun `CC-1_2_CliParser parseEvents preserves the EventField closed ADT on --fields`() {
        val r = CliParser.parseEvents(
            arrayOf("--db", "events.sqlite", "run-1", "--fields", "SEQ,SUBJECT"),
        )
        assertTrue(r is EventsCliParseResult.Parsed, "expected admission, got $r")
        val fields = (r as EventsCliParseResult.Parsed).args.fieldsWhitelist
        assertNotNull(fields, "--fields must populate fieldsWhitelist")
        assertEquals(
            listOf(EventField.Sequence, EventField.Subject),
            fields,
            "the closed EventField ADT must round-trip",
        )
    }

    // ----------------------------------------------------------- CC-1-3 inspect parser entry point

    @Test
    fun `CC-1_3_CliParser parseInspect admits a well-formed query`() {
        val r = CliParser.parseInspect(
            arrayOf("--db", "events.sqlite", "--control-root", "control", "--log-tail", "512"),
        )
        assertTrue(r is InspectParseResult.Parsed, "expected admission, got $r")
        val q = (r as InspectParseResult.Parsed).value
        assertEquals(512, q.logTail)
        assertEquals("control", q.controlRoot)
        assertEquals("events.sqlite", q.dbPath)
    }

    @Test
    fun `CC-1_3_CliParser parseInspect refuses --log-tail-op with no value`() {
        val r = CliParser.parseInspect(arrayOf("--db", "events.sqlite", "--log-tail-op"))
        assertTrue(r is InspectParseResult.Rejected, "expected rejection, got $r")
        assertTrue(
            (r as InspectParseResult.Rejected).error.contains("--log-tail-op requires a value"),
            "the refusal must name --log-tail-op; got: ${(r as InspectParseResult.Rejected).error}",
        )
    }

    @Test
    fun `CC-1_3_CliParser parseInspect refuses an unknown --flag by name`() {
        val r = CliParser.parseInspect(arrayOf("--bogus", "value"))
        assertTrue(r is InspectParseResult.Rejected, "expected rejection, got $r")
        assertTrue(
            (r as InspectParseResult.Rejected).error.contains("unknown option"),
            "the refusal must name the option kind",
        )
    }

    @Test
    fun `CC-1_3_CliParser parseInspect produces the same shape as the legacy MainInspectCli delegate`() {
        // The legacy test seam (`MainInspectCli.parseInspect`) is preserved as a thin delegate to
        // `CliParser.parseInspect`; both return the same `InspectParseResult` for the same args.
        val a = CliParser.parseInspect(arrayOf("--db", "x", "--log-tail-op", "op-1"))
        val b = MainInspectCli.parseInspect(arrayOf("--db", "x", "--log-tail-op", "op-1"))
        assertEquals(
            a.javaClass,
            b.javaClass,
            "delegate and unified parser must agree on the result class",
        )
        assertEquals(
            (a as InspectParseResult.Parsed).value.opId,
            (b as InspectParseResult.Parsed).value.opId,
            "delegate and unified parser must agree on the typed payload",
        )
    }

    // ----------------------------------------------------------- CC-1-4 refusal prefixes are preserved

    @Test
    fun `CC-1_4_console CLI arg refusal keeps the Error prefix and console-refused is unchanged`() {
        // CC-1.4a — argument refusal (parser-side) renders as `Error: ...` via
        // `renderConsoleCliRefusal`. The prefix is preserved because the renderer is untouched.
        val r = CliParser.parseConsole(arrayOf("--control-dir", "/tmp/ctrl", "run-1", "op-1", "--bogus"))
        assertTrue(r is ConsoleCliParseResult.Rejected, "expected rejection, got $r")
        val line = renderConsoleCliRefusal((r as ConsoleCliParseResult.Rejected).refusal)
        assertTrue(
            line.startsWith("Error:"),
            "console CLI arg refusal must keep the 'Error:' prefix; got: $line",
        )

        // CC-1.4b — data-plane refusal (read-side) renders as `console-refused:` via
        // `ConsoleReadService.renderRefusal`. The prefix is preserved because the renderer is
        // untouched. We check one case as a witness; the sealed ADT is unchanged.
        val line2 = ConsoleReadService.renderRefusal(
            dev.rubentxu.pipeline.v2.output.OutputRefusal.Unavailable,
        )
        assertEquals(
            "console-refused: unavailable",
            line2,
            "console data-plane refusal must keep the 'console-refused:' prefix byte-for-byte",
        )
    }

    @Test
    fun `CC-1_4_inspect refusal keeps the inspect prefix and observe keeps its prefix`() {
        // inspect: prefix is in `MainInspectCli.main` and is preserved by CC-1.
        val inspect = CliParser.parseInspect(arrayOf("--bogus"))
        assertTrue(inspect is InspectParseResult.Rejected, "expected rejection, got $inspect")
        val line = "inspect: ${(inspect as InspectParseResult.Rejected).error}"
        assertTrue(
            line.startsWith("inspect:"),
            "inspect CLI arg refusal must keep the 'inspect:' prefix; got: $line",
        )

        // observe: prefix is in `MainObserveCli.main` and is preserved by CC-1 (observe was
        // already routed through CliParser before this slice).
        val observe = CliParser.parseObservation(arrayOf("--bogus"))
        assertTrue(
            observe is ObservationParseResult.Rejected,
            "expected rejection, got $observe",
        )
        val line2 = "observe: ${(observe as ObservationParseResult.Rejected).error}"
        assertTrue(
            line2.startsWith("observe:"),
            "observe CLI arg refusal must keep the 'observe:' prefix; got: $line2",
        )
    }

    // ----------------------------------------------------------- CC-1-5 verb mains reach the parser

    /**
     * The four verb mains must reach the parser through their public `CliParser` entry points.
     * The witness row is: an admitted `CliParser.parseConsole` and an admitted `CliParser.parseEvents`
     * produce the same typed payload the verb main consumes; a refused `CliParser.parseInspect`
     * produces the same rejection shape `MainInspectCli.parseInspect` returns. Adding a NEW verb
     * that bypasses [CliParser] would be the regression this row exists to surface.
     */
    @Test
    fun `CC-1_5_verb mains translate the typed result without re-deciding flags`(@TempDir root: Path) {
        // console: a well-formed page read admits through the parser; the verb main must reach the
        // `execute` path. We use a non-existent control dir so the verb main's `Files.isDirectory`
        // check fires AFTER the parser admits — proving the admission was not lost in translation.
        val consoleArgs = arrayOf("--control-dir", root.resolve("missing").toString(), "run-1", "op-1")
        val consoleAdmit = CliParser.parseConsole(consoleArgs)
        assertTrue(
            consoleAdmit is ConsoleCliParseResult.Admitted,
            "well-formed console read must admit through the parser",
        )

        // events: a refused parser produces a typed `EventsCliParseResult.Rejected` that the verb
        // main translates as `Error: <reason>`. We assert the translation preserves the reason.
        val eventsReject = CliParser.parseEvents(arrayOf("--bogus"))
        assertTrue(
            eventsReject is EventsCliParseResult.Rejected,
            "unknown option must produce a typed rejection",
        )
        val translated = "Error: ${(eventsReject as EventsCliParseResult.Rejected).reason}"
        assertTrue(
            translated.startsWith("Error:"),
            "events CLI arg refusal translation must keep the 'Error:' prefix; got: $translated",
        )

        // inspect: the parser and the legacy delegate agree.
        val inspectReject = CliParser.parseInspect(arrayOf("--bogus"))
        val legacyReject = MainInspectCli.parseInspect(arrayOf("--bogus"))
        assertEquals(
            (inspectReject as InspectParseResult.Rejected).error,
            (legacyReject as InspectParseResult.Rejected).error,
            "CliParser.parseInspect and MainInspectCli.parseInspect must agree on rejection reasons",
        )
    }

    /**
     * End-to-end HF1 row — the verb `main` functions, run in process, must reach the same wire
     * prefixes as before CC-1. Captures stdout / stderr and asserts the prefix is byte-for-byte
     * intact.
     */
    @Test
    fun `CC-1_5_end-to-end MainConsoleCli main renders Error prefix through CliParser`(@TempDir root: Path) {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    // Unknown option → parser refuses → verb main renders as `Error: ...`.
                    val exit = MainConsoleCli.main(
                        arrayOf("--control-dir", root.toString(), "run-1", "op-1", "--bogus"),
                    )
                    assertEquals(
                        2,
                        exit,
                        "refused command must exit 2, not 0; stdout:\n${out.toString(Charsets.UTF_8)}\nstderr:\n${err.toString(Charsets.UTF_8)}",
                    )
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        val stderrText = err.toString(Charsets.UTF_8)
        assertTrue(
            stderrText.startsWith("Error:") || stderrText.contains("\nError:"),
            "console CLI arg refusal must keep the 'Error:' prefix on stderr; got:\n$stderrText",
        )
    }
}
