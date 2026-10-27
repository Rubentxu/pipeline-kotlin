package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The verb that decides whether a run satisfied its contract.
 *
 * ## The guarantee
 *
 * `events verify` says what it cannot read. An option it does not own, an
 * argument it was not asked about, a value that is not a value it can read:
 * each one is refused BY NAME, and the refusal happens before the store is
 * touched.
 *
 * ## The capacity this serves
 *
 * This verb is the CLI's acceptance gate: it answers whether a run met its
 * contract. It used to read its options with a hand-written loop whose `when`
 * had no branch for anything it did not recognise, so an option it did not own
 * was dropped without a word — `events verify --db <db> --run <r> --contract
 * <c> --bogus` printed EXACTLY what the same invocation without `--bogus`
 * printed. It also read two values it could not read, and read them as
 * something else: `--from-sequence abc` became the sequence 0, and `--scope
 * anything` became the scope WHOLE. Every one of those answers was about a
 * request nobody had made, and the verb that issues verdicts was issuing them.
 *
 * ## Why this instrument
 *
 * The refusals are decided before any I/O, so the cheapest instrument that can
 * fail is to call the verb in this process, read its exit code and read its
 * stderr. No subprocess, no disk, no clock.
 *
 * The expectations are literals written here. The only thing read from
 * production is the vocabulary the refusal names.
 *
 * ## Why the refusal is read from before the usage line
 *
 * The usage line names `--scope` and `--from-sequence` too, because they are
 * forms the verb accepts. A row that looked for "--scope" in the whole of
 * stderr would be satisfied by the usage line alone, and a verb that printed
 * nothing but its usage would pass it. Every row here therefore reads the
 * REFUSAL — the part of stderr that comes before the usage line — and the
 * usage line is checked separately, for what it names.
 *
 * ## What this does not freeze
 *
 * The wording of a refusal is not frozen, only the fragments that carry the
 * operator's way out: the offending token, the accepted vocabulary, and the
 * connector that says the value is not one of them.
 *
 * ## What this instrument cannot see
 *
 * The path that leads to the store. A request that this verb ACCEPTS opens the
 * event store, and a unit test in this tree does not open a database; the
 * store's own admission is exercised in the module that owns it
 * (`EventStoreAdmissionTest`). What is frozen here is the boundary: a refused
 * request never reaches the store, which is read from the ABSENCE of the
 * store's own refusal in the verb's stderr.
 */
class MainEventsVerifyCliTest {

    @Test
    fun `events verify refuses an option it does not own`() {
        val result = capture {
            MainEventsVerifyCli.main(
                arrayOf("--db", "/tmp/store/events.db", "--run", "run-1", "--contract", "/tmp/store/events.yaml", "--bogus"),
            )
        }

        assertEquals(2, result.exitCode)
        assertTrue(refusalOf(result).contains("--bogus"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `events verify refuses a request it could not read before it touches the store`() {
        val result = capture {
            MainEventsVerifyCli.main(
                arrayOf("--db", "/tmp/store/events.db", "--run", "run-1", "--contract", "/tmp/store/events.yaml", "--bogus"),
            )
        }

        // The store refuses a missing path in its own words, and this verb
        // prints those words when the store is consulted. Their absence says
        // the store was never reached.
        assertFalse(result.stderr.contains("db not found"), result.stderr)
        assertFalse(result.stderr.contains("no directory for db"), result.stderr)
        assertFalse(result.stderr.contains("db has no event history"), result.stderr)
    }

    @Test
    fun `events verify refuses a value that names one of its own options`() {
        val result = capture {
            MainEventsVerifyCli.main(arrayOf("--db", "--run", "run-1", "--contract", "/tmp/store/events.yaml"))
        }

        assertEquals(2, result.exitCode)
        // The refusal names the option that ate the other one. It must not
        // blame the argument left over: that argument is not what was wrong.
        assertTrue(refusalOf(result).contains("--db"), result.stderr)
        assertTrue(refusalOf(result).contains("--run"), result.stderr)
        assertFalse(refusalOf(result).contains("run-1"), result.stderr)
    }

    @Test
    fun `events verify refuses a scope it cannot read`() {
        val result = capture {
            MainEventsVerifyCli.main(
                arrayOf("--db", "/tmp/store/events.db", "--run", "run-1", "--contract", "/tmp/store/events.yaml", "--scope", "bogus"),
            )
        }

        assertEquals(2, result.exitCode)
        assertTrue(refusalOf(result).contains("--scope"), result.stderr)
        assertTrue(refusalOf(result).contains("must be"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `events verify refuses a from-sequence it cannot read`() {
        val result = capture {
            MainEventsVerifyCli.main(
                arrayOf("--db", "/tmp/store/events.db", "--run", "run-1", "--contract", "/tmp/store/events.yaml", "--from-sequence", "abc"),
            )
        }

        assertEquals(2, result.exitCode)
        assertTrue(refusalOf(result).contains("--from-sequence"), result.stderr)
        assertTrue(refusalOf(result).contains("must be"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `events verify refuses an argument it was not asked about`() {
        val result = capture {
            MainEventsVerifyCli.main(
                arrayOf("--db", "/tmp/store/events.db", "--run", "run-1", "--contract", "/tmp/store/events.yaml", "extra"),
            )
        }

        assertEquals(2, result.exitCode)
        assertTrue(refusalOf(result).contains("extra"), result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `events verify says which of its options a request is missing`() {
        val missingDb = capture { MainEventsVerifyCli.main(arrayOf("--run", "run-1", "--contract", "/tmp/store/events.yaml")) }
        val missingRun = capture { MainEventsVerifyCli.main(arrayOf("--db", "/tmp/store/events.db", "--contract", "/tmp/store/events.yaml")) }
        val missingContract = capture {
            MainEventsVerifyCli.main(arrayOf("--db", "/tmp/store/events.db", "--run", "run-1"))
        }

        assertEquals(2, missingDb.exitCode)
        assertTrue(refusalOf(missingDb).contains("--db"), missingDb.stderr)
        assertTrue(refusalOf(missingDb).contains("required"), missingDb.stderr)

        assertEquals(2, missingRun.exitCode)
        assertTrue(refusalOf(missingRun).contains("--run"), missingRun.stderr)
        assertTrue(refusalOf(missingRun).contains("required"), missingRun.stderr)

        assertEquals(2, missingContract.exitCode)
        assertTrue(refusalOf(missingContract).contains("--contract"), missingContract.stderr)
        assertTrue(refusalOf(missingContract).contains("required"), missingContract.stderr)
    }

    @Test
    fun `the usage of events verify names only the forms it accepts`() {
        val result = capture { MainEventsVerifyCli.main(arrayOf("--bogus")) }

        val usage = result.stderr.substringAfter("Usage:")
        assertTrue(usage.contains("--from-sequence"), result.stderr)
        assertTrue(usage.contains("--scope"), result.stderr)
        // Two forms the usage used to name and the verb never accepted. Naming
        // a form the CLI refuses is a promise the CLI does not keep.
        assertFalse(usage.contains("--after-last-run-started"), result.stderr)
        assertFalse(usage.contains("--from-cursor"), result.stderr)
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * The refusal, without the usage line that follows it.
     *
     * The usage line names the options the verb accepts, so a row that read
     * the whole of stderr would be satisfied by the usage alone.
     */
    private fun refusalOf(result: Result): String = result.stderr.substringBefore("Usage:")

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
