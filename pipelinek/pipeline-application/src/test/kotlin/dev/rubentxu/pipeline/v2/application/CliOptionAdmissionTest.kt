package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The admission of an option to the verb that owns it.
 *
 * ## The guarantee
 *
 * A verb refuses an option it does not own, and the refusal names the option.
 * The verb's own vocabulary is the authority: an option the BUILD knows and the
 * verb does not own is refused exactly like an option nobody knows, because the
 * defect is the verb's, not the build's.
 *
 * ## The capacity this serves
 *
 * An operator who mistypes a flag, or hands a flag to a verb that never
 * accepted it, gets a refusal on stderr and a nonzero exit. Before this
 * admission, `operations <runId> --control-root <dir> --bogus-flag` published
 * an empty list of operations and exited 0: the answer was about a run the
 * operator never asked about, and it looked like the truth.
 *
 * ## Why this instrument
 *
 * The admission is a pure function over an argument array, so the cheapest
 * instrument that can fail is to call it and compare the outcome against a
 * literal expectation written here. The verbs are exercised in the same
 * process: they are called, their exit code is read and their stderr is
 * captured, so the seam between "the verb was given an option" and "the verb
 * refused it" is crossed without a subprocess.
 *
 * The expectations are literals, and the only thing read from production is
 * the vocabulary under test. An expectation computed from the same table it
 * checks would agree with every mutation of that table, which is the one
 * thing a test must not do.
 *
 * ## What this does not freeze
 *
 * The wording of the refusal is not frozen, except for the token of the
 * offending option, which the operator has to be able to read. A verb that
 * printed its own sentence instead of the shared one would still refuse.
 *
 * ## What this instrument cannot see
 *
 * The verbs that own a different vocabulary -- `run`, `events`, `console`,
 * `inspect`, `saga inspect`, the `runs` family and `observe` -- are not here:
 * their option tables are not this one's, and they already refused an unknown
 * option before this admission existed.
 *
 * `events verify` reads this admission too, and its own rows -- the refusals
 * the verb makes with the words it makes them with -- are in
 * `MainEventsVerifyCliTest`. What is here is the vocabulary and the rule that
 * decides whether a request is readable at all.
 */
class CliOptionAdmissionTest {

    @Test
    fun `a verb that owns no option refuses one`() {
        assertEquals(
            CliAdmission.Rejected(CliError.UnknownOption("--json")),
            CliOptionAdmission.admit(arrayOf("--json"), NO_OPTIONS),
        )
    }

    @Test
    fun `an option the build knows and the verb does not own is refused too`() {
        assertEquals(
            CliAdmission.Rejected(CliError.UnknownOption("--db")),
            CliOptionAdmission.admit(arrayOf("--db", "/tmp/a-plane"), NO_OPTIONS),
        )
    }

    @Test
    fun `a verb admits the option it owns`() {
        assertEquals(
            CliAdmission.Admitted(
                flags = setOf("--json"),
                values = emptyMap(),
                positionals = emptyList(),
            ),
            CliOptionAdmission.admit(arrayOf("--json"), VERSION_OPTIONS),
        )
    }

    @Test
    fun `a valued option takes the token after it`() {
        assertEquals(
            CliAdmission.Admitted(
                flags = emptySet(),
                values = mapOf("--control-root" to "/tmp/a-plane"),
                positionals = listOf("run-1"),
            ),
            CliOptionAdmission.admit(
                arrayOf("run-1", "--control-root", "/tmp/a-plane"),
                OPERATIONS_OPTIONS,
            ),
        )
    }

    @Test
    fun `a valued option with no value is refused`() {
        assertEquals(
            CliAdmission.Rejected(CliError.MissingOptionValue("--control-root")),
            CliOptionAdmission.admit(arrayOf("run-1", "--control-root"), OPERATIONS_OPTIONS),
        )
    }

    @Test
    fun `a value that names an option of this build is refused`() {
        assertEquals(
            CliAdmission.Rejected(CliError.OptionValueIsAnOption("--control-root", "--format")),
            CliOptionAdmission.admit(
                arrayOf("run-1", "--control-root", "--format"),
                OPERATIONS_OPTIONS,
            ),
        )
    }

    @Test
    fun `a value that names nothing this build knows is a value`() {
        assertEquals(
            CliAdmission.Admitted(
                flags = emptySet(),
                values = mapOf("--control-root" to "--bogus-flag"),
                positionals = listOf("run-1"),
            ),
            CliOptionAdmission.admit(
                arrayOf("run-1", "--control-root", "--bogus-flag"),
                OPERATIONS_OPTIONS,
            ),
        )
    }

    @Test
    fun `a value that names an option of the verb itself is refused`() {
        // The verb's own tokens are the stronger half of the rule: a token
        // this request's verb owns is even less likely to be a path than one
        // the shared parser happens to know.
        assertEquals(
            CliAdmission.Rejected(CliError.OptionValueIsAnOption("--db", "--run")),
            CliOptionAdmission.admit(
                arrayOf("--db", "--run", "run-1"),
                EVENTS_VERIFY_OPTIONS,
            ),
        )
    }

    @Test
    fun `the events verify vocabulary is the one its verb reads`() {
        assertEquals(
            CliAdmission.Admitted(
                flags = emptySet(),
                values = mapOf(
                    "--db" to "/tmp/store/events.db",
                    "--run" to "run-1",
                    "--contract" to "/tmp/store/events.yaml",
                ),
                positionals = emptyList(),
            ),
            CliOptionAdmission.admit(
                arrayOf("--db", "/tmp/store/events.db", "--run", "run-1", "--contract", "/tmp/store/events.yaml"),
                EVENTS_VERIFY_OPTIONS,
            ),
        )
    }

    @Test
    fun `the two option vocabularies agree about every token they share`() {
        val shared = (VERSION_OPTIONS + OPERATIONS_OPTIONS + EVENTS_VERIFY_OPTIONS)
            .filter { knownOptionArity(it.token) != null }
        assertTrue(shared.isNotEmpty(), "no token is shared; the agreement proves nothing")
        for (option in shared) {
            assertEquals(
                option.takesValue,
                knownOptionArity(option.token),
                "the verb and the shared parser disagree about ${option.token}",
            )
        }
    }

    @Test
    fun `version refuses an option it does not own`() {
        val result = capture { runVersionVerb(arrayOf("--db")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--db"), result.stderr)
    }

    @Test
    fun `doctor refuses an option it does not own`() {
        val result = capture { runDoctorVerb(arrayOf("--json")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--json"), result.stderr)
    }

    @Test
    fun `capabilities refuses an option it does not own`() {
        val result = capture { MainCapabilitiesCli.main(arrayOf("--view")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--view"), result.stderr)
    }

    @Test
    fun `sdk-info refuses an option it does not own`() {
        val result = capture { MainSdkInfoCli.main(arrayOf("--db")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--db"), result.stderr)
    }

    @Test
    fun `operations refuses an option it does not own and publishes nothing`() {
        val result = capture { MainOperationsCli.main(arrayOf("run-1", "--bogus-flag")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--bogus-flag"), result.stderr)
        // The dangerous shape: a refusal that still publishes a list of nothing.
        // An operator would read it as a run that published no operations.
        assertEquals("", result.stdout)
    }

    @Test
    fun `operations refuses a value that is another option`() {
        val result = capture { MainOperationsCli.main(arrayOf("--control-root", "--db")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--db"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `version still answers the option it owns`() {
        val result = capture { runVersionVerb(arrayOf("--json")) }

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("productVersion"), result.stdout)
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
