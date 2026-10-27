package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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
 * The guarantee this file measures is EQUALITY: the same mistake, given to
 * several verbs, is refused with the same words by all of them.
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
 * The four verbs that read a run are compared WITH EACH OTHER, because that
 * comparison IS the guarantee. A hand-written fragment alone was not enough,
 * and this file learned that the hard way: a first form of these rows
 * asserted that each verb's stderr CONTAINED `missing required argument: run
 * id`, and a verb that said `missing required argument: run identifier`
 * satisfied it, because the fragment is a PREFIX of the message. That mutant
 * survived. Comparing the messages with each other closes the hole, because a
 * noun that only one verb says is a divergence, not a wording.
 *
 * ## What is frozen, and what is not
 *
 * The shared sentence IS frozen for the mistakes that every verb can receive
 * (an unknown option, a missing value, a missing run id, a missing `--db`):
 * those four rows compare the four messages to each other AND to the exact
 * sentence this file wrote. A change to the shared vocabulary has to change a
 * string this file wrote by hand to be caught, and that is the point.
 *
 * The mistakes a verb alone can receive are frozen only in their HEAD: the
 * head is where the operator's way out lives (which option, which verb owns
 * it, which combination is impossible), and the tail is the verb's own advice
 * about its own vocabulary, which legitimately differs.
 *
 * ## What this does not freeze
 *
 * The hint a refusal prints is a separate line and is not compared: two verbs
 * that refuse the same missing `--db` give different advice about it, and
 * that difference is correct.
 *
 * ## What changed, and what the seam now covers
 *
 * The verbs that read a run used to refuse with a string of their own and
 * with a literal `2`: `runs cancel`, `runs recover`, `inspect` and
 * `saga inspect` each wrote its own sentence, and the same mistake got a
 * different sentence depending on the verb that received it. Measured on the
 * built distribution: «unknown option 'extra'», «unknown option: extra»,
 * «unexpected positional argument 'extra'». Their parsers now produce a
 * [CliError] and their code comes from the taxonomy, so the rows below cross
 * the seam for them too.
 *
 * The code's contribution is STRUCTURAL, not numeric: the number was `2`
 * before the cut and it is `2` after it. What changed is that the verb no
 * longer writes the number, and that the sentence is the shared one. A row
 * that only checked the code would have been green before the cut and would
 * have certified nothing.
 *
 * ## What this instrument cannot see
 *
 * The refusals about the WORLD are not here: a store that cannot be opened, a
 * cancel the run itself refuses, a saga inspection that cannot read the
 * durable promises. Those are facts about the world, they carry their own
 * ADTs and their own renderers, and they are not a request the CLI failed to
 * understand.
 *
 * The door of the `saga` family is observed at the door and not at the
 * terminal: it answers through `System.exit`, so calling it in this process
 * would end the process. The door of the `runs` family answers through its
 * return value and is observed whole.
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
    fun `the refusal of a second positional says the command takes one`() {
        val exit = CliError.TooManyPositionals("extra-arg").toCliExit()

        assertTrue(exit.message.contains("extra-arg"), exit.message)
        val hint = requireNotNull(exit.hint) { "the refusal of a second positional has no hint" }
        assertTrue(hint.contains("one positional argument"), hint)
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

    @Test
    fun `an option nobody wrote is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "unknown option: --bogus-flag",
            "runs cancel" to capture { MainRunsCli.main(arrayOf("cancel", "run-1", "--bogus-flag")) },
            "runs recover" to capture { MainRunsCli.main(arrayOf("recover", "run-1", "--bogus-flag")) },
            "inspect" to capture { MainInspectCli.main(arrayOf("run-1", "--bogus-flag")) },
            "saga inspect" to capture { MainSagaInspectCli.main(arrayOf("run-1", "--bogus-flag")) },
        )
    }

    @Test
    fun `an option without its value is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "option --db requires a value",
            "runs cancel" to capture { MainRunsCli.main(arrayOf("cancel", "run-1", "--db")) },
            "runs recover" to capture { MainRunsCli.main(arrayOf("recover", "run-1", "--db")) },
            "inspect" to capture { MainInspectCli.main(arrayOf("run-1", "--db")) },
            "saga inspect" to capture { MainSagaInspectCli.main(arrayOf("run-1", "--db")) },
        )
    }

    @Test
    fun `a run nobody named is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "missing required argument: run id",
            "runs cancel" to capture { MainRunsCli.main(arrayOf("cancel")) },
            "runs recover" to capture { MainRunsCli.main(arrayOf("recover")) },
            "inspect" to capture { MainInspectCli.main(arrayOf()) },
            "saga inspect" to capture { MainSagaInspectCli.main(arrayOf()) },
        )
    }

    @Test
    fun `a required option nobody wrote is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "missing required argument: --db <path>",
            "runs cancel" to capture { MainRunsCli.main(arrayOf("cancel", "run-1")) },
            "runs recover" to capture { MainRunsCli.main(arrayOf("recover", "run-1")) },
            "inspect" to capture { MainInspectCli.main(arrayOf("run-1")) },
            "saga inspect" to capture { MainSagaInspectCli.main(arrayOf("run-1")) },
        )
    }

    @Test
    fun `a value the verb cannot use is refused with the shared sentence`() {
        assertRefusedWithReason("inspect", capture { MainInspectCli.main(arrayOf("run-1", "--step", "abc")) }, "invalid value for --step")
        assertRefusedWithReason("saga inspect", capture { MainSagaInspectCli.main(arrayOf("run-1", "--format", "yaml")) }, "invalid value for --format")
    }

    @Test
    fun `a combination with no honest answer is refused with the shared sentence`() {
        assertRefusedWithReason("inspect", capture { MainInspectCli.main(arrayOf("run-1", "--state", "--profile")) }, "invalid combination:")
    }

    @Test
    fun `an option another verb owns is refused with the shared sentence`() {
        assertRefusedWithReason(
            "runs cancel",
            capture { MainRunsCli.main(arrayOf("cancel", "run-1", "--db", "/tmp/run.db", "--dry-run")) },
            "option --dry-run belongs to 'runs recover'",
        )
        assertRefusedWithReason(
            "runs recover",
            capture { MainRunsCli.main(arrayOf("recover", "run-1", "--db", "/tmp/run.db", "--reason", "because")) },
            "option --reason belongs to 'runs cancel'",
        )
    }

    @Test
    fun `the runs family refuses a word that names no verb with the shared sentence`() {
        assertRefused("runs", capture { MainRunsCli.main(arrayOf()) }, "missing required argument: subcommand")
        assertRefused("runs", capture { MainRunsCli.main(arrayOf("bogus")) }, "unknown subcommand: bogus")
    }

    @Test
    fun `the saga family door refuses with the shared sentence`() {
        // The door answers through System.exit, so it is observed where it is
        // produced: at the door itself. The rendering of it is the renderer's,
        // which the rows above already cross.
        val alone = requireNotNull(cliFamilyDoor(arrayOf("saga"))) { "the saga family has no door" }
        val unknown = requireNotNull(cliFamilyDoor(arrayOf("saga", "bogus"))) { "the saga family has no door" }

        assertEquals(2, alone.error.toCliExit().code)
        assertEquals("missing required argument: subcommand", alone.error.toCliExit().message)
        assertEquals(2, unknown.error.toCliExit().code)
        assertEquals("unknown subcommand: bogus", unknown.error.toCliExit().message)
    }

    /**
     * The same refusal, said the same way by every verb that received it.
     *
     * The guarantee is equality between the verbs, so the verbs are compared
     * with EACH OTHER and with the sentence this file wrote. The code is
     * checked too, because a verb that refuses with a code of its own is the
     * defect this file exists to catch — but the code alone would not have
     * caught it: the number was `2` before the cut and it is `2` after it.
     * What the sentence adds is the part that was actually wrong.
     */
    private fun assertSameRefusal(expected: String, vararg cases: Pair<String, Result>) {
        val messages = cases.map { (verb, result) ->
            assertEquals(2, result.exitCode, "$verb did not answer with the taxonomy's code")
            messageOf(verb, result)
        }
        val distinct = messages.distinct()
        assertEquals(
            1,
            distinct.size,
            "the verbs did not say the same thing:\n" +
                cases.map { it.first }.zip(messages).joinToString("\n") { (verb, message) -> "  $verb: $message" },
        )
        assertEquals(expected, distinct.single(), "the shared sentence is not the one this file wrote")
    }

    /**
     * A refusal whose whole message is the shared one.
     */
    private fun assertRefused(verb: String, result: Result, expected: String) {
        assertEquals(2, result.exitCode, "$verb did not answer with the taxonomy's code")
        assertEquals(expected, messageOf(verb, result), "$verb did not say the shared sentence")
    }

    /**
     * A refusal whose HEAD is the shared one and whose tail is the verb's own
     * advice about its own vocabulary.
     *
     * The head is where the operator's way out lives: which option was wrong,
     * which verb owns it, which combination is impossible. The tail says what
     * that verb accepts, and two verbs accept different things.
     */
    private fun assertRefusedWithReason(verb: String, result: Result, expectedHead: String) {
        assertEquals(2, result.exitCode, "$verb did not answer with the taxonomy's code")
        val message = messageOf(verb, result)
        assertTrue(message.startsWith(expectedHead), "$verb did not say the shared sentence; it said:\n$message")
    }

    /**
     * The message a verb refused with, without the verb's name in front of it.
     *
     * The refusal is printed as "<verb>: <message>" and, when there is one,
     * the hint follows on a line of its own. The message is therefore the
     * first line the verb named itself in.
     */
    private fun messageOf(verb: String, result: Result): String {
        val line = result.stderr.lines().firstOrNull { it.startsWith("$verb: ") }
            ?: fail<String>("$verb printed no refusal line; it printed:\n${result.stderr}")
        return line.removePrefix("$verb: ")
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
