package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * A valued option consumes ONE argument, and WHICH argument it may consume is
 * decided once, in one owner, for every verb.
 *
 * **The guarantee.** A valued option refuses a value that is not there, and
 * refuses a value spelled like an option of this build — in every verb, with
 * the same words. The CLI owns one table of what an option MEANS and one place
 * that decides which argument a valued option may consume.
 *
 * **The capacity this serves.** An operator who writes `--db --control-root
 * /tmp/ctl` is told that `--db` consumed an option, instead of the CLI
 * answering about a run nobody asked about.
 *
 * **Why this instrument.** The guarantee is about a decision, and every
 * decision here is a PURE function over an array of arguments: no filesystem,
 * no clock, no process, no journal, no run. The cheapest instrument that can
 * fail is to call the parser and read the typed result — no CLI process, no
 * pipe, no temporary directory.
 *
 * The expectations are LITERAL: each row names the exact refusal, spelled out
 * here rather than computed from the table under test. A row that computed the
 * expectation from the table would agree with any table, including a broken
 * one.
 *
 * **What this does not freeze.** The words a verb prints on stderr, the exit
 * code a refusal produces, and the order in which the verbs are listed here.
 * It freezes the DECISION each verb makes about the value of a valued option,
 * and the shape of the refusal it carries.
 */
class CliOptionValueReadingTest {

    @Test
    fun `a valued option whose value is not there is refused`() {
        assertEquals(
            CliError.MissingOptionValue("--db"),
            readOptionValue("--db", arrayOf("--db"), 1).refusal(),
        )
    }

    @Test
    fun `a value spelled like an option of this build is refused`() {
        assertEquals(
            CliError.OptionValueIsAnOption("--db", "--control-root"),
            readOptionValue("--db", arrayOf("--db", "--control-root"), 1).refusal(),
        )
    }

    @Test
    fun `a value spelled like an option the calling verb owns is refused`() {
        // "--subject" is the events verb's own token and not one of this build's, so
        // only the verb's own set can refuse it.
        assertEquals(
            CliError.OptionValueIsAnOption("--db", "--subject"),
            readOptionValue("--db", arrayOf("--db", "--subject"), 1, setOf("--subject")).refusal(),
        )
    }

    @Test
    fun `a value that names nothing is accepted`() {
        // The refusal is scoped to the options THIS build knows: a value that names
        // nothing is far more likely to be a path than a mistyped flag, and refusing
        // every value that begins with "--" would make such a path unexpressible.
        assertEquals(
            OptionValueOutcome.Present("--verbose-stop"),
            readOptionValue("--grep", arrayOf("--grep", "--verbose-stop"), 1),
        )
    }

    @Test
    fun `every verb refuses a value spelled like an option the same way`() {
        val rows = listOf(
            "events" to eventsRefusal(),
            "inspect" to inspectRefusal(),
            "runs cancel" to runsCancelRefusal(),
            "saga inspect" to sagaRefusal(),
            "console" to consoleRefusal(),
        )
        for ((verb, sentence) in rows) {
            assertEquals(
                SHARED_SENTENCE,
                sentence,
                "the $verb verb words the refusal its own way",
            )
        }
    }

    @Test
    fun `every verb carries the refusal as a typed error`() {
        assertEquals(
            CliError.OptionValueIsAnOption("--db", "--control-root"),
            inspectError(),
        )
        assertEquals(
            CliError.OptionValueIsAnOption("--db", "--control-root"),
            runsCancelError(),
        )
        assertEquals(
            CliError.OptionValueIsAnOption("--db", "--control-root"),
            sagaError(),
        )
        assertEquals(
            ConsoleCliRefusal.SharedRefusal(SHARED_SENTENCE),
            consoleRefusalValue(),
        )
    }

    @Test
    fun `every verb still accepts a real value`() {
        // The other half of the partition: a refusal that is too broad is a defect
        // too, and it would answer about a run nobody asked about in the other
        // direction — refusing a path that was written plainly.
        assertTrue(eventsAdmitted(), "the events verb refused a real path")
        assertTrue(inspectAdmitted(), "the inspect verb refused a real path")
        assertTrue(runsCancelAdmitted(), "the runs verb refused a real path")
        assertTrue(sagaAdmitted(), "the saga verb refused a real path")
        assertTrue(consoleAdmitted(), "the console verb refused a real path")
    }

    private fun eventsRefusal(): String = when (val r = CliEventsParser.parse(EVENTS_ARGS)) {
        is EventsCliParseResult.Rejected -> r.reason
        is EventsCliParseResult.Parsed -> fail("the events verb admitted the request: ${r.args}")
    }

    private fun inspectRefusal(): String = inspectError().toCliExit().message

    private fun inspectError(): CliError = when (val r = CliInspectParser.parse(INSPECT_ARGS)) {
        is InspectParseResult.Rejected -> r.error
        is InspectParseResult.Parsed -> fail("the inspect verb admitted the request: ${r.value}")
    }

    private fun runsCancelRefusal(): String = runsCancelError().toCliExit().message

    private fun runsCancelError(): CliError =
        when (val r = MainRunsCli.parseCancelArguments(RUNS_ARGS)) {
            is MainRunsCli.OptionParse.Rejected -> r.error
            is MainRunsCli.OptionParse.Parsed -> fail("the runs verb admitted the request: ${r.value}")
        }

    private fun sagaRefusal(): String = sagaError().toCliExit().message

    private fun sagaError(): CliError = when (val r = CliSagaInspectParser.parse(SAGA_ARGS)) {
        is SagaInspectParseResult.Rejected -> r.error
        is SagaInspectParseResult.Parsed -> fail("the saga verb admitted the request: ${r.value}")
    }

    private fun consoleRefusal(): String = when (val refusal = consoleRefusalValue()) {
        is ConsoleCliRefusal.SharedRefusal -> refusal.message
        else -> fail("the console refused with its own vocabulary: $refusal")
    }

    private fun consoleRefusalValue(): ConsoleCliRefusal =
        when (val r = CliConsoleParser.parse(CONSOLE_ARGS)) {
            is ConsoleCliParseResult.Rejected -> r.refusal
            is ConsoleCliParseResult.Admitted -> fail("the console admitted the request: ${r.request}")
        }

    private fun eventsAdmitted(): Boolean =
        when (CliEventsParser.parse(arrayOf("--db", EVENTS_DB, RUN_ID))) {
            is EventsCliParseResult.Parsed -> true
            is EventsCliParseResult.Rejected -> false
        }

    private fun inspectAdmitted(): Boolean =
        when (CliInspectParser.parse(arrayOf(RUN_ID, "--db", EVENTS_DB))) {
            is InspectParseResult.Parsed -> true
            is InspectParseResult.Rejected -> false
        }

    private fun runsCancelAdmitted(): Boolean =
        when (MainRunsCli.parseCancelArguments(arrayOf("--db", EVENTS_DB, RUN_ID))) {
            is MainRunsCli.OptionParse.Parsed -> true
            is MainRunsCli.OptionParse.Rejected -> false
        }

    private fun sagaAdmitted(): Boolean =
        when (CliSagaInspectParser.parse(arrayOf(RUN_ID, "--db", EVENTS_DB))) {
            is SagaInspectParseResult.Parsed -> true
            is SagaInspectParseResult.Rejected -> false
        }

    private fun consoleAdmitted(): Boolean =
        when (CliConsoleParser.parse(arrayOf("--control-dir", CONTROL_DIR, "--db", EVENTS_DB, RUN_ID, OP_ID))) {
            is ConsoleCliParseResult.Admitted -> true
            is ConsoleCliParseResult.Rejected -> false
        }

    private fun OptionValueOutcome.refusal(): CliError = when (this) {
        is OptionValueOutcome.Refused -> error
        is OptionValueOutcome.Present -> fail("the value was accepted: $value")
    }

    private companion object {
        /** The sentence the shared owner renders for a value spelled like an option. */
        const val SHARED_SENTENCE = "value for option --db looks like another option: --control-root"

        const val RUN_ID = "run-1"
        const val OP_ID = "op-1"
        const val CONTROL_DIR = "/tmp/pipelinek-verification-control"
        const val EVENTS_DB = "/tmp/pipelinek-verification-events.db"

        /** Every verb reads the same pair: `--db` and the option it must not swallow. */
        val EVENTS_ARGS = arrayOf("--db", "--control-root", CONTROL_DIR, RUN_ID)
        val INSPECT_ARGS = arrayOf(RUN_ID, "--db", "--control-root", CONTROL_DIR)
        val RUNS_ARGS = arrayOf("--db", "--control-root", CONTROL_DIR, RUN_ID)
        val SAGA_ARGS = arrayOf(RUN_ID, "--db", "--control-root", CONTROL_DIR)
        val CONSOLE_ARGS = arrayOf("--db", "--control-root", CONTROL_DIR, RUN_ID, OP_ID)
    }
}
