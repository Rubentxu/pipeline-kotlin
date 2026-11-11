package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The shape of the requests `substrate init` accepts.
 *
 * ## The guarantee under trial
 *
 * That the preparation of a substrate is a step the operator NAMES: it names
 * the file the substrate is, and it refuses a request that leaves it out or
 * that carries something the verb does not own. A preparation that invented a
 * place of its own would be a substrate nobody can compare against the command
 * line, and the service would be answering about runs the operator never
 * started.
 *
 * ## The capacity this serves
 *
 * The operator reads a refusal and knows what to type next. The alternative —
 * a preparation that picks a store of its own — gives the operator a file they
 * did not choose and a service pointing at it.
 *
 * ## Why this instrument
 *
 * Every row below is REFUSED before the verb touches the filesystem, so the
 * cheapest instrument that can fail is to call the verb and read what it
 * printed. The rows that prepare a substrate are INTEGRATION rows: they open
 * a real database, and the engine is the thing under trial there.
 *
 * ## What this does not freeze
 *
 * The words the operator reads, beyond the option they name, and the exit
 * code, which the taxonomy owns.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MainSubstrateInitCliTest {

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
    fun `an option the verb does not own is refused by name`() {
        val answer = ask("--db", "/tmp/store.db", "--control-root", "/tmp/control")

        assertEquals(2, answer.exit.code)
        assertTrue(
            answer.stderr.contains("unknown option: --control-root"),
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
            answer.stderr.contains("pipelinek substrate init --db <path>"),
            "the refusal says how the verb is called: ${answer.stderr}",
        )
    }

    @Test
    fun `the family word alone is refused with the usage of the family`() {
        // `substrate` is a family and not a form: the verb is `substrate init`.
        // The refusal names the forms the family owns, and the usage under it
        // is the usage of this family.
        val door = requireNotNull(
            cliFamilyDoor(arrayOf("substrate")),
        ) { "the family word 'substrate' has no refusal" }

        assertEquals(
            SUBSTRATE_INIT_USAGE,
            door.usage,
            "the family prints the usage of its own form",
        )
        assertTrue(
            door.error.refusalLines("pipeline").any { it.contains("substrate init") },
            "the refusal names the form the family owns: ${door.error}",
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

        val exit = runSubstrateInitVerb(words.toList().toTypedArray(), out, err)
        out.flush()
        err.flush()

        return Answer(exit, outBytes.toString(Charsets.UTF_8), errBytes.toString(Charsets.UTF_8))
    }
}
