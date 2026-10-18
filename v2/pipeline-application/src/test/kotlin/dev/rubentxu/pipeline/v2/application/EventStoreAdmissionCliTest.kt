package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * D3 — a `--db` pointing nowhere let a raw `java.sql.SQLException` escape `main`.
 *
 * ## The defect, measured against the installed distribution
 *
 * ```text
 * $ pipelinek inspect some-run --db /tmp/probe/no-such-dir/events.db --control-root /tmp/probe/ctl
 * Exception in thread "main" java.sql.SQLException: path to '/tmp/probe/no-such-dir/events.db':
 *   '/tmp/probe/no-such-dir' does not exist
 *       at org.sqlite.SQLiteConnection.open(SQLiteConnection.java:261)
 *       ... 12 more frames ...
 *       at dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore.<init>(SqliteEventStore.kt:145)
 *       at dev.rubentxu.pipeline.v2.application.MainInspectCli.main(MainInspectCli.kt:178)
 * $ echo $?
 * 1
 * ```
 *
 * Measured across every verb reading an event store, on the same bad path:
 *
 * | verb      | on a `--db` that cannot be opened   | exit |
 * |-----------|--------------------------------------|------|
 * | `events`  | `Error: db not found: <path>`       | 2    |
 * | `observe` | raw `SQLException` + stack trace    | 1    |
 * | `inspect` | raw `SQLException` + stack trace    | 1    |
 * | `run`     | raw `SQLException` + stack trace    | 1    |
 *
 * Three reactions to one condition, and the correct one was ALREADY WRITTEN TWICE in this
 * repository — `MainEventsCli.kt` and `MainEventsVerifyCli.kt` each carry their own
 * `if (!Files.exists(...)) { println("Error: db not found: $db"); return 2 }`. The other three
 * verbs simply never asked. That is a second authority in the AGENTS.md DR-1 sense: the decision
 * exists, it is duplicated, and it is not the one every reader consults.
 *
 * ## Why a stack trace is the wrong answer, and not merely ugly
 *
 * An operator who typos `--db` is told a `java.sql.SQLException` stack trace and an exit code of
 * `1`. Exit `1` is this CLI's word for "the pipeline FAILED" — a run that executed and failed. A
 * mistyped path did not execute anything. So the same exit code now means two incompatible things
 * depending on whether the operator or the pipeline caused it, and the stack trace is 14 frames of
 * `org.sqlite` internals that answer no question the operator asked.
 *
 * The refusal the working verb already produces is three words and a path.
 *
 * ## The production entry point this crosses
 *
 * ```text
 * MainInspectCli.main(argv)                    in-process, real stdout/stderr, real exit code
 *   -> CliParser.parseInspect                  the argument is well-formed
 *   -> EventStoreAdmission                     THE decision: may this store be read?
 *   -> SqliteEventStore                        the real store, only when admitted
 * ```
 *
 * **HF1, in-process.** The verbs are called in `main`, on real argv, against real directories
 * that do and do not exist. No store double, no reader reimplementation.
 *
 * ## The control row, and why it is load-bearing
 *
 * A refusal fix that refuses too much is a worse product than the leak: `inspect` against a real
 * database with no such run is exit `0` with an empty table today, and it must stay that way.
 * Without `ADMIT_3`, a factory that refuses every unreadable-looking path — including an empty but
 * perfectly valid SQLite file — would be green on the refusal rows and broken for the operator.
 *
 * ## The mutation that must kill this
 *
 * ```text
 * M-1  the admission, reverted to constructing the store unconditionally, at ONE site:
 *        SqliteEventStore(compiled.dbPath)     <- the pre-fix line
 * ```
 *
 * Measured, both ways, with the mutant's file SHA-256 verified before the result was read:
 *
 * ```text
 * M-1 at MainInspectCli   ADMIT_1 RED (java.sql.SQLException, the original defect)
 *                        ADMIT_2 GREEN, ADMIT_3 GREEN
 * M-1 at MainObserveCli   ADMIT_2 RED (same exception, from the other verb)
 *                        ADMIT_1 GREEN, ADMIT_3 GREEN
 * ```
 *
 * Each refusal row dies for its OWN subject and only for its own subject. That is the property that
 * makes the two rows separate claims rather than one claim written twice: if reverting `inspect`
 * also killed `ADMIT_2`, one of the two rows was not measuring anything.
 */
@Timeout(60)
class EventStoreAdmissionCliTest {

    @TempDir
    lateinit var root: Path

    private val controlRoot: Path get() = root.resolve("control").also { Files.createDirectories(it) }

    /** A path whose PARENT does not exist — the measured shape, and the one SQLite names first. */
    private val missingDb: Path get() = root.resolve("no-such-dir/events.db")

    /**
     * A valid, empty SQLite database: openable, and holding no such run.
     *
     * The control's whole subject is that "openable but empty" must NOT be refused, so the file is
     * made a real database by the real factory rather than by `touch`.
     */
    private fun emptyButValidDb(name: String): Path =
        root.resolve(name).also { dev.rubentxu.pipeline.v2.events.durable.SqliteEventStore(it.toString()).close() }

    // ------------------------------------------------------------------ the refusal

    /**
     * `inspect` against a `--db` it cannot open is a usage error with a nameable reason.
     *
     * This is the row that compiles and fails against the code as it stands: today the process
     * exits `1` and the stack trace is on stderr.
     */
    @Test
    fun ADMIT_1_inspect_refuses_a_db_it_cannot_open_with_a_reason() {
        val result = inspect(
            "--db", missingDb.toString(),
            "--control-root", controlRoot.toString(),
        )

        assertEquals(2, result.exitCode, "a path that cannot be opened is a usage error, not a failed run")
        // The contract is that the refusal NAMES the path, not that it uses one particular
        // adjective. The measured message is `no directory for db: <path>`, which says more than the
        // `db not found` this verb used to print: it names the half the operator has to create.
        // Asserting a guessed phrase here would have rewritten a better message to match a worse one.
        assertTrue(
            result.stderr.contains(missingDb.toString()),
            "the refusal must name the path the operator asked for: ${result.stderr}",
        )
        assertFalse(
            result.stderr.contains("Exception in thread"),
            "a Java stack trace is not an operator-facing answer:\n${result.stderr}",
        )
    }

    /** The same condition on the other verb that leaks it, which is why it needs its own row. */
    @Test
    fun ADMIT_2_observe_refuses_a_db_it_cannot_open_with_a_reason() {
        val result = observe("--db", missingDb.toString())

        assertEquals(2, result.exitCode, "a path that cannot be opened is a usage error, not a failed run")
        assertFalse(
            result.stderr.contains("Exception in thread"),
            "a Java stack trace is not an operator-facing answer:\n${result.stderr}",
        )
    }

    // ------------------------------------------------------------------ the control

    /**
     * A real database holding no such run is NOT a refusal: an empty table and exit `0`.
     *
     * Measured before the fix: `pipelinek inspect some-run --db <empty.db> --control-root <dir>`
     * prints the header and no rows, exit `0`. A refusal that also caught this would tell an
     * operator their database is broken when it is merely empty.
     */
    @Test
    fun ADMIT_3_an_openable_database_with_no_such_run_is_not_refused() {
        val result = inspect(
            "--db", emptyButValidDb("empty.db").toString(),
            "--control-root", controlRoot.toString(),
        )

        assertEquals(0, result.exitCode, "an openable store is not a refusal: ${result.stderr}")
        assertTrue(
            result.stdout.contains("inspect run="),
            "the document is still produced, with no rows: ${result.stdout}",
        )
    }

    // ---- fixture -------------------------------------------------------------------------------

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun inspect(vararg extra: String): CliResult = capture { MainInspectCli.main(arrayOf("some-run", *extra)) }

    private fun observe(vararg extra: String): CliResult = capture { MainObserveCli.main(arrayOf("some-run", *extra)) }

    /**
     * Runs a verb's `main` with the real streams, so the exit code under test is the one the
     * process would return.
     */
    private fun capture(body: () -> Int): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    body()
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }
}