package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant

/**
 * AUD-04 parity — the `events` CLI must not run a DIFFERENT command than the caller typed, and still
 * exit 0.
 *
 * ## The defect this certifies, and who introduced it
 *
 * The parser was `else -> if (!args[i].startsWith("--") && runId == null) runId = args[i]`, added by
 * `a84b73f9` (EVT-2). An unknown `--flag` matched the `else` arm, failed the `startsWith("--")` test,
 * and fell off the end of the `when`. The same held for a second positional, which was dropped while
 * the first was kept. Both paths returned 0.
 *
 * This is not a merge conflict; it is a hole in this branch, and it was live before any integration.
 * `s6-plugin-sdk` independently hardened the same parser under AUD-04. The rows below assert the
 * **behaviour S6 already accepted**, so whichever copy of the file survives a merge answers the same
 * way. Nothing here decides the timing of that integration.
 *
 * ## Why exit 0 is the defect and not the style
 *
 * A caller cannot tell a refused command from a served one by status. The concrete caller is the
 * external consumer, which issues
 *
 * ```
 * pipeline events --db events.db RUN_ID --limit 4 --typed
 * ```
 *
 * and, against this parser, receives untyped envelopes with status 0. It asked for payload it did not
 * get, and the only signal is one it did not look for. That is the silent-failure shape the
 * strict-parse convention exists to prevent — and it is already this branch's own convention:
 * [CliParser] refuses an unknown option with [CliError.TrailingOption], and [MainObserveCli] is strict
 * because it delegates there. This file was written before that convention and never updated.
 *
 * ## The production entry point this crosses
 *
 * [MainEventsCli.main], in process, over a REAL SQLite file written by the REAL [SqliteEventStore].
 * Nothing about the parser, the store or the reader is doubled, so HF1 (in-process) is faithful here;
 * it is not HF2, and nothing below claims the installed distribution.
 *
 * ## The mutations that must kill this
 *
 * - **D-ARG-1** — restore `else -> if (!args[i].startsWith("--") && runId == null) runId = args[i]`.
 *   Kills UNKNOWN-1 and UNKNOWN-2 (they become exit 0 with 8 envelopes).
 * - **D-ARG-2** — keep the unknown-option refusal but drop the `runId != null` arm. Kills EXTRA-1
 *   alone: the second positional is dropped and exit stays 0.
 * - **D-ARG-3** — refuse before parsing, i.e. return 2 from the top of `main` for every invocation.
 *   Killed by KNOWN-1, which is the only row asserting a served command; without it, "reject
 *   everything" would satisfy UNKNOWN-1, UNKNOWN-2 and EXTRA-1.
 */
@DisplayName("AUD-04 — la CLI de eventos ejecuta el comando que se le escribió, o lo rechaza")
class MainEventsCliArgumentStrictnessTest {

    private val runId = "run-cli-args"

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun stdoutLines(): List<String> = stdout.lineSequence().filter { it.isNotBlank() }.toList()
    }

    /** Runs the real CLI entry point and captures both streams. The verb is dropped by `Main`. */
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

    @Test
    fun `KNOWN-1 una llamada bien formada se sirve y sale con 0`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 8)

        val result = cli("--db", db.toString(), runId, "--limit", "4")

        assertEquals(0, result.exitCode, "a parse that succeeds must still be served")
        assertEquals(4, result.stdoutLines().size, "--limit 4 is honoured, not ignored")
    }

    @Test
    fun `UNKNOWN-1 una opcion desconocida se rechaza y no emite ninguna historia`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 8)

        // The exact shape the external consumer issues. `--typed` does not exist here.
        val result = cli("--db", db.toString(), runId, "--limit", "4", "--typed")

        assertEquals(2, result.exitCode, "an option this build does not have is an error, not a no-op")
        assertTrue(
            result.stdoutLines().isEmpty(),
            "and it emitted no history: serving untyped envelopes under a typed flag is the failure " +
                "being refused, not a lesser version of it",
        )
        assertTrue(
            result.stderr.contains("unknown option: --typed"),
            "and it names the offending option; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `UNKNOWN-2 el rechazo ocurre antes de abrir el almacen`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist.sqlite")

        val result = cli("--db", missing.toString(), runId, "--typed")

        assertEquals(2, result.exitCode)
        assertTrue(
            result.stderr.contains("unknown option: --typed"),
            "the argument is rejected while parsing, before any filesystem or store question is asked; " +
                "stderr:\n${result.stderr}",
        )
        assertTrue(
            !result.stderr.contains("db not found"),
            "a bad flag is the first thing said, so a caller fixing one error at a time is not sent " +
                "after a second one that a corrected flag would remove",
        )
    }

    @Test
    fun `EXTRA-1 un segundo posicional se rechaza en vez de descartarse`(@TempDir dir: Path) {
        val db = dir.resolve("events.sqlite")
        appendValid(db, 3)

        val result = cli("--db", db.toString(), runId, "some-other-run")

        assertEquals(2, result.exitCode, "the command reads ONE run; a second one was silently dropped")
        assertTrue(
            result.stdoutLines().isEmpty(),
            "and it did not answer for the run it silently substituted",
        )
        assertTrue(
            result.stderr.contains("unexpected extra argument: some-other-run"),
            "naming what was dropped is what makes this diagnosable; stderr:\n${result.stderr}",
        )
    }
}
