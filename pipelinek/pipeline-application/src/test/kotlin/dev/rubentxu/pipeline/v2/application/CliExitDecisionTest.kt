package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a refusal is a DECISION that travels to the entry point, and
 * the entry point is the only place that ends the process.
 *
 * The capacity this serves: a caller that reads an exit code can tell a
 * refusal from a failure, and the CLI can be exercised in-process without the
 * harness dying under it. Before the cut, three helpers answered by ending
 * the JVM: the read-only dispatcher, the family door and the output-protocol
 * negotiation. A helper that ends the process takes a decision that is not its
 * own, and it takes it away from the one place that owns the process.
 *
 * ## Why this instrument
 *
 * The rows below CALL the helpers that used to end the process. That call is
 * the observation: a helper that ended the process would never return, and the
 * row that follows it would never run. The decision is read from the value the
 * helper returns, so a helper that answered with a number, or with nothing at
 * all, is caught here.
 *
 * ## What this does not freeze
 *
 * The WORDS a refusal carries. The sentence is the renderer's, and it is
 * asserted where it belongs, in the file that owns the rendering. These rows
 * assert the case, the code and the fact that the refusal reached the caller.
 *
 * ## What this instrument cannot see
 *
 * It cannot see WHICH function ends the process. Nothing in a unit test can:
 * the invariant is a property of the source, and the instrument for it is the
 * repository guard that names the offending function and line. These rows and
 * that guard answer two different questions — this one asks what a refusal
 * IS, the guard asks who is allowed to end the process — and neither can
 * stand in for the other.
 *
 * These rows are pure: no filesystem, no clock, no process, no network. The
 * only side effect is what the CLI prints, and that is captured and read.
 */
class CliExitDecisionTest {

    @Test
    fun `a read-only verb answers with a decision`() {
        val (outcome, streams) = capturing { handleReadOnlyVerb(arrayOf("capabilities")) }

        val answered = assertInstanceOf(
            ReadOnlyVerbOutcome.Answered::class.java,
            outcome,
            "a read-only verb answers the request",
        )
        assertEquals(0, answered.exit.code, "the verb succeeded, so the success case carries the code")
        assertTrue(
            streams.stdout.contains("capabilities"),
            "the verb printed its answer: ${streams.stdout}",
        )
    }

    @Test
    fun `a family word on its own answers with a decision`() {
        val (outcome, streams) = capturing { handleReadOnlyVerb(arrayOf("saga")) }

        val answered = assertInstanceOf(
            ReadOnlyVerbOutcome.Answered::class.java,
            outcome,
            "the door of the family answers the request",
        )
        assertEquals(2, answered.exit.code, "a request the CLI did not understand is the invalid-argument case")
        assertEquals(
            "missing required argument: subcommand",
            answered.exit.message,
            "the refusal is the shared one",
        )
        assertTrue(
            streams.stderr.contains("missing required argument: subcommand"),
            "the refusal still reached the operator: ${streams.stderr}",
        )
    }

    @Test
    fun `a word no verb answers is not answered at all`() {
        val outcome = handleReadOnlyVerb(arrayOf("no-such-verb"))

        assertInstanceOf(
            ReadOnlyVerbOutcome.NotHandled::class.java,
            outcome,
            "a word that names no verb is not a request this CLI answers",
        )
    }

    @Test
    fun `an unsupported output protocol is refused with a decision`() {
        val decision = negotiateOutputProtocol(listOf("pipelinek.output/9"))

        val refused = assertInstanceOf(
            OutputProtocolDecision.Refused::class.java,
            decision,
            "a protocol this build does not speak is refused",
        )
        assertEquals(2, refused.exit.code, "an unusable request is the invalid-argument case")
        assertTrue(
            refused.exit.message.contains("no acceptable --output-protocol"),
            "the refusal names what was not acceptable: ${refused.exit.message}",
        )
        assertTrue(
            refused.exit.message.contains("pipelinek.output/1"),
            "the refusal names what this build does speak: ${refused.exit.message}",
        )
    }

    @Test
    fun `a supported output protocol is accepted`() {
        val decision = negotiateOutputProtocol(listOf("pipelinek.output/2"))

        val accepted = assertInstanceOf(
            OutputProtocolDecision.Accepted::class.java,
            decision,
            "a protocol this build speaks is accepted",
        )
        assertEquals(OutputProtocol.SeparatedV2, accepted.protocol)
    }

    @Test
    fun `no protocol requested means the default one`() {
        val decision = negotiateOutputProtocol(emptyList())

        val accepted = assertInstanceOf(
            OutputProtocolDecision.Accepted::class.java,
            decision,
            "a run that asked for nothing gets the default protocol",
        )
        assertEquals(OutputProtocol.DEFAULT, accepted.protocol)
    }

    @Test
    fun `a code the taxonomy does not declare is refused`() {
        val exit = verbExit(42)

        assertEquals(
            4,
            exit.code,
            "a code nobody declared is the runtime-not-available case",
        )
        assertTrue(
            exit.message.contains("42"),
            "the refusal names the code that was not declared: ${exit.message}",
        )
    }

    @Test
    fun `every declared code finds its case`() {
        val declared = (0..7).map { code -> code to CliExit.of(code) }

        val missing = declared.filter { (_, case) -> case == null }.map { (code, _) -> code }

        assertEquals(emptyList<Int>(), missing, "every code of the taxonomy has a case")
        assertNull(CliExit.of(42), "a code the taxonomy does not declare has no case")
    }

    /** The value a call produced, together with what it printed. */
    private data class Streams(val stdout: String, val stderr: String)

    /**
     * Run [block] with the console captured, and give back both.
     *
     * The CLI prints its answer on stdout and its refusal on stderr, and a row
     * that asserts a decision has to be able to read what the decision said
     * out loud without letting it into the report of the run.
     */
    private fun <T> capturing(block: () -> T): Pair<T, Streams> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(err, true, Charsets.UTF_8.name()))
            val value = block()
            return value to Streams(out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
