package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Paths

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
 * The verb is handed the streams it has to write to, so the rows read what
 * the verb said without any row touching the process-wide streams: two rows
 * can run at the same time and neither one can see the other's bytes.
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
 * terminal: it answers with a [CliError] and does not end the process, so it
 * can be observed whole here — the refusal, the code the taxonomy declares
 * for it and the sentence. The door of the `runs` family answers through its
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
    fun `the refusal of a run identity that cannot name a directory names the identity`() {
        // A run identity that cannot name a durable directory is a fact about the REQUEST, so the
        // refusal says which identity it refused: an operator who typed one of two names has to be
        // told which one was wrong.
        val exit = CliError.RunIdCannotNameADirectory("a/b").toCliExit()

        assertEquals(2, exit.code, "an unusable request is the invalid-argument case")
        assertTrue(exit.message.contains("a/b"), exit.message)
    }

    @Test
    fun `the refusal of a run identity that cannot name a directory names the accepted alphabet`() {
        // The hint is where the operator's way out lives. A refusal that says only "that name is
        // not allowed" leaves the operator to guess the alphabet; the alphabet is the answer, and
        // it is the same one the durable lease enforces.
        val exit = CliError.RunIdCannotNameADirectory("a/b").toCliExit()

        val hint = requireNotNull(exit.hint) { "the refusal of an unnameable run id has no hint" }
        assertTrue(hint.contains("'.'"), hint)
        assertTrue(hint.contains("'_'"), hint)
        assertTrue(hint.contains("'-'"), hint)
    }

    @Test
    fun `a run identity that cannot name a directory is refused the same way by every verb that reads a run`() {
        // The two verbs that ask the artifact plane about a RUN. They reach it through different
        // doors — one lists the run's operations, the other composes the run's lanes — and they
        // have to REFUSE the same identity the same way, because the refusal is about the identity
        // and not about the verb that received it.
        //
        // The refusal happens before the product touches the disk: the identity is refused where
        // the NAME is decided, and a name is decided before any path is read. These rows therefore
        // need no store, no fixture and no directory that exists.
        assertSameRefusal(
            "run id 'a/b' cannot name a durable directory",
            "operations" to capture { out, err -> dispatch(arrayOf("operations", "a/b", "--control-root", controlRoot), out, err) },
            "observe" to capture { out, err ->
                dispatch(arrayOf("observe", "a/b", "--control-root", controlRoot, "--view", "console"), out, err)
            },
        )
    }

    @Test
    fun `the refusal of an unnameable run id is a refusal and not a stack trace`() {
        // The artifact plane refuses the identity by throwing a typed refusal, and the dispatcher
        // turns it into words. A dispatcher that let the throw escape would answer the operator
        // with the shape of a type and a stack, which is the failure this row exists to catch: the
        // answer an operator reads is the one that looks like a defect.
        val result = capture { out, err -> dispatch(arrayOf("operations", "a/b", "--control-root", controlRoot), out, err) }

        assertFalse(result.stderr.contains("UnnameableRunIdentity"), result.stderr)
        assertFalse(result.stderr.contains("Exception"), result.stderr)
        assertTrue(result.stderr.contains("cannot name a durable directory"), result.stderr)
    }

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
        val result = capture { out, err -> MainObserveCli.main(arrayOf("run-1", "--bogus-flag"), out, err) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("unknown option: --bogus-flag"), result.stderr)
        assertFalse(result.stderr.contains("UnknownOption"), result.stderr)
    }

    @Test
    fun `observe names the channels this build has when a channel is wrong`() {
        val result = capture { out, err -> MainObserveCli.main(arrayOf("run-1", "--channel", "bogus"), out, err) }

        assertEquals(2, result.exitCode)
        // The usage line names the channels too, but as `stdout|stderr`. The
        // fragment asserted here is the renderer's, and only the renderer's.
        assertTrue(result.stderr.contains("stdout, stderr"), result.stderr)
    }

    @Test
    fun `an option nobody wrote is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "unknown option: --bogus-flag",
            "runs cancel" to capture { out, err -> MainRunsCli.main(arrayOf("cancel", "run-1", "--bogus-flag"), out, err) },
            "runs recover" to capture { out, err -> MainRunsCli.main(arrayOf("recover", "run-1", "--bogus-flag"), out, err) },
            "inspect" to capture { out, err -> MainInspectCli.main(arrayOf("run-1", "--bogus-flag"), out, err) },
            "saga inspect" to capture { out, err -> MainSagaInspectCli.main(arrayOf("run-1", "--bogus-flag"), out, err) },
        )
    }

    @Test
    fun `an option without its value is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "option --db requires a value",
            "runs cancel" to capture { out, err -> MainRunsCli.main(arrayOf("cancel", "run-1", "--db"), out, err) },
            "runs recover" to capture { out, err -> MainRunsCli.main(arrayOf("recover", "run-1", "--db"), out, err) },
            "inspect" to capture { out, err -> MainInspectCli.main(arrayOf("run-1", "--db"), out, err) },
            "saga inspect" to capture { out, err -> MainSagaInspectCli.main(arrayOf("run-1", "--db"), out, err) },
        )
    }

    @Test
    fun `a run nobody named is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "missing required argument: run id",
            "runs cancel" to capture { out, err -> MainRunsCli.main(arrayOf("cancel"), out, err) },
            "runs recover" to capture { out, err -> MainRunsCli.main(arrayOf("recover"), out, err) },
            "inspect" to capture { out, err -> MainInspectCli.main(arrayOf(), out, err) },
            "saga inspect" to capture { out, err -> MainSagaInspectCli.main(arrayOf(), out, err) },
        )
    }

    @Test
    fun `a required option nobody wrote is refused the same way by every verb that reads a run`() {
        assertSameRefusal(
            "missing required argument: --db <path>",
            "runs cancel" to capture { out, err -> MainRunsCli.main(arrayOf("cancel", "run-1"), out, err) },
            "runs recover" to capture { out, err -> MainRunsCli.main(arrayOf("recover", "run-1"), out, err) },
            "inspect" to capture { out, err -> MainInspectCli.main(arrayOf("run-1"), out, err) },
            "saga inspect" to capture { out, err -> MainSagaInspectCli.main(arrayOf("run-1"), out, err) },
        )
    }

    @Test
    fun `a value the verb cannot use is refused with the shared sentence`() {
        assertRefusedWithReason(
            "inspect",
            capture { out, err -> MainInspectCli.main(arrayOf("run-1", "--step", "abc"), out, err) },
            "invalid value for --step",
        )
        assertRefusedWithReason(
            "saga inspect",
            capture { out, err -> MainSagaInspectCli.main(arrayOf("run-1", "--format", "yaml"), out, err) },
            "invalid value for --format",
        )
    }

    @Test
    fun `a combination with no honest answer is refused with the shared sentence`() {
        assertRefusedWithReason(
            "inspect",
            capture { out, err -> MainInspectCli.main(arrayOf("run-1", "--state", "--profile"), out, err) },
            "invalid combination:",
        )
    }

    @Test
    fun `an option another verb owns is refused with the shared sentence`() {
        assertRefusedWithReason(
            "runs cancel",
            capture { out, err -> MainRunsCli.main(arrayOf("cancel", "run-1", "--db", "/tmp/run.db", "--dry-run"), out, err) },
            "option --dry-run belongs to 'runs recover'",
        )
        assertRefusedWithReason(
            "runs recover",
            capture { out, err -> MainRunsCli.main(arrayOf("recover", "run-1", "--db", "/tmp/run.db", "--reason", "because"), out, err) },
            "option --reason belongs to 'runs cancel'",
        )
    }

    @Test
    fun `the runs family refuses a word that names no verb with the shared sentence`() {
        assertRefused("runs", capture { out, err -> MainRunsCli.main(arrayOf(), out, err) }, "missing required argument: subcommand")
        assertRefused("runs", capture { out, err -> MainRunsCli.main(arrayOf("bogus"), out, err) }, "unknown subcommand: bogus")
    }

    @Test
    fun `the saga family door refuses with the shared sentence`() {
        // The door answers with a decision and does not end the process, so it
        // is observed where it is produced: at the door itself, whole. The
        // rendering of it is the renderer's, which the rows above already
        // cross.
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

    /**
     * The code the read-only dispatcher answered with.
     *
     * The dispatcher is the door every read-only verb goes through, and it is where a refusal the
     * artifact plane raised is turned into a decision. Calling it — rather than the verbs one by
     * one — is what makes the comparison between the verbs a comparison of the SAME refusal
     * answered by two doors.
     */
    private fun dispatch(args: Array<String>, out: PrintStream, err: PrintStream): Int =
        when (val outcome = handleReadOnlyVerb(args, out, err)) {
            is ReadOnlyVerbOutcome.Answered -> outcome.exit.code
            ReadOnlyVerbOutcome.NotHandled -> fail("no verb answered ${args.toList()}")
        }

    /**
     * A control root that does not exist, and is never created.
     *
     * The identity is refused where the name is decided, which is before any path is read, so
     * nothing here is ever opened. The path is named anyway, so that the rows say WHERE the verb
     * was pointed instead of leaving it to a reader to wonder.
     */
    private val controlRoot: String =
        Paths.get(System.getProperty("java.io.tmpdir"), "pipelinek-unnameable-run").toString()

    /**
     * Run [block] with two streams of its own, and give back what it answered and what it said.
     *
     * The streams are PARAMETERS the block receives, not globals this test replaces. A test that
     * swapped the process-wide streams would mutate the state every other row shares, and two
     * rows running at the same time would read each other's bytes. The bytes here are the ones
     * the CLI shows, because the code under test is the code the CLI runs — only the sink differs.
     */
    private fun capture(block: (PrintStream, PrintStream) -> Int): Result {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())
        val exitCode = block(out, err)
        out.flush()
        err.flush()
        return Result(exitCode, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }
}
