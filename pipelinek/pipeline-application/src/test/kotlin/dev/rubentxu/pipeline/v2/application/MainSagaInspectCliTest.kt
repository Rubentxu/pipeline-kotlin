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
        assertTrue(result.stderr.contains("a run id is required"), result.stderr)
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
        assertTrue(result.stderr.contains("--format accepts text or json"), result.stderr)
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

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(err, true, Charsets.UTF_8.name()))
            val argv = Array(args.size) { args[it] }
            val exitCode = MainSagaInspectCli.main(argv)
            return Result(exitCode, out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
