package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventStoreRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * What a refusal says when the value it names is empty.
 *
 * ## The guarantee
 *
 * A refusal names the value that is wrong, because the value is what the
 * operator has to change. A value that is drawn RAW stops naming it the moment
 * it is empty: the sentence keeps its shape and loses its content, and the
 * operator is left reading a phrase with a hole where the data was.
 *
 * The guarantee is that the drawing is always visible: an empty value is drawn
 * `''`, a value of only spaces is drawn `' '`, and a value with visible
 * characters is drawn exactly as it was before.
 *
 * ## The capacity this serves
 *
 * An operator who wrote `--db "$DB"` with the variable unset has to learn what
 * to write instead. A refusal that leaves a hole tells them the CLASS of the
 * mistake and nothing about the value, and the value is the whole of what they
 * have to fix. Every verb of this CLI already draws a value that is not there
 * between quotes — `unknown token ''`, `got ''` — so the hole is not a limit
 * of the format: it is that most families paste the value raw.
 *
 * ## Why this instrument
 *
 * The observable is the FORM of the sentence, and the sentences are made by
 * pure functions: the central mapping of a [CliError] to words, the console
 * family, the store family, the event-read family and the `events` parser.
 * Calling them with an empty value and reading the sentence is therefore the
 * cheapest instrument that can fail, and it touches nothing: no file, no
 * database, no clock.
 *
 * One row crosses the seam and calls the VERB, because a renderer that the
 * verb does not use would be a renderer nobody reads.
 *
 * ## What this does not freeze
 *
 * The wording of each sentence, and which of them exist. It freezes the way a
 * VALUE is drawn, and the sentence of a refusal with a visible value, which is
 * a contract other people read.
 */
class CliBlankValueRefusalTest {

    @Test
    fun `the central mapping names the empty value it was given`() {
        val command = CliError.InvalidCommand("").toCliExit().message
        val view = CliError.InvalidView("").toCliExit().message
        val positional = CliError.UnexpectedPositional("").toCliExit().message

        assertShowsTheEmptyValue(command, "the refusal of an empty command")
        assertShowsTheEmptyValue(view, "the refusal of an empty view")
        assertShowsTheEmptyValue(positional, "the refusal of an empty positional")
    }

    @Test
    fun `the console family names the empty value it was given`() {
        val extra = renderConsoleCliRefusal(ConsoleCliRefusal.UnexpectedArgument(""))
        val number = renderConsoleCliRefusal(ConsoleCliRefusal.NotAnInteger("--max-bytes", ""))
        val cursor = renderConsoleCliRefusal(ConsoleCliRefusal.NotACursorToken(""))

        assertShowsTheEmptyValue(extra, "the refusal of an empty extra argument")
        assertShowsTheEmptyValue(number, "the refusal of an empty number")
        assertShowsTheEmptyValue(cursor, "the refusal of an empty cursor")
    }

    @Test
    fun `the store family names the empty path it was given`() {
        val absent = EventStoreRefusal.Absent("").render()
        val noDirectory = EventStoreRefusal.NoDirectory("").render()
        val notReadable = EventStoreRefusal.NotReadable("", "file is not a database").render()
        val noHistory = EventStoreRefusal.NoEventHistory("").render()

        assertShowsTheEmptyValue(absent, "the refusal of an absent store")
        assertShowsTheEmptyValue(noDirectory, "the refusal of a missing directory")
        assertShowsTheEmptyValue(notReadable, "the refusal of an unreadable store")
        assertShowsTheEmptyValue(noHistory, "the refusal of a store with no history")
    }

    @Test
    fun `the event-read family names the empty run it was given`() {
        val unknownRun = EventRecordReadRefusal.UnknownRun("").render()
        val pastTail = EventRecordReadRefusal.CursorBeyondTail("", 9, 4).render()

        assertShowsTheEmptyValue(unknownRun, "the refusal of an unknown run")
        assertShowsTheEmptyValue(pastTail, "the refusal of a cursor past the tail")
    }

    @Test
    fun `the events parser names the empty value it was given`() {
        val extra = reasonOf(CliEventsParser.parse(arrayOf("run-1", "--db", "/tmp/run.sqlite", "")))
        val limit = reasonOf(
            CliEventsParser.parse(arrayOf("run-1", "--db", "/tmp/run.sqlite", "--limit", ""))
        )

        assertShowsTheEmptyValue(extra, "the refusal of an empty extra argument")
        assertShowsTheEmptyValue(limit, "the refusal of an empty limit")
    }

    @Test
    fun `a value of only spaces is named as the spaces it is`() {
        // A value of only spaces is NOT empty, and a refusal that draws it as
        // the empty value would be saying something false about it. It is drawn
        // as the spaces it is, so the operator sees what they wrote.
        val text = CliError.InvalidView(" ").toCliExit().message

        assertTrue(
            text.contains("' '"),
            "the refusal does not show the spaces the operator wrote: $text",
        )
        assertFalse(
            text.contains("''"),
            "the refusal calls a value of spaces an empty value: $text",
        )
    }

    @Test
    fun `a value with visible characters keeps the sentence it had`() {
        // The rule only fills a hole. A refusal that names a value the operator
        // CAN see keeps the exact sentence it has always had, because that
        // sentence is a contract other people read.
        assertEquals(
            "unknown command: pipelinek",
            CliError.InvalidCommand("pipelinek").toCliExit().message,
        )
        assertEquals(
            "unexpected argument: extra",
            CliError.UnexpectedPositional("extra").toCliExit().message,
        )
        assertEquals(
            "Error: unexpected extra argument: extra",
            renderConsoleCliRefusal(ConsoleCliRefusal.UnexpectedArgument("extra")),
        )
        assertEquals(
            "Error: db not found: /runs/run-42/events.db",
            EventStoreRefusal.Absent("/runs/run-42/events.db").render(),
        )
        assertEquals(
            "Error: unknown run: run-1",
            EventRecordReadRefusal.UnknownRun("run-1").render(),
        )
        assertEquals(
            "unexpected extra argument: extra",
            reasonOf(CliEventsParser.parse(arrayOf("run-1", "--db", "/tmp/run.sqlite", "extra"))),
        )
    }

    @Test
    fun `the verb shows the empty argument the operator wrote`() {
        // The seam: a renderer the verb does not use is a renderer nobody
        // reads. The verb refuses while it parses, so this row touches no
        // file and no database.
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = MainInspectCli.main(
            arrayOf("run-1", "--control-root", "/nonexistent-control-root", ""),
            PrintStream(out),
            PrintStream(err),
        )

        assertEquals(2, code, "the refusal is not the invalid-argument case: ${err.toString()}")
        assertShowsTheEmptyValue(
            err.toString().lineSequence().first { it.isNotBlank() },
            "the refusal of the verb",
        )
    }

    /**
     * The refusal shows the value, and it does not leave a hole where the
     * value goes.
     */
    private fun assertShowsTheEmptyValue(text: String, what: String) {
        assertTrue(
            text.contains("''"),
            "$what does not show the empty value: $text",
        )
        assertFalse(
            text.trimEnd().endsWith(":"),
            "$what leaves a hole where the value goes: $text",
        )
    }

    /** The reason a parse was refused. */
    private fun reasonOf(result: EventsCliParseResult): String =
        when (result) {
            is EventsCliParseResult.Rejected -> result.reason
            is EventsCliParseResult.Parsed -> error("the request was not refused: ${result.args}")
        }
}
