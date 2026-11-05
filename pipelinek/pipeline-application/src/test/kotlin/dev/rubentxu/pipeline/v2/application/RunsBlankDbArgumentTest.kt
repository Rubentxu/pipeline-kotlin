package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The verbs of `pipelinek runs` answer a database path that names nothing with
 * the CLI's own form.
 *
 * **The guarantee.** A path that names no store is refused at the boundary that
 * read it: one line that names the option, and the exit code the CLI reserves
 * for a refusal. The verb never lets it reach the store, so the operator never
 * reads a stack trace about a value they typed.
 *
 * **The capacity this serves.** An operator closing a run whose database path
 * came from a shell variable gets an answer they can act on — the option is
 * named, and the exit code says "refused" rather than "the product crashed".
 * The two failures this replaces are the same shape: the value reaches the
 * storage engine, which opens an empty filename as a temporary database, and
 * the durability precondition of a writer fails there, far below the boundary
 * that should have answered.
 *
 * **Why this instrument.** The observable is the FORM of the answer, so the
 * instrument is the verb itself, called in process: its exit code is read and
 * its stderr is captured. The refusal happens before the store is opened, so
 * the rows below touch no file at all. The alternative — an integration test
 * that opens a real store and watches what happens to it — would have to build
 * a database to observe a decision that is taken before any database exists.
 *
 * **What this does not freeze.** The wording of the refusal, the order in
 * which the family checks its arguments, and the exit code the CLI maps a
 * refusal to; those belong to the CLI's own wiring.
 */
class RunsBlankDbArgumentTest {

    @Test
    fun `a blank db is refused by name`() {
        val answer = answerOf("cancel", "run-1", "--db", "")

        assertEquals(
            CliExit.INVALID_ARGUMENT.code,
            answer.code,
            "a database that names nothing was not refused with the refusal code: ${answer.stderr}",
        )
        assertTrue(
            answer.firstLine.contains("--db"),
            "the refusal does not name the option that is wrong: ${answer.firstLine}",
        )
        assertTrue(
            answer.firstLine.contains("blank"),
            "the refusal does not say that the value names nothing: ${answer.firstLine}",
        )
    }

    @Test
    fun `the runs family answers a blank db with the CLI's form and not a stack trace`() {
        val answer = answerOf("cancel", "run-1", "--db", "")

        assertEquals(
            CliExit.INVALID_ARGUMENT.code,
            answer.code,
            "the answer is not a refusal: ${answer.stderr}",
        )
        assertTrue(
            answer.firstLine.contains("--db"),
            "the answer does not name the option that is wrong: ${answer.firstLine}",
        )
        assertTrue(
            answer.firstLine.contains("blank"),
            "the answer does not say that the value names nothing: ${answer.firstLine}",
        )
        assertFalse(
            answer.stderr.contains("Exception in thread"),
            "the answer carries an uncaught exception: ${answer.stderr}",
        )
        assertFalse(
            answer.stderr.lines().any { it.trimStart().startsWith("at ") },
            "the answer carries a stack trace: ${answer.stderr}",
        )
    }

    @Test
    fun `a blank db is refused the same way by cancel and by recover`() {
        val cancelled = answerOf("cancel", "run-1", "--db", "")
        val recovered = answerOf("recover", "run-1", "--db", "")

        assertEquals(
            cancelled.code,
            recovered.code,
            "the two verbs of the family do not answer with the same code: " +
                "${cancelled.code} and ${recovered.code}",
        )
        assertTrue(
            recovered.firstLine.contains("--db"),
            "the refusal of recover does not name the option that is wrong: ${recovered.firstLine}",
        )
        assertTrue(
            recovered.firstLine.contains("blank"),
            "the refusal of recover does not say that the value names nothing: ${recovered.firstLine}",
        )
        assertEquals(
            cancelled.firstLine.substringAfter(": "),
            recovered.firstLine.substringAfter(": "),
            "the two verbs of the family do not say the same thing: " +
                "'${cancelled.firstLine}' and '${recovered.firstLine}'",
        )
    }

    @Test
    fun `a db of only spaces is refused too`() {
        // The storage engine reads a name of only spaces as an empty name, so
        // the refusal has to be about a path with no text in it and not about an
        // empty string: a check that only looked for the empty string would let
        // this one through.
        val answer = answerOf("cancel", "run-1", "--db", " ")

        assertEquals(
            CliExit.INVALID_ARGUMENT.code,
            answer.code,
            "a database of only spaces was not refused with the refusal code: ${answer.stderr}",
        )
        assertTrue(
            answer.firstLine.contains("--db"),
            "the refusal does not name the option that is wrong: ${answer.firstLine}",
        )
        assertTrue(
            answer.firstLine.contains("blank"),
            "the refusal does not say that the value names nothing: ${answer.firstLine}",
        )
    }

    @Test
    fun `a db that names a store is not refused`() {
        // The other half of the partition: a refusal that is too wide is a
        // defect in the opposite direction. The reason carries the only value
        // this family refuses, so the answer has to be about the reason and not
        // about the database. The reason is checked before the store is opened,
        // so nothing here reaches the filesystem.
        val answer = answerOf(
            "cancel",
            "run-1",
            "--db",
            "/tmp/pipelinek-runs-blank-db/run.sqlite",
            "--reason",
            "a reason\nwith a newline",
        )

        assertEquals(
            CliExit.INVALID_ARGUMENT.code,
            answer.code,
            "the answer is not a refusal: ${answer.stderr}",
        )
        assertTrue(
            answer.firstLine.contains("--reason"),
            "the answer does not name the option that is wrong: ${answer.firstLine}",
        )
        assertFalse(
            answer.firstLine.contains("--db"),
            "a database that names a store was refused: ${answer.firstLine}",
        )
    }

    /**
     * The answer of the family, as the operator reads it.
     *
     * The verb is called in process with its own streams, so what is read here
     * are the same bytes the CLI shows.
     */
    private fun answerOf(vararg args: String): Answer {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = MainRunsCli.main(arrayOf(*args), PrintStream(out), PrintStream(err))
        return Answer(code = code, stdout = out.toString(), stderr = err.toString())
    }

    /**
     * What a verb answered.
     *
     * [firstLine] is the refusal itself: the usage the family prints after a
     * refusal comes after it, and the usage names every option the family
     * knows, so a search over the whole of stderr would find an option that
     * the refusal does not name.
     */
    private data class Answer(val code: Int, val stdout: String, val stderr: String) {
        val firstLine: String
            get() = stderr.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }
}
