package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The saga read-side at the edge: what the verb does with the arguments it is given, and
 * what it says when it cannot answer.
 *
 * The verb exists for a person who has a run and a question, so the two things worth
 * protecting are the shape of the answer and the honesty of a refusal. A refusal that
 * prints an empty table is the worst outcome available: it reads as "nothing is owed" and
 * sends the person looking for the wrong thing.
 */
class MainSagaInspectCliTest {

    @Test
    fun `a run id is required`() {
        val result = run()

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("missing required argument: run id"), result.stderr)
    }

    @Test
    fun `the durable store is required`() {
        val result = run("run-42")

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--db"), result.stderr)
    }

    @Test
    fun `an unknown option is refused`() {
        val result = run("run-42", "--db", "/tmp/run.sqlite", "--colour")

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("unknown option"), result.stderr)
    }

    @Test
    fun `an unknown output format is refused`() {
        val result = run("run-42", "--db", "/tmp/run.sqlite", "--format", "yaml")

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("invalid value for --format"), result.stderr)
        assertTrue(result.stderr.contains("text, json"), result.stderr)
    }

    @Test
    fun `a store that is not there is refused with its reason and prints no list`() {
        val result = run("run-42", "--db", "/tmp/a-store-that-does-not-exist.sqlite")

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("there is no database at"), result.stderr)
        // The dangerous shape: a refusal that still prints a table of nothing. An operator
        // would read it as a run that owes nothing.
        assertEquals("", result.stdout)
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Run the verb with the arguments it is given, and give back what it answered and what it said.
     *
     * The streams are PARAMETERS the verb is handed, not globals this test replaces. A test that
     * swapped the process-wide streams would mutate the state every other row shares, and two
     * rows running at the same time would read each other's bytes. The bytes here are the ones
     * the CLI shows, because the code under test is the code the CLI runs — only the sink differs.
     */
    private fun run(vararg args: String): Result {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())
        val argv = Array(args.size) { args[it] }
        val exitCode = MainSagaInspectCli.main(argv, out, err)
        out.flush()
        err.flush()
        return Result(exitCode, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }
}
