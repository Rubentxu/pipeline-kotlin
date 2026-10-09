package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventPage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1c / AUD-04 + AUD-05 — the events CLI's argument refusal and its exit-code contract.
 *
 * ## What this pins
 *
 * **AUD-04.** `MainEventsCli` used to treat any unrecognised token as a positional run id and
 * silently drop the rest: `--bogus` was ignored, and a second positional was discarded while the
 * first was kept. The command then ran a DIFFERENT read than the one typed and exited 0. These
 * tests require an explicit, typed refusal naming the offending token, with a non-zero status.
 *
 * **AUD-05.** The command returned `0` on both drain outcomes, including `Stalled`, and nothing
 * recorded that as a decision. [MainEventsCli.exitCodeFor] is now the single exhaustive mapping
 * `EventPageDrain.Outcome -> Int`, and the contract is asserted here so it stops being an accident.
 *
 * ## Fidelity and hermeticity
 *
 * [MainEventsCli.main] is the production entry point, run in process. The argument-refusal rows do
 * not touch a database (the parser refuses before any read), so no fixture is needed. `@Timeout`
 * bounds every row; both process streams are captured and restored in `finally`; nothing is written
 * to disk, the network or `/tmp`.
 *
 * ## Mutations that must kill this
 *
 * - restoring the old `else -> if (!args[i].startsWith("--") && runId == null) runId = args[i]`
 *   makes both refusal rows see exit 0 instead of 2;
 * - changing `exitCodeFor`'s `Stalled` cell to any non-zero value fails the contract row.
 */
@Timeout(60)
@DisplayName("B1c — la CLI de eventos rechaza opciones desconocidas y fija su contrato de exit code")
class MainEventsCliArgumentContractTest {

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun cli(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout)
                    System.setErr(stderr)
                    MainEventsCli.main(arrayOf(*args))
                }
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        return CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `an unknown option is refused and named, not ignored`() {
        val result = cli("--db", "events.sqlite", "run-1", "--bogus")

        assertEquals(2, result.exitCode, "an unknown option must not be a silent success")
        assertTrue(
            result.stderr.contains("--bogus") && result.stderr.contains("unknown option"),
            "the refusal must name the offending option; stderr:\n${result.stderr}",
        )
    }

    @Test
    fun `a second positional argument is refused and named, not dropped`() {
        val result = cli("--db", "events.sqlite", "run-1", "run-2")

        assertEquals(2, result.exitCode, "a second run id must not be silently discarded")
        assertTrue(
            result.stderr.contains("run-2") && result.stderr.contains("extra argument"),
            "the refusal must name the extra argument; stderr:\n${result.stderr}",
        )
    }

    /**
     * The exit-code contract, pinned as a pure mapping.
     *
     * `Stalled` is unreachable against both real stores today, which is exactly why its status must
     * be stated and tested rather than left to whatever `main` happens to return: the day the guard
     * fires, the contract already says what the process status means.
     */
    @Test
    fun `the drain outcome maps to the documented exit code`() {
        val answered = EventPageDrain.Outcome.Answered(
            EventPage(
                envelopes = emptyList(),
                nextCursor = EventCursor("run-1", 1L),
                hasMore = false,
                refusals = emptyList(),
            ),
        )
        val stalled = EventPageDrain.Outcome.Stalled(
            page = EventPage(
                envelopes = emptyList(),
                nextCursor = EventCursor("run-1", 1L),
                hasMore = true,
                refusals = emptyList(),
            ),
            stuckAfter = null,
        )

        assertEquals(0, MainEventsCli.exitCodeFor(answered), "an answered observation is a success")
        assertEquals(
            0,
            MainEventsCli.exitCodeFor(stalled),
            "a stalled page still completed a read-only observation and reports the stall on stderr; " +
                "the status stays 0 by contract",
        )
    }
}
