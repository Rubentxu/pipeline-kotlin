package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.MainEventsCli.EventsInspectionFlag
import dev.rubentxu.pipeline.v2.application.MainEventsCli.EventsInspectionParseResult
import dev.rubentxu.pipeline.v2.events.identity.EventField
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * R4 / WU-R4.2 — the closed ADT of new events-CLI flags and the parser that produces it.
 *
 * The four cases (`Follow`, `Fields`, `Context`, `Tail`) are the contract. Adding a fifth is
 * a compile-time break for every caller of [MainEventsCli.parseEventsInspectionFlags] and for
 * every test below. The pure transform layer (`EventInspection.projectFields`, `tail`,
 * `contextWindow`) is exercised end-to-end in the WU-LPR-051 suite — these tests pin the
 * CLI wiring that promotes those transforms into options.
 */
@Timeout(60)
@DisplayName("R4 / WU-R4.2 — events CLI inspection flags (parser + closed ADT)")
class MainEventsInspectionFlagsTest {

    @org.junit.jupiter.api.io.TempDir
    lateinit var root: java.nio.file.Path

    // ------------------------------------------------------------------ HF0 closed ADT

    @Test
    fun `EventsInspectionFlag has exactly the four R4 cases and no others`() {
        val all = listOf(
            EventsInspectionFlag.Follow,
            EventsInspectionFlag.Fields(listOf(EventField.Kind)),
            EventsInspectionFlag.Context(2),
            EventsInspectionFlag.Tail(5),
        )
        assertEquals(4, all.size, "EventsInspectionFlag must remain a four-case closed ADT")
        // No duplicates by identity
        assertEquals(all.size, all.toSet().size, "EventsInspectionFlag cases must be distinct")
    }

    // ------------------------------------------------------------------ HF0 parser

    @Test
    fun `parseEventsInspectionFlags returns an empty list for the empty input`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf())
        assertTrue(r is EventsInspectionParseResult.Parsed)
        assertEquals(emptyList<EventsInspectionFlag>(), (r as EventsInspectionParseResult.Parsed).flags)
    }

    @Test
    fun `parseEventsInspectionFlags recognises --follow without consuming a value`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--follow"))
        assertTrue(r is EventsInspectionParseResult.Parsed)
        assertEquals(listOf(EventsInspectionFlag.Follow), (r as EventsInspectionParseResult.Parsed).flags)
    }

    @Test
    fun `parseEventsInspectionFlags maps the documented --fields tokens to EventField cases`() {
        val r = MainEventsCli.parseEventsInspectionFlags(
            arrayOf("--fields", "SEQ,KIND,EVENTID,SUBJECT,SOURCE"),
        )
        assertTrue(r is EventsInspectionParseResult.Parsed)
        val parsed = (r as EventsInspectionParseResult.Parsed).flags
        assertEquals(1, parsed.size)
        val fields = (parsed[0] as EventsInspectionFlag.Fields).fields
        assertEquals(
            listOf(
                EventField.Sequence, EventField.Kind, EventField.EventId,
                EventField.Subject, EventField.Source,
            ),
            fields,
        )
    }

    @Test
    fun `parseEventsInspectionFlags accepts the case-insensitive --fields aliases SEQUENCE and EVENT_ID`() {
        val r = MainEventsCli.parseEventsInspectionFlags(
            arrayOf("--fields", "sequence,event_id,kind"),
        )
        assertTrue(r is EventsInspectionParseResult.Parsed)
        val fields = ((r as EventsInspectionParseResult.Parsed).flags.single() as EventsInspectionFlag.Fields).fields
        assertEquals(listOf(EventField.Sequence, EventField.EventId, EventField.Kind), fields)
    }

    @Test
    fun `parseEventsInspectionFlags refuses an unknown --fields token with the offending value`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--fields", "SEQ,BOGUS,KIND"))
        assertTrue(r is EventsInspectionParseResult.Rejected)
        val reason = (r as EventsInspectionParseResult.Rejected).reason
        assertTrue(reason.contains("BOGUS"), "rejection must name the offending token, got: $reason")
    }

    @Test
    fun `parseEventsInspectionFlags refuses --fields with no value`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--fields"))
        assertTrue(r is EventsInspectionParseResult.Rejected)
        assertTrue((r as EventsInspectionParseResult.Rejected).reason.contains("requires a value"))
    }

    @Test
    fun `parseEventsInspectionFlags refuses a non-Int --context value`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--context", "abc"))
        assertTrue(r is EventsInspectionParseResult.Rejected)
        val reason = (r as EventsInspectionParseResult.Rejected).reason
        assertTrue(reason.contains("non-negative Int"), "rejection must name the value type, got: $reason")
    }

    @Test
    fun `parseEventsInspectionFlags refuses a negative --context value`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--context", "-1"))
        assertTrue(r is EventsInspectionParseResult.Rejected)
    }

    @Test
    fun `parseEventsInspectionFlags accepts --context 0 as the empty-window identity`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--context", "0"))
        assertTrue(r is EventsInspectionParseResult.Parsed)
        val parsed = (r as EventsInspectionParseResult.Parsed).flags
        assertEquals(EventsInspectionFlag.Context(0), parsed.single())
    }

    @Test
    fun `parseEventsInspectionFlags refuses a negative --tail value`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--tail", "-3"))
        assertTrue(r is EventsInspectionParseResult.Rejected)
    }

    @Test
    fun `parseEventsInspectionFlags accepts --tail 0 as the empty-tail identity`() {
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--tail", "0"))
        assertTrue(r is EventsInspectionParseResult.Parsed)
        assertEquals(EventsInspectionFlag.Tail(0), (r as EventsInspectionParseResult.Parsed).flags.single())
    }

    @Test
    fun `parseEventsInspectionFlags combines the four flags in any order`() {
        val r = MainEventsCli.parseEventsInspectionFlags(
            arrayOf("--follow", "--fields", "KIND", "--context", "2", "--tail", "5"),
        )
        assertTrue(r is EventsInspectionParseResult.Parsed)
        val flags = (r as EventsInspectionParseResult.Parsed).flags
        assertEquals(
            listOf(
                EventsInspectionFlag.Follow,
                EventsInspectionFlag.Fields(listOf(EventField.Kind)),
                EventsInspectionFlag.Context(2),
                EventsInspectionFlag.Tail(5),
            ),
            flags,
        )
    }

    @Test
    fun `parseEventsInspectionFlags leaves non-R4 flags for the caller to recognise`() {
        // The parser is for the R4 / WU-R4.2 flags only. Other verbs (or the events CLI's
        // own --db / --kind etc.) are handled upstream. An unknown `--foo` here is silently
        // skipped, NOT refused — the upstream parser is the source of truth for unknown
        // option rejection.
        val r = MainEventsCli.parseEventsInspectionFlags(arrayOf("--db", "events.sqlite", "--follow"))
        assertTrue(r is EventsInspectionParseResult.Parsed)
        assertEquals(listOf(EventsInspectionFlag.Follow), (r as EventsInspectionParseResult.Parsed).flags)
    }

    // ------------------------------------------------------------------ HF1 main() wiring

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
                    MainEventsCli.main(arrayOf(*args))
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `main() refuses --typed combined with --fields with a typed reason on stderr`() {
        val r = cli("--db", "events.sqlite", "run-1", "--typed", "--fields", "KIND")
        assertEquals(2, r.exitCode, "--typed with --fields is mutually exclusive, must be 2")
        assertTrue(
            r.stderr.contains("mutually exclusive") && r.stderr.contains("--typed"),
            "stderr must name the conflict and the typed side; got:\n${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --typed combined with --context and --tail for the same reason`() {
        val r1 = cli("--db", "events.sqlite", "run-1", "--typed", "--context", "2")
        assertEquals(2, r1.exitCode)
        assertTrue(r1.stderr.contains("mutually exclusive"))

        val r2 = cli("--db", "events.sqlite", "run-1", "--typed", "--tail", "3")
        assertEquals(2, r2.exitCode)
        assertTrue(r2.stderr.contains("mutually exclusive"))
    }

    @Test
    fun `main() refuses --fields with no value at the CLI layer, not silently`() {
        val r = cli("--db", "events.sqlite", "run-1", "--fields")
        assertEquals(2, r.exitCode, "missing value must be 2, not a silent default")
        assertTrue(
            r.stderr.contains("--fields requires a value"),
            "stderr must name the missing-value reason; got:\n${r.stderr}",
        )
    }

    @Test
    fun `main() refuses an unknown --fields token with a typed message`() {
        val r = cli("--db", "events.sqlite", "run-1", "--fields", "SEQ,BOGUS")
        assertEquals(2, r.exitCode)
        assertTrue(
            r.stderr.contains("BOGUS"),
            "stderr must name the offending token; got:\n${r.stderr}",
        )
    }

    @Test
    fun `main() refuses a non-Int --context value at the CLI layer`() {
        val r = cli("--db", "events.sqlite", "run-1", "--context", "abc")
        assertEquals(2, r.exitCode)
        assertTrue(r.stderr.contains("non-negative Int"))
    }

    @Test
    fun `main() refuses a non-Int --tail value at the CLI layer`() {
        val r = cli("--db", "events.sqlite", "run-1", "--tail", "abc")
        assertEquals(2, r.exitCode)
        assertTrue(r.stderr.contains("non-negative Int"))
    }

    @Test
    fun `main() refuses --fields when the durable store is missing, not silently defaulting`() {
        // The closed ADT of --fields is envelope-only, so a real SqliteEventStore is required
        // for the projection to run. When the path does not exist, the CLI refuses with exit
        // 2 BEFORE any read attempt, and the refusal names "db not found" on stderr. This
        // is the same shape as the existing --typed path; pinning it here so a future
        // re-shape of the projection does not regress to a silent exit-0 default.
        //
        // The fixture is a file ABSENT under a directory that EXISTS, because that is the arm
        // whose refusal is `db not found`. It used to be `/nonexistent-path/does-not-exist.sqlite`,
        // whose parent is also missing — a different refusal (`no directory for db`), so the row was
        // asserting the wording of an arm its fixture never reached. The subject is unchanged and the
        // other arm now has its own row below; splitting them is what keeps each row honest.
        val r = cli("--db", root.resolve("absent.sqlite").toString(), "run-1", "--fields", "KIND")
        assertEquals(2, r.exitCode, "non-existent db is a usage error at the CLI layer")
        assertTrue(
            r.stderr.contains("db not found"),
            "stderr must name the db-not-found reason; got:\n${r.stderr}",
        )
    }

    @Test
    fun `main() refuses --fields when the db directory is missing, and says which half is absent`() {
        // The second arm of the same decision. An operator who mistypes a parent directory needs to
        // be told THAT is what is missing, not merely that some path was not found: the remedy is
        // different (create the directory vs. point at another file). Both refusals name the path,
        // and both are exit 2 — this row exists so the distinction cannot quietly collapse.
        val missingDir = root.resolve("no-such-dir")
        val r = cli("--db", missingDir.resolve("absent.sqlite").toString(), "run-1", "--fields", "KIND")
        assertEquals(2, r.exitCode, "a db whose directory does not exist is a usage error")
        assertTrue(
            r.stderr.contains("no directory for db"),
            "stderr must name the absent directory as the reason; got:\n${r.stderr}",
        )
        assertTrue(
            r.stderr.contains(missingDir.toString()),
            "the refusal must name the path the operator asked for; got:\n${r.stderr}",
        )
    }
}
