package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Where a read verb reads the run id it owns.
 *
 * ## The guarantee
 *
 * A verb that owns the run id reads it from WHATEVER position the operator
 * wrote it in. `inspect --db X run-1` and `inspect run-1 --db X` are the same
 * request, and the verb answers one of them the way it answers the other.
 *
 * This is not a new rule invented here: `events`, `console` and `operations`
 * already read the run id in any position, and the CLI's own parsers
 * ([CliEventsParser.parse], [CliConsoleParser.parse]) collect it as the first
 * positional wherever it appears. The four verbs this file exercises read it
 * from `args[0]` instead, so a request that wrote the run id AFTER an option
 * was answered "a run id is required" — a claim about an argument the operator
 * had just supplied, and a refusal that names a thing that is present.
 *
 * ## The capacity this serves
 *
 * An operator who writes the run id in the order that reads naturally to them
 * gets an answer about that run. Before this, `observe --view console run-1`
 * said the run id was missing, and the operator's next move was to look for a
 * typo in a run id they had just typed correctly.
 *
 * ## Why this instrument
 *
 * The cheapest instrument that can fail is the verb itself, called in process:
 * the verb is given the argument array, its exit code is read and its stderr is
 * captured. The refusal the operator would read IS the observable, so there is
 * no subprocess, no filesystem and no timing in the way.
 *
 * Each row asks the SAME question of a DIFFERENT verb, because the extraction
 * is a separate site in each one: a row for `inspect` cannot catch a verb that
 * still reads `args[0]`.
 *
 * ## What each row asserts, and why all three assertions
 *
 * 1. The refusal names the NEXT thing the verb needs. The run id is not the
 *    missing thing, and the refusal says what is.
 * 2. The refusal does not name the run id — neither the phrase ("run id") nor
 *    the value ("run-1"). The first catches a verb that refuses ABOUT the run
 *    id; the second catches a parser that refuses the run id AS a positional.
 *    Either one alone leaves a hole the other closes.
 * 3. Nothing is written to stdout. A refusal that also published an answer
 *    would be read as the answer.
 *
 * ## What this does not freeze
 *
 * The wording of the refusal is not frozen. What is frozen is the NAME of the
 * thing the verb is asking for — the token the operator has to be able to read
 * — and the fact that the run id is not named as the offending thing. A verb
 * that printed its own sentence instead of the shared one would still pass,
 * and it would be a second rendering of the same fact, which is a different
 * cut.
 *
 * ## What this instrument cannot see
 *
 * It cannot see that the verb went on to USE the run id it read: every row
 * stops at the refusal that comes before the store is opened, because a unit
 * test in this tree does not touch the filesystem. That the run id read from
 * the trailing position is the one the verb acts upon is covered by the E2E
 * scenario, which writes the run id after the options in four invocations and
 * reads real runs.
 */
class RunIdPositionTest {

    @Test
    fun `inspect reads the run id it was given after an option`() {
        val result = capture { MainInspectCli.main(arrayOf("--db", "/tmp/a-store.db", "run-1")) }

        // The run id was written and it was read: the refusal is about the
        // NEXT thing this verb needs, which is the Output Plane.
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("missing required argument: --control-root"), result.stderr)
        // The run id is NOT the missing thing. Before the fix this verb said
        // "missing required argument: run id" about an argument the operator
        // had just written.
        assertFalse(result.stderr.contains("run id"), result.stderr)
        // And it is not named as an offending argument either.
        assertFalse(result.stderr.contains("run-1"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `observe reads the run id it was given after an option`() {
        val result = capture { MainObserveCli.main(arrayOf("--view", "console", "run-1")) }

        assertEquals(2, result.exitCode)
        // The plane is what this verb asks for next. Its own wording, and not
        // the usage text: the usage text names the option too, and a row that
        // could be satisfied by the usage text would pass on a verb that
        // refused everything.
        assertTrue(result.stderr.contains("Output Plane"), result.stderr)
        assertFalse(result.stderr.contains("run id"), result.stderr)
        assertFalse(result.stderr.contains("run-1"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `runs cancel reads the run id it was given after an option`() {
        val result = capture {
            MainRunsCli.main(arrayOf("cancel", "--reason", "because", "run-1"))
        }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("missing required argument: --db"), result.stderr)
        assertFalse(result.stderr.contains("run id"), result.stderr)
        assertFalse(result.stderr.contains("run-1"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `runs recover reads the run id it was given after an option`() {
        val result = capture { MainRunsCli.main(arrayOf("recover", "--dry-run", "run-1")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("missing required argument: --db"), result.stderr)
        assertFalse(result.stderr.contains("run id"), result.stderr)
        assertFalse(result.stderr.contains("run-1"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `saga inspect reads the run id it was given after an option`() {
        val result = capture { MainSagaInspectCli.main(arrayOf("--format", "json", "run-1")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("missing required argument: --db"), result.stderr)
        assertFalse(result.stderr.contains("run id"), result.stderr)
        assertFalse(result.stderr.contains("run-1"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `a verb that owns the run id refuses a second one`() {
        // The run id is the ONE positional these verbs own. A request that
        // carries two is not a request about one of them: it is a request
        // whose author believed the second one was going to be read.
        val requests = listOf(
            "inspect" to arrayOf("--db", "/tmp/a-store.db", "run-1", "run-2"),
            "observe" to arrayOf("--view", "console", "run-1", "run-2"),
            "runs cancel" to arrayOf("cancel", "--reason", "because", "run-1", "run-2"),
            "runs recover" to arrayOf("recover", "--dry-run", "run-1", "run-2"),
            "saga inspect" to arrayOf("--format", "json", "run-1", "run-2"),
        )

        for ((verb, args) in requests) {
            val result = capture {
                when (verb) {
                    "inspect" -> MainInspectCli.main(args)
                    "observe" -> MainObserveCli.main(args)
                    "runs cancel" -> MainRunsCli.main(args)
                    "runs recover" -> MainRunsCli.main(args)
                    else -> MainSagaInspectCli.main(args)
                }
            }

            assertEquals(2, result.exitCode, verb)
            assertTrue(result.stderr.contains("run-2"), "$verb: ${result.stderr}")
            // The FIRST one is not the offending token: the verb owns it. A
            // refusal that named "run-1" would say the opposite of the truth.
            assertFalse(result.stderr.contains("run-1"), "$verb: ${result.stderr}")
            assertFalse(result.stderr.contains("run id"), "$verb: ${result.stderr}")
            assertEquals("", result.stdout, verb)
        }
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private fun capture(block: () -> Int): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(err, true, Charsets.UTF_8.name()))
            val exitCode = block()
            return Result(exitCode, out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
