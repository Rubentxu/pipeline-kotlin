package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

/**
 * S5.4 / R2 / D-M5 — the events CLI must SHOW the refusal, and its continuation must get past it.
 *
 * ## The production entry point this crosses
 *
 * [MainEventsCli.main], in process, over a REAL SQLite file written by the REAL
 * [SqliteEventStore]. Nothing about the store is doubled; the only thing that does not run is the
 * distribution image, so this is not the installed-distribution harness (HF2) — the external
 * two-process vertical is, and it is what certifies the installed bytes.
 *
 * ## Why the unreadable row is inserted with SQL
 *
 * No public API can write a row this runtime cannot read, which is the point of the row: it stands
 * for history written by a newer runtime, or for corruption. So the fixture appends the readable rows
 * through [SqliteEventStore.append] and writes the unreadable one over the store's own public
 * `underlyingConnectionFactory()`. The alternative — a store double that returns refusals — is what
 * `EventPageDrainTest` already does, and doing it here too would test nothing new.
 *
 * ## The mutation that must kill this
 *
 * **D-M5**: putting `history(run, query).filter { … }.take(limit)` back into [MainEventsCli] while
 * leaving the reader fixed. Measured, because the attribution is not the obvious one:
 *
 * - tests 1 and 2 die with `UndecodableEventRecordException`, not with a quiet omission. `history`
 *   routes to `EventStore.eventsFor`, which cannot represent a refusal and throws at the unreadable
 *   row. So the old CLI lost the whole observation — stack trace, no history, an exit status the
 *   command never chose — rather than hiding one row. These two tests are written against the FIXED
 *   behaviour and would still pass if the defect were a silence; they fail because the read did not
 *   survive at all.
 * - test 3 dies on the continuation, and it dies silently. The legacy cursor came from the last
 *   MATCHING row, so a filter that matches nothing yields `lastSequence = 0` — "start of history" —
 *   after the store was read to its end, and a resuming reader re-reads the entire run.
 *
 * The cursor-past-a-trailing-refusal property is proved at the reader level instead, in
 * `EventPageDrainTest`, because through this CLI the exception above always fired first and the
 * legacy cursor was never reached.
 */
@DisplayName("S5.4 — la CLI de eventos ve el rechazo y su cursor lo pasa")
class MainEventsCliRefusalVisibilityTest {

    private val runId = "run-cli-refusal"

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun stdoutLines(): List<String> = stdout.lineSequence().filter { it.isNotBlank() }.toList()
    }

    /**
     * Runs the real CLI entry point and captures both streams.
     *
     * `Main` drops the `events` verb before dispatching here, so the helper does not pass it: a
     * leading `events` would be read as the run id, which is a test that passes by reading the wrong
     * history.
     */
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

    /** Readable rows, written the way the product writes them. */
    private fun appendValid(db: Path, count: Int) {
        SqliteEventStore(db.toString()).use { store ->
            repeat(count) { index ->
                store.append(
                    RunStarted(
                        eventId = "evt-$index",
                        runId = runId,
                        sequence = 0L,
                        occurredAt = Instant.parse("2026-10-06T12:00:00Z"),
                        scriptPath = "/tmp/run-$index.pipeline.kts",
                    ),
                )
            }
        }
    }

    /**
     * One durable row this runtime refuses, at [sequence].
     *
     * @param kind a kind with no decoder here produces `unknownKind`; a known kind with a payload
     *   that is not its schema produces `malformedPayload`. They stay distinguishable on the wire,
     *   because version skew and corruption are different facts for whoever reads them.
     */
    private fun insertUnreadable(db: Path, sequence: Long, kind: String, payload: String) {
        SqliteEventStore(db.toString()).use { store ->
            store.underlyingConnectionFactory()().use { conn: Connection ->
                conn.prepareStatement(
                    "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, "evt-unreadable-$sequence")
                    ps.setString(2, runId)
                    ps.setLong(3, sequence)
                    ps.setString(4, kind)
                    ps.setString(5, "2026-10-06T12:00:00Z")
                    ps.setString(6, payload)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun cursorFrom(stderr: String): EventCursor? =
        Regex("evt-cursor-v1:([^:]+):(\\d+)").find(stderr)
            ?.let { EventCursor.decode("evt-cursor-v1:${it.groupValues[1]}:${it.groupValues[2]}") }

    @Test
    fun `una fila ilegible en mitad de la historia sale en la salida y no desaparece`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 40)                                            // sequences 1..40
        insertUnreadable(db, 41L, "RunStarted", "{ this is not the schema")   // malformedPayload
        appendValid(db, 1)                                             // sequence 42

        val result = cli("--db", db.toString(), runId, "--limit", "100")

        assertEquals(0, result.exitCode, "a page that carries a refusal is still a successful read")
        assertEquals(41, result.stdoutLines().size, "40 and 42 decode; the unreadable row is not invented")
        assertTrue(
            result.stderr.contains("evt-refusal-v1:"),
            "the refusal must be on the wire, not merely in the type; stderr:\n${result.stderr}",
        )
        assertTrue(
            Regex("evt-refusal-v1:[^:]*:41:malformedPayload:RunStarted").containsMatchIn(result.stderr),
            "the refusal line must name row 41 and its real reason; stderr:\n${result.stderr}",
        )
        assertTrue(
            result.stderr.contains("evt-refusals-v1:") && result.stderr.contains(":1"),
            "the count makes 'were there any' a single-token question; stderr:\n${result.stderr}",
        )
        assertEquals(42L, cursorFrom(result.stderr)?.lastSequence, "row 42 was read, so the cursor is at 42")
    }

    @Test
    fun `el cursor pasa por una fila ilegible que es la ultima en vez de atascarse antes`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 40)                                       // sequences 1..40
        insertUnreadable(db, 41L, "plugin.from.the.future", "{\"anything\":1}")  // unknownKind

        val first = cli("--db", db.toString(), runId, "--limit", "100")

        assertEquals(0, first.exitCode)
        assertEquals(40, first.stdoutLines().size)
        assertTrue(
            Regex("evt-refusal-v1:[^:]*:41:unknownKind:plugin.from.the.future").containsMatchIn(first.stderr),
            "an unknown plugin kind is version skew, and says so; stderr:\n${first.stderr}",
        )
        assertEquals(
            41L,
            cursorFrom(first.stderr)?.lastSequence,
            "the continuation is the last ROW read, which is the unreadable one, because the store — not " +
                "the filtered stream — decides where a page ends",
        )

        val token = cursorFrom(first.stderr)?.encode()
        assertNotNull(token, "the continuation must be resumable")
        val second = cli("--db", db.toString(), runId, "--after-cursor", token!!, "--limit", "100")

        assertEquals(0, second.exitCode)
        assertEquals(
            0,
            second.stdoutLines().size,
            "resuming past the refusal must reach the end of history, not repeat it",
        )
        assertTrue(
            !second.stderr.contains("evt-refusal-v1:"),
            "and must not report the same refusal again; stderr:\n${second.stderr}",
        )
    }

    @Test
    fun `un filtro que no casa devuelve la historia vacia y no se equivoca de final`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 3)

        val result = cli("--db", db.toString(), runId, "--kind", "NoSuchKind", "--limit", "10")

        assertEquals(0, result.exitCode)
        assertTrue(result.stdoutLines().isEmpty(), "nothing matches, so nothing is emitted")
        assertEquals(
            3L,
            cursorFrom(result.stderr)?.lastSequence,
            "a filter that matches nothing still reached the end of history, and the continuation says 3. " +
                "A cursor taken from the last MATCHING row would be 0 here — start of history — so resuming " +
                "would re-read the whole run.",
        )
    }

    @Test
    fun `un limite que no es un entero positivo se rechaza en vez de volverse el valor por defecto`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 1)

        listOf("0", "-1", "abc").forEach { bad ->
            val result = cli("--db", db.toString(), runId, "--limit", bad)
            assertEquals(2, result.exitCode, "--limit $bad must be rejected, not silently read as 100")
        }
    }

    @Test
    fun `un cursor de otro run se rechaza en vez de saltarse filas`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 2)

        val foreign = EventCursor("some-other-run", 1L).encode()
        val result = cli("--db", db.toString(), runId, "--after-cursor", foreign, "--limit", "10")

        assertEquals(2, result.exitCode, "a token carries its run id so it cannot be read as another run's position")
        assertTrue(
            result.stderr.contains("belongs to run"),
            "and says why; stderr:\n${result.stderr}",
        )
    }
}
