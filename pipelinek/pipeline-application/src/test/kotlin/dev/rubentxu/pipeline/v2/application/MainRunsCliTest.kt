package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The verbs of `pipelinek runs` own their options, and an option that belongs
 * to the other verb never reaches the run.
 *
 * **The guarantee.** `runs cancel` never simulates and `runs recover` never
 * records an operator's reason. Both refuse the option instead of ignoring
 * it, and the refusal says which option does not belong.
 *
 * **The capacity this serves.** An operator who asks a verb for something it
 * cannot do is TOLD, with the exit code the CLI already reserves for a
 * refusal. The two failures this replaces were silent, and each was the
 * opposite of what was asked: a cancel that was asked to simulate closed the
 * run for real and answered 0, and a recover that was given a reason dropped
 * the text and answered 0 as well. Both left the operator holding a green
 * exit code over a run in the state they had explicitly tried to avoid.
 *
 * **Why this instrument.** The decision is a pure function of the verb and
 * the arguments: no filesystem, no clock, no process, no run. The cheapest
 * instrument that can fail is a call to the parser and a reading of its
 * verdict. The alternative — a CLI test that runs a cancel — would need a
 * durable store, a run to cancel and a way to observe what was written, and
 * it would say THAT the verb closed a run, never WHICH of the two rules broke.
 *
 * **What this does not freeze.** That the refusal reaches the operator as
 * exit code 2 and lands on stderr; that is the CLI's own wiring, and it is
 * observed where the CLI runs.
 */
class MainRunsCliTest {

    @Test
    fun `cancel refuses a dry run instead of closing the run for real`() {
        // The cancel path never read the flag, so this used to close the run
        // and answer 0: an operator asking for a simulation got a closure.
        val error = requireNotNull(
            rejectionOf(MainRunsCli.parseCancelArguments(arrayOf("--db", "/tmp/run.db", "--dry-run"))),
        ) { "a cancel that was asked to simulate was accepted" }

        assertTrue(
            error.contains("--dry-run"),
            "the refusal does not name the option that does not belong: $error",
        )
    }

    @Test
    fun `recover refuses a reason instead of dropping the text`() {
        // The recover path never read the text, so this used to answer 0 over
        // a run that carries no trace of the reason the operator gave.
        val error = requireNotNull(
            rejectionOf(
                MainRunsCli.parseRecoverArguments(arrayOf("--db", "/tmp/run.db", "--reason", "because")),
            ),
        ) { "a recover that was given a reason was accepted" }

        assertTrue(
            error.contains("--reason"),
            "the refusal does not name the option that does not belong: $error",
        )
    }

    @Test
    fun `cancel keeps the reason it was given`() {
        val arguments = requireNotNull(
            acceptedOf(
                MainRunsCli.parseCancelArguments(arrayOf("--db", "/tmp/run.db", "--reason", "because")),
            ),
        ) { "a cancel with a reason was refused" }
        assertEquals("because", arguments.reason, "the reason was not kept verbatim")
        assertEquals("/tmp/run.db", arguments.dbPath, "the database was not kept")
    }

    @Test
    fun `recover keeps the dry run it was asked for`() {
        val arguments = requireNotNull(
            acceptedOf(
                MainRunsCli.parseRecoverArguments(arrayOf("--db", "/tmp/run.db", "--dry-run")),
            ),
        ) { "a recover that was asked to simulate was refused" }
        assertTrue(arguments.dryRun, "the simulation was not kept")
        assertEquals("/tmp/run.db", arguments.dbPath, "the database was not kept")
    }

    @Test
    fun `cancel without a reason has no reason`() {
        // The cancel falls back to "the operator asked for it", so the absence
        // of a reason is a legitimate reading and not a refusal.
        val arguments = requireNotNull(
            acceptedOf(MainRunsCli.parseCancelArguments(arrayOf("--db", "/tmp/run.db"))),
        ) { "a cancel without a reason was refused" }
        assertNull(arguments.reason, "a reason was invented")
    }

    @Test
    fun `recover without a dry run writes`() {
        val arguments = requireNotNull(
            acceptedOf(MainRunsCli.parseRecoverArguments(arrayOf("--db", "/tmp/run.db"))),
        ) { "a recover without a dry run was refused" }
        assertFalse(arguments.dryRun, "a recover that was not asked to simulate is a simulation")
    }

    @Test
    fun `an option nobody wrote is refused by both verbs`() {
        val cancelled = rejectionOf(MainRunsCli.parseCancelArguments(arrayOf("--what", "ever")))
        val recovered = rejectionOf(MainRunsCli.parseRecoverArguments(arrayOf("--what", "ever")))

        assertNotNull(cancelled, "an unknown option was accepted by cancel")
        assertNotNull(recovered, "an unknown option was accepted by recover")
    }

    @Test
    fun `an option without its value is refused by both verbs`() {
        val cancelled = rejectionOf(MainRunsCli.parseCancelArguments(arrayOf("--db")))
        val recovered = rejectionOf(MainRunsCli.parseRecoverArguments(arrayOf("--db")))

        assertNotNull(cancelled, "a database without a value was accepted by cancel")
        assertNotNull(recovered, "a database without a value was accepted by recover")
    }

    /** The refusal, when there was one. */
    private fun rejectionOf(parsed: MainRunsCli.OptionParse<*>): String? = when (parsed) {
        is MainRunsCli.OptionParse.Rejected -> parsed.error
        is MainRunsCli.OptionParse.Parsed -> null
    }

    /** The options, when they were accepted. */
    private fun <T> acceptedOf(parsed: MainRunsCli.OptionParse<T>): T? = when (parsed) {
        is MainRunsCli.OptionParse.Parsed -> parsed.value
        is MainRunsCli.OptionParse.Rejected -> null
    }
}
