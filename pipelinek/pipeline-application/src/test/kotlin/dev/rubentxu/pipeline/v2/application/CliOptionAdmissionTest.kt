package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * The same holds for a POSITIONAL argument. A positional argument is part of
 * the request too, and a verb that owns none has to say that a request
 * carrying one is not one of its requests. Measured on the built distribution,
 * `doctor foo`, `version foo`, `capabilities foo` and `sdk-info foo` answered
 * 0 and printed exactly what the bare verb prints: a confident answer about a
 * request nobody had made.
 *
 * A verb that owns one owns EXACTLY one, and the second one is refused
 * naming it: `operations <runId> extra-arg` published the operations of
 * `<runId>` and said nothing about `extra-arg`, so the operator who asked
 * about two runs read an answer about one.
 *
 * ## The capacity this serves
 *
 * An operator who mistypes a flag, hands a flag to a verb that never accepted
 * it, or types an argument the verb does not take, gets a refusal on stderr
 * and a nonzero exit. Before this admission, `operations <runId>
 * --control-root <dir> --bogus-flag` published an empty list of operations and
 * exited 0: the answer was about a run the operator never asked about, and it
 * looked like the truth.
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
 * offending option or argument, which the operator has to be able to read. A
 * verb that printed its own sentence instead of the shared one would still
 * refuse.
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
 *
 * `doctor` has no row that exercises the request that IS its own: its health
 * probe writes a temporary file into the working directory, and a unit test in
 * this tree does not touch the filesystem. The other three verbs are exercised
 * both ways.
 */
class CliOptionAdmissionTest {

    @Test
    fun `a verb that owns no option refuses one`() {
        assertEquals(
            CliAdmission.Rejected(CliError.UnknownOption("--json")),
            CliOptionAdmission.admit(arrayOf("--json"), NO_ARGUMENTS_REQUEST),
        )
    }

    @Test
    fun `an option the build knows and the verb does not own is refused too`() {
        assertEquals(
            CliAdmission.Rejected(CliError.UnknownOption("--db")),
            CliOptionAdmission.admit(arrayOf("--db", "/tmp/a-plane"), NO_ARGUMENTS_REQUEST),
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
            CliOptionAdmission.admit(arrayOf("--json"), VERSION_REQUEST),
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
                OPERATIONS_REQUEST,
            ),
        )
    }

    @Test
    fun `a valued option with no value is refused`() {
        assertEquals(
            CliAdmission.Rejected(CliError.MissingOptionValue("--control-root")),
            CliOptionAdmission.admit(arrayOf("run-1", "--control-root"), OPERATIONS_REQUEST),
        )
    }

    @Test
    fun `a value that names an option of this build is refused`() {
        assertEquals(
            CliAdmission.Rejected(CliError.OptionValueIsAnOption("--control-root", "--format")),
            CliOptionAdmission.admit(
                arrayOf("run-1", "--control-root", "--format"),
                OPERATIONS_REQUEST,
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
                OPERATIONS_REQUEST,
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
                EVENTS_VERIFY_REQUEST,
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
                EVENTS_VERIFY_REQUEST,
            ),
        )
    }

    @Test
    fun `the two option vocabularies agree about every token they share`() {
        val shared = listOf(VERSION_REQUEST, OPERATIONS_REQUEST, EVENTS_VERIFY_REQUEST)
            .flatMap { it.options }
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
    fun `a verb that owns no positional argument refuses one`() {
        assertEquals(
            CliAdmission.Rejected(CliError.UnexpectedPositional("foo")),
            CliOptionAdmission.admit(arrayOf("foo"), NO_ARGUMENTS_REQUEST),
        )
    }

    @Test
    fun `a verb that owns a positional argument reads it`() {
        assertEquals(
            CliAdmission.Admitted(
                flags = emptySet(),
                values = emptyMap(),
                positionals = listOf("run-1"),
            ),
            CliOptionAdmission.admit(
                arrayOf("run-1"),
                OPERATIONS_REQUEST,
            ),
        )
    }

    @Test
    fun `a verb that owns one positional argument refuses a second one`() {
        // The verb owns the run id and the request carries another one. The
        // verb used to read the first and to drop the second in silence, so
        // the operator asked about two runs and read an answer about one.
        assertEquals(
            CliAdmission.Rejected(CliError.TooManyPositionals("run-2")),
            CliOptionAdmission.admit(arrayOf("run-1", "run-2"), OPERATIONS_REQUEST),
        )
    }

    @Test
    fun `version refuses an option it does not own`() {
        val result = capture { runVersionVerb(arrayOf("--db")).code }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--db"), result.stderr)
    }

    @Test
    fun `doctor refuses an option it does not own`() {
        val result = capture { runDoctorVerb(arrayOf("--json")).code }

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
        val result = capture { runVersionVerb(arrayOf("--json")).code }

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("productVersion"), result.stdout)
    }

    @Test
    fun `version refuses an argument it was not asked about`() {
        val result = capture { runVersionVerb(arrayOf("foo")).code }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("foo"), result.stderr)
        // The dangerous shape: a refusal that still answers. An operator would
        // read the version line as the answer to the request they made.
        assertEquals("", result.stdout)
    }

    @Test
    fun `doctor refuses an argument it was not asked about`() {
        val result = capture { runDoctorVerb(arrayOf("foo")).code }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("foo"), result.stderr)
        // The health report is the answer to the request `doctor` was asked
        // for; printing it here would answer a request nobody made.
        assertEquals("", result.stdout)
    }

    @Test
    fun `capabilities refuses an argument it was not asked about`() {
        val result = capture { MainCapabilitiesCli.main(arrayOf("foo")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("foo"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `sdk-info refuses an argument it was not asked about`() {
        val result = capture { MainSdkInfoCli.main(arrayOf("foo")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("foo"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `operations still reads the run id it owns`() {
        val result = capture { MainOperationsCli.main(arrayOf("run-1")) }

        // The verb owns the run id, so the argument is admitted and READ: the
        // refusal is about the NEXT thing the verb needs, not about the
        // argument. A refusal that named "run-1" would say the opposite.
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("--control-root"), result.stderr)
        assertFalse(result.stderr.contains("run-1"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `operations refuses the second positional argument it does not own`() {
        // No --control-root on purpose: the refusal of the extra argument
        // happens in the admission, BEFORE the verb looks for anything else,
        // and a unit test in this tree does not touch the filesystem. The
        // request is refused naming the EXTRA argument and not the first one,
        // which the verb owns.
        val result = capture { MainOperationsCli.main(arrayOf("run-1", "run-2")) }

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("run-2"), result.stderr)
        // The FIRST one is not the offending token: the verb owns it. A
        // refusal that named "run-1" would say the opposite of the truth.
        assertFalse(result.stderr.contains("run-1"), result.stderr)
        // The dangerous shape: a refusal that still publishes a listing of
        // the first run. The operator asked about two runs and would read an
        // answer about one.
        assertEquals("", result.stdout)
    }

    @Test
    fun `capabilities still answers the request that is its own`() {
        val result = capture { MainCapabilitiesCli.main(arrayOf()) }

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("pipelinek.capabilities/2"), result.stdout)
    }

    @Test
    fun `sdk-info still answers the request that is its own`() {
        val result = capture { MainSdkInfoCli.main(arrayOf()) }

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("pipelinek.sdk/1"), result.stdout)
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
