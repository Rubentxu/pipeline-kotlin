package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The words a refusal is made of.
 *
 * ## The guarantee
 *
 * A refusal says ONE thing, and the thing names what was wrong. There is one
 * renderer — [toCliExit] — and the typed error carries no words of its own, so
 * a verb cannot answer the same mistake with a second, different sentence.
 *
 * ## The capacity this serves
 *
 * An operator who mistypes a flag has to learn two things: that the flag was
 * refused, and what the accepted values are. Before this, the same mistake
 * produced two different sentences depending on the verb: `observe` printed
 * `UnknownOption(value=--bogus-flag)`, and `run` printed `invalid view: bogus`
 * with no list of the views that exist.
 *
 * ## Why this instrument
 *
 * [toCliExit] is pure, so the cheapest instrument that can fail is to call it
 * and read what it says. The seam — that a verb renders its refusal through
 * it, rather than through the error's own shape — is crossed by calling the
 * verb in this process and reading its stderr.
 *
 * The expectations are literals written here. The only thing read from
 * production is the vocabulary the refusal names, and it is read as TEXT: a
 * change to that vocabulary has to change a string this file wrote by hand to
 * be caught.
 *
 * ## What this does not freeze
 *
 * The exact sentence is not frozen, only the fragments that carry the
 * operator's way out: the offending token, the accepted vocabulary, and the
 * advice. A renderer that said the same thing in other words would still pass.
 *
 * ## What this instrument cannot see
 *
 * The verbs that refuse with a string of their own — `runs cancel`,
 * `runs recover`, `inspect`, `saga inspect` — do not produce a [CliError] and
 * are not here. Joining them would mean making their parsers produce this
 * type, which is another cut.
 *
 * The two phrases this file refuses to accept are the two the old hint
 * promised: `prefix with '='` and `or quote it`. Neither form works in this
 * CLI, so neither may be offered. A DIFFERENT false promise would not be
 * caught by these two literals, and that limit is stated here rather than
 * hidden.
 */
class CliErrorRenderingTest {

    @Test
    fun `the refusal of an unknown option names the option`() {
        val exit = CliError.UnknownOption("--bogus-flag").toCliExit()

        assertTrue(exit.message.contains("--bogus-flag"), exit.message)
        assertFalse(exit.message.contains("UnknownOption"), exit.message)
    }

    @Test
    fun `the refusal of an invalid view names the views this build has`() {
        val exit = CliError.InvalidView("bogus").toCliExit()

        assertTrue(exit.message.contains("bogus"), exit.message)
        val hint = requireNotNull(exit.hint) { "the refusal of an invalid view has no hint" }
        assertTrue(hint.contains("console"), hint)
    }

    @Test
    fun `the refusal of an invalid format names the formats this build has`() {
        val exit = CliError.InvalidFormat("bogus").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of an invalid format has no hint" }
        assertTrue(hint.contains("jsonl"), hint)
    }

    @Test
    fun `the refusal of an invalid channel names the channels this build has`() {
        val exit = CliError.InvalidChannel("bogus").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of an invalid channel has no hint" }
        assertTrue(hint.contains("stdout, stderr"), hint)
    }

    @Test
    fun `the refusal of an invalid outcome names the outcomes this build has`() {
        val exit = CliError.InvalidOutcome("bogus").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of an invalid outcome has no hint" }
        assertTrue(hint.contains("success"), hint)
    }

    @Test
    fun `the refusal of an invalid limit says what to do instead`() {
        val exit = CliError.InvalidLimit("abc").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of an invalid limit has no hint" }
        assertTrue(hint.contains("everything"), hint)
    }

    @Test
    fun `the refusal of a trailing option says the options come first`() {
        val exit = CliError.TrailingOption("--format").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of a trailing option has no hint" }
        assertTrue(hint.contains("before the script path"), hint)
    }

    @Test
    fun `the refusal of an option another verb owns names the verb that owns it`() {
        val exit = CliError.OptionBelongsToObserve("--limit").toCliExit()

        assertTrue(exit.message.contains("observe"), exit.message)
    }

    @Test
    fun `the refusal of a flag that changes what a run does says the command only reads`() {
        val exit = CliError.OptionNotReadable("--resume").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of an unreadable option has no hint" }
        assertTrue(hint.contains("only reads"), hint)
    }

    @Test
    fun `the refusal of a value that looks like an option does not promise a form the CLI refuses`() {
        val exit = CliError.OptionValueIsAnOption("--control-root", "--format").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of a value that looks like an option has no hint" }
        assertFalse(hint.contains("prefix with '='"), hint)
        assertFalse(hint.contains("or quote it"), hint)
    }

    @Test
    fun `observe refuses an option it does not own with the shared sentence`() {
        val result = capture { MainObserveCli.main(arrayOf("run-1", "--bogus-flag")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("unknown option: --bogus-flag"), result.stderr)
        assertFalse(result.stderr.contains("UnknownOption"), result.stderr)
    }

    @Test
    fun `observe names the channels this build has when a channel is wrong`() {
        val result = capture { MainObserveCli.main(arrayOf("run-1", "--channel", "bogus")) }

        assertEquals(2, result.exitCode)
        // The usage line names the channels too, but as `stdout|stderr`. The
        // fragment asserted here is the renderer's, and only the renderer's.
        assertTrue(result.stderr.contains("stdout, stderr"), result.stderr)
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
