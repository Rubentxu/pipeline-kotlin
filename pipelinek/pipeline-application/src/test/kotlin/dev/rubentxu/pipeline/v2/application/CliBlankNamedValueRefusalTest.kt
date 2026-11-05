package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * What a refusal says when the value that NAMES something is blank.
 *
 * ## The guarantee
 *
 * A value that names a location or an identity is a REQUEST for that thing. A
 * blank value is not "no request": the filesystem resolves an empty path to the
 * working directory, the storage engine reads an empty name as a temporary
 * database, and a store reads an empty run id as a run nobody has heard of.
 * The product then answers the request it RESOLVED, and the operator reads a
 * path or an identity they never wrote — or reads nothing at all, with a green
 * exit.
 *
 * The guarantee is that the REQUEST is refused instead: the refusal names the
 * value, and it says the value is blank.
 *
 * ## The capacity this serves
 *
 * An operator who wrote `--db "$DB"` with the variable unset has to learn what
 * to write instead. A refusal that names the working directory tells them the
 * directory is missing; a refusal that names their own empty variable tells
 * them the variable is empty. And a verb that answers 0 with no output at all
 * tells them nothing at all — the operator cannot tell a request about no run
 * from a request about the run they meant.
 *
 * ## Why this instrument
 *
 * The observable is what the operator READS, and it is produced by pure
 * functions: the parsers decide, and the ONE mapping turns the decision into
 * words. Calling a verb's own entry point with its two streams is therefore
 * the cheapest instrument that can fail, and it touches nothing: every one of
 * these refusals is decided before a store, a journal, a process or a file is
 * opened.
 *
 * Each case goes through the verb that OWNS the value. A rule that only the
 * reader knew would be a rule the verb does not use, and a rule nobody reads
 * is not a rule.
 *
 * ## Why BLANK and not EMPTY
 *
 * A value of only spaces is blank too, and the filesystem resolves it to a
 * directory whose name is made of spaces — a path the operator cannot have
 * meant. Both shapes are exercised here, because a rule that only knows the
 * empty string is a rule that leaves the spaces behind.
 *
 * ## What this does not freeze
 *
 * The wording of each refusal, and which of them exist. It freezes the FACT
 * that a value naming something is refused when it is blank, and the sentences
 * that were already published for the mistakes that were already refused.
 */
class CliBlankNamedValueRefusalTest {

    @Test
    fun `a path-naming option refuses a blank value by its name`() {
        for ((option, refusal) in pathNamingRefusals) {
            for (value in blankValues) {
                val text = refusal(value)
                assertTrue(
                    text.contains(option),
                    "the refusal does not name the option $option: $text",
                )
                assertTrue(
                    text.contains("must not be blank"),
                    "the refusal does not say the value is blank: $text",
                )
            }
        }
    }

    @Test
    fun `a blank script path is refused by its name`() {
        for (verb in listOf("run", "validate")) {
            for (value in blankValues) {
                val refusal = executionRefusal(verb, value)
                assertTrue(
                    refusal.contains("script path"),
                    "the refusal does not name the script path: $refusal",
                )
                assertTrue(
                    refusal.contains("must not be blank"),
                    "the refusal does not say the value is blank: $refusal",
                )
            }
        }
    }

    @Test
    fun `a run id that names nothing is refused by its name`() {
        for ((verb, refusal) in runIdRefusals) {
            for (value in blankValues) {
                val text = refusal(value)
                assertTrue(
                    text.contains("run id"),
                    "the refusal of $verb does not name the run id: $text",
                )
                assertTrue(
                    text.contains("must not be blank"),
                    "the refusal of $verb does not say the value is blank: $text",
                )
            }
        }
    }

    @Test
    fun `a value with visible characters is still accepted`() {
        // The rule fills a hole and never refuses a value the operator can see:
        // a path that names something, and a run id that names a run, are the
        // requests the CLI exists to serve.
        assertTrue(
            readOptionValue("--db", arrayOf("--db", "/tmp/store.db"), 1) is OptionValueOutcome.Present,
            "a path with visible characters was refused",
        )
        assertTrue(
            CliExecutionParser.parse(arrayOf("run", "script.pipeline.kts")) is CliParseResult.Parsed,
            "a script path with visible characters was refused",
        )
        assertTrue(
            CliEventsParser.parse(arrayOf("run-1", "--db", "/tmp/store.db")) is EventsCliParseResult.Parsed,
            "a run id with visible characters was refused",
        )
    }

    @Test
    fun `a refusal that was already published keeps the sentence it had`() {
        // The refusals that were already correct are not rewritten: the runs
        // family said "must not be blank" about its store path before the rule
        // reached it, and the console says "runId must not be blank" about its
        // run id. A rule that rewrote a sentence that was already right would
        // be a rule that spends the operator's attention for nothing.
        val runs = stderr { out, err -> MainRunsCli.main(arrayOf("cancel", "--db", "", "run-1"), out, err) }
        assertTrue(
            runs.contains("runs cancel: invalid value for --db: must not be blank"),
            "the runs family changed the sentence it published about a blank store path: $runs",
        )
        val console = stderr { out, err ->
            MainConsoleCli.main(arrayOf("--control-dir", "/tmp/ctl", "--run", "", "op-1"), out, err)
        }
        assertTrue(
            console.contains("Error: runId must not be blank"),
            "the console changed the sentence it published about a blank run id: $console",
        )
    }

    /**
     * The values that name nothing.
     *
     * The empty string and the value of only spaces: both are blank, and the
     * filesystem resolves the second one to a directory whose name is made of
     * spaces — a path the operator cannot have meant.
     */
    private val blankValues: List<String> = listOf("", "   ")

    /**
     * The options that name a location, and the refusal each one answers with.
     *
     * One case per option, because the variations are the same KIND: an option
     * whose value is a path, given a blank value. Each case goes through the
     * verb that OWNS the option, because a reader that refused a value the
     * verb does not use would be a reader nobody reads.
     */
    private val pathNamingRefusals: List<Pair<String, (String) -> String>> = listOf(
        "--db" to { value ->
            stderr { out, err -> MainEventsCli.main(arrayOf("run-1", "--db", value), out, err) }
        },
        "--control-root" to { value ->
            stderr { out, err ->
                MainInspectCli.main(arrayOf("run-1", "--db", "/tmp/store.db", "--control-root", value), out, err)
            }
        },
        "--control-dir" to { value ->
            stderr { out, err -> MainConsoleCli.main(arrayOf("--control-dir", value, "run-1", "op-1"), out, err) }
        },
        "--workspace" to { value -> executionRefusal("run", "--workspace", value, "script.pipeline.kts") },
        "--plugin-jar" to { value -> executionRefusal("run", "--plugin-jar", value, "script.pipeline.kts") },
        "--plugin-trust" to { value -> executionRefusal("run", "--plugin-trust", value, "script.pipeline.kts") },
    )

    /**
     * The verbs that own a run id, and the refusal each one answers with.
     *
     * The run id is the ONE positional argument these verbs own, and each one
     * reads it in its own parser, so each one refuses it in its own boundary.
     */
    private val runIdRefusals: List<Pair<String, (String) -> String>> = listOf(
        "observe" to { value -> stderr { out, err -> MainObserveCli.main(arrayOf(value), out, err) } },
        "inspect" to { value -> stderr { out, err -> MainInspectCli.main(arrayOf(value), out, err) } },
        "saga inspect" to { value -> stderr { out, err -> MainSagaInspectCli.main(arrayOf(value), out, err) } },
        "events" to { value ->
            stderr { out, err -> MainEventsCli.main(arrayOf(value, "--db", "/tmp/store.db"), out, err) }
        },
        "runs cancel" to { value -> stderr { out, err -> MainRunsCli.main(arrayOf("cancel", value), out, err) } },
        "runs recover" to { value -> stderr { out, err -> MainRunsCli.main(arrayOf("recover", value), out, err) } },
    )

    /** A verb, called with the two streams it writes to. */
    private fun interface Verb {
        fun call(out: PrintStream, err: PrintStream): Int
    }

    /** What the verb wrote to its error stream. */
    private fun stderr(verb: Verb): String {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        verb.call(PrintStream(out), PrintStream(err))
        return err.toString()
    }

    /**
     * The sentence the execution verbs answer with, for a request they refuse.
     *
     * `run` and `validate` are the two verbs whose entry point ends the
     * PROCESS, so the row reads the decision the parser made and renders it
     * through the ONE mapping — the same words the operator reads.
     */
    private fun executionRefusal(verb: String, vararg rest: String): String =
        when (val parsed = CliExecutionParser.parse(arrayOf(verb, *rest))) {
            is CliParseResult.Rejected -> parsed.error.toCliExit().message
            is CliParseResult.Parsed -> error("the request was not refused")
        }
}
