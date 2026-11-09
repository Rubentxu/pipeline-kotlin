package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shape of the requests `serve` accepts.
 *
 * ## The guarantee under trial
 *
 * The service is composed over the substrate the operator NAMES, and every
 * part of that substrate is named with an option: the store, the control root
 * and the socket. A request that leaves one of them out is refused by naming
 * the one, because a service that invented a default for any of the three
 * would be serving something the operator did not choose — and a service
 * listening somewhere the operator did not name is a service nobody can find
 * and nobody can close.
 *
 * ## The capacity this serves
 *
 * The operator reads a refusal and knows what to type next. The alternative —
 * a service that starts with a store of its own, or on a socket of its own
 * choosing — gives the operator a process they cannot point a client at and
 * a substrate they cannot compare against the command line.
 *
 * ## Why this instrument
 *
 * The admission is a pure function of an array of arguments: no socket, no
 * store, no clock, no process. Every row below is REFUSED before the verb
 * touches the filesystem, so the cheapest instrument that can fail is to
 * call the verb and read what it printed.
 *
 * The handler is called with the arguments that follow the verb's own words,
 * which is what the inventory's dispatcher does: a row that passed the
 * verb's word as if it were an argument would be testing the wrong request.
 *
 * ## What this does not freeze
 *
 * The words the operator reads and the exit code, which the taxonomy owns.
 */
class MainServeCliTest {

    @Test
    fun `a request without the store is refused by naming it`() {
        val answer = ask()

        assertEquals(
            2,
            answer.exit.code,
            "a request without the store is refused as an invalid argument",
        )
        assertTrue(
            answer.stderr.contains("missing required argument: --db <path>"),
            "the refusal names the option that is missing: ${answer.stderr}",
        )
    }

    @Test
    fun `a request with the store and without the control root is refused by naming it`() {
        val answer = ask("--db", "/tmp/store.db")

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("missing required argument: --control-root <path>"),
            "the refusal names the option that is missing: ${answer.stderr}",
        )
    }

    @Test
    fun `a request without the socket is refused by naming it`() {
        val answer = ask("--db", "/tmp/store.db", "--control-root", "/tmp/control")

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("missing required argument: --socket <path>"),
            "the refusal names the option that is missing: ${answer.stderr}",
        )
    }

    @Test
    fun `an option the verb does not own is refused by name`() {
        val answer = ask(
            "--db",
            "/tmp/store.db",
            "--control-root",
            "/tmp/control",
            "--socket",
            "/tmp/service.sock",
            "--json",
        )

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("unknown option: --json"),
            "the refusal names the option the verb does not own: ${answer.stderr}",
        )
    }

    @Test
    fun `a positional argument is refused`() {
        val answer = ask("extra")

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("unexpected argument: extra"),
            "the refusal names the argument that does not belong: ${answer.stderr}",
        )
    }

    @Test
    fun `the refusal prints the usage of the verb`() {
        val answer = ask()

        assertTrue(
            answer.stderr.contains("pipelinek serve --db <path> --control-root <path> --socket <path>"),
            "the refusal says how the verb is called: ${answer.stderr}",
        )
    }

    /**
     * The decision of the verb, and what it printed.
     */
    private data class Answer(val exit: CliExit, val stdout: String, val stderr: String)

    /**
     * Asks the verb with [words] — the arguments that follow the verb's own
     * words — and reads its answer.
     */
    private fun ask(vararg words: String): Answer {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val out = PrintStream(outBytes, true, Charsets.UTF_8.name())
        val err = PrintStream(errBytes, true, Charsets.UTF_8.name())

        val exit = runServeVerb(words.toList().toTypedArray(), out, err)
        out.flush()
        err.flush()

        return Answer(exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }
}
