package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * S5.4 / R6 / R7 — C3, the external-process vertical: two OS processes, one durable cursor.
 *
 * ## What this crosses that nothing else does
 *
 * Every other law in this block runs the observation IN this JVM: the drain test drives a doubled
 * store, the store contract test drives the real store in-process, and the CLI visibility test drives
 * the real CLI entry point in-process. This one forks the INSTALLED BINARY, twice, and lets the
 * second process learn where the first stopped from nothing but the bytes the first wrote to stderr.
 * That is the whole of BLOCK C's claim — an external consumer observes typed facts and continues
 * after a restart without scraping logs — and it cannot be shown in-process.
 *
 * ## Two parts, because they fail for different reasons
 *
 * 1. A REAL pipeline run, read and resumed across two processes. This is the product writing its own
 *    history and the binary reading it back. Nothing in the fixture is injected.
 * 2. A fixture run whose row 41 cannot be decoded, read and resumed across two processes. Here the
 *    rows ARE injected, because no public API can write a row this runtime cannot read — that is the
 *    definition of the row. What is NOT injected is the observation: the reader is the installed
 *    binary, in its own JVM, and the refusal has to survive the process boundary to be seen.
 *
 * ## What this deliberately does not claim
 *
 * That the fixture rows came from a real run. Part 2 does not need them to, and asserting otherwise
 * would be decoration. The property under test is about the READER being external and durable, and
 * part 1 already carries the real-run half.
 *
 * ## The mutation that must kill this
 *
 * D-M5, the CLI reverted to `history(...) + take(limit)`. Part 2's first process dies on
 * `UndecodableEventRecordException` instead of emitting a continuation, and part 1's resume cannot
 * start because there is no token to resume from.
 */
@Timeout(10, unit = TimeUnit.MINUTES)
@DisplayName("S5.4 — dos procesos externos, un cursor durable, y el rechazo cruza el frontera")
class S54ExternalVerticalRestartUatTest {

    private val binary: File = AppBinSupport.discover().toFile()
    private val fixtureRunId = "run-c3-external-vertical"

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun envelopes(): List<Long> = stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                Regex("\"sequence\":(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
            }
            .toList()

        fun refusals(): List<String> = stderr.lineSequence()
            .filter { it.startsWith("evt-refusal-v1:") }
            .toList()

        fun continuation(): String? = stderr.lineSequence()
            .firstOrNull { it.startsWith("evt-cursor-v1:") }
            ?.trim()
    }

    /** A real OS process running the installed binary. Nothing here is shared with this JVM. */
    private fun events(vararg args: String): CliResult {
        val proc = ProcessBuilder(binary.absolutePath, "events", *args).start()
        assertTrue(proc.waitFor(5, TimeUnit.MINUTES), "events CLI hung on ${args.toList()}")
        return CliResult(
            proc.exitValue(),
            proc.inputStream.bufferedReader().readText(),
            proc.errorStream.bufferedReader().readText(),
        )
    }

    private fun run(vararg args: String): CliResult {
        val proc = ProcessBuilder(binary.absolutePath, *args).start()
        assertTrue(proc.waitFor(5, TimeUnit.MINUTES), "pipeline hung on ${args.toList()}")
        return CliResult(
            proc.exitValue(),
            proc.inputStream.bufferedReader().readText(),
            proc.errorStream.bufferedReader().readText(),
        )
    }

    private fun insertUnreadable(dbPath: String, sequence: Long, kind: String, payload: String) {
        SqliteEventStore(dbPath).use { store ->
            store.underlyingConnectionFactory()().use { conn: Connection ->
                conn.prepareStatement(
                    "INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, "e-unreadable-$sequence")
                    ps.setString(2, fixtureRunId)
                    ps.setLong(3, sequence)
                    ps.setString(4, kind)
                    ps.setString(5, Instant.parse("2026-10-06T12:00:00Z").toString())
                    ps.setString(6, payload)
                    ps.executeUpdate()
                }
            }
        }
    }

    /**
     * Rows 1..40 and 42 through the store; row 41 through SQL, because it is one this runtime
     * cannot read and no write API can express that.
     *
     * The third block opens a NEW store so the sequence counter re-seeds from the durable MAX, which
     * is now 41. Appending on the previous instance would have handed out 41 again and collided with
     * the unreadable row on the unique index.
     */
    private fun seedFixtureRun(dbPath: String) {
        SqliteEventStore(dbPath).use { store ->
            repeat(40) { index -> store.append(fixtureEvent(index)) }
        }
        insertUnreadable(dbPath, 41L, "plugin.from.the.future", "{\"anything\":1}")
        SqliteEventStore(dbPath).use { store -> store.append(fixtureEvent(900)) }
    }

    private fun fixtureEvent(index: Int) = dev.rubentxu.pipeline.v2.events.RunStarted(
        eventId = "e$index",
        runId = fixtureRunId,
        sequence = 0L,
        occurredAt = Instant.parse("2026-10-06T12:00:00Z"),
        scriptPath = "/tmp/c3.pipeline.kts",
    )

    @Test
    fun `parte 1 - una corrida real se lee y se reanuda entre dos procesos`(@TempDir dir: Path) {
        val script = Files.writeString(
            dir.resolve("real.pipeline.kts"),
            """
            pipeline {
                stages {
                    stage("c3") {
                        echo("observable")
                    }
                }
            }
            """.trimIndent(),
        )
        val db = dir.resolve("real.sqlite").toString()

        val runResult = run("run", "--format", "json", "--db", db, script.toString())
        assertEquals(0, runResult.exitCode, "run must succeed; stderr:\n${runResult.stderr.takeLast(300)}")
        val runId = Regex("\"runId\":\"([^\"]+)\"").find(runResult.stdout)!!.groupValues[1]

        // Process A reads the first page and dies. It leaves nothing but a token.
        val first = events("--db", db, runId, "--limit", "2")
        assertEquals(0, first.exitCode, "process A must succeed; stderr:\n${first.stderr.takeLast(300)}")
        val pageOne = first.envelopes()
        assertEquals(2, pageOne.size, "process A was asked for two events")
        assertTrue(pageOne == pageOne.sorted(), "and the order is the store's sequence order")

        val token = first.continuation()
        assertNotNull(token, "process A must leave a resume token on stderr")

        // Process B starts clean, knows nothing but the token, and continues.
        val second = events("--db", db, runId, "--after-cursor", token!!, "--limit", "1000")
        assertEquals(0, second.exitCode, "process B must succeed; stderr:\n${second.stderr.takeLast(300)}")

        val everything = pageOne + second.envelopes()
        assertEquals(
            everything.distinct().sorted(),
            everything,
            "no event is delivered twice across the two processes",
        )
        assertTrue(
            pageOne.all { it <= second.envelopes().first() },
            "process B resumes strictly after process A stopped",
        )
        assertTrue(everything.isNotEmpty(), "and the run is not empty")
    }

    @Test
    fun `parte 2 - el rechazo cruza el frontera de proceso y el cursor lo pasa`(@TempDir dir: Path) {
        val db = dir.resolve("fixture.sqlite").toString()
        seedFixtureRun(db)

        val first = events("--db", db, fixtureRunId, "--limit", "1000")
        assertEquals(0, first.exitCode, "process A must survive an unreadable row; stderr:\n${first.stderr}")
        assertEquals(1, first.refusals().size, "process A must report the refusal exactly once")
        val readable = first.envelopes()
        assertEquals(41, readable.size, "rows 1..40 and 42 decode; 41 does not become an event")
        assertEquals(42L, readable.max(), "and the run does not stop at the unreadable row")

        val token = first.continuation()
        assertNotNull(token, "process A must leave a resume token")
        assertTrue(
            token!!.endsWith(":42"),
            "the token is the store's row position, so it must be past the last row read, which is 42; " +
                "got '$token'",
        )

        // Process B resumes past the refusal and must not see it again.
        val second = events("--db", db, fixtureRunId, "--after-cursor", token, "--limit", "1000")
        assertEquals(0, second.exitCode)
        assertTrue(
            second.refusals().isEmpty(),
            "the refusal was already reported by process A; stderr:\n${second.stderr}",
        )
        assertTrue(
            second.envelopes().isEmpty(),
            "and resuming past it must reach the end, not repeat it",
        )
    }
}
