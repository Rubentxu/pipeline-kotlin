package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.LineSelector
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.ObservationView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fail-closed contract for the ADR-0088 observation flags.
 *
 * The rows below are the parser boundary only: they prove what the CLI ACCEPTS
 * and REFUSES before any process, store or journal exists. They do not certify
 * what `run` prints — that is the renderer's job, and the live-console slice's.
 *
 * ## Harness fidelity
 *
 * HF0 Pure Contract. [CliParser.parse] is a pure function over `Array<String>`;
 * no clock, no filesystem, no ambient state. Calling the production entry point
 * directly is therefore faithful, not a reimplementation.
 *
 * ## Mutation
 *
 * - `TRAIL-1` (trailing options rejected) → drop the `args.drop(index + 1)` scan.
 * - `VIEW-1` (unavailable view refused, not downgraded) → treat `Unavailable`
 *   like `Invalid` and fall back to the default.
 * - `DEFAULT-1` (human by default) → default `view`/`format` to JSON.
 */
class ObservationCliContractTest {

    private fun parsed(args: Array<String>): CliFlags {
        val result = CliParser.parse(args)
        assertTrue(result is CliParseResult.Parsed, "expected a parse, got $result")
        return (result as CliParseResult.Parsed).flags
    }

    private fun rejected(args: Array<String>): CliError {
        val result = CliParser.parse(args)
        assertTrue(result is CliParseResult.Rejected, "expected a rejection, got $result")
        return (result as CliParseResult.Rejected).error
    }

    @Test
    fun `DEFAULT-1 run is human text and the default view`() {
        val flags = parsed(arrayOf("run", "demo.kts"))

        assertEquals(ObservationView.NORMAL, flags.view)
        assertEquals(ObservationFormat.TEXT, flags.format)
    }

    @Test
    fun `VIEW-2 an explicit view is honoured`() {
        assertEquals(
            ObservationView.EVENTS,
            parsed(arrayOf("run", "--view", "events", "demo.kts")).view,
        )
        assertEquals(
            ObservationView.QUIET,
            parsed(arrayOf("run", "--view", "QUIET", "demo.kts")).view,
        )
    }

    @Test
    fun `VIEW-1 a known but undeliverable view is REFUSED, never downgraded`() {
        // `full` interleaves two planes with no common order. Silently serving
        // `normal` instead would tell the operator they observed something they
        // did not observe.
        val error = rejected(arrayOf("run", "--view", "full", "demo.kts"))

        assertTrue(error is CliError.UnavailableView, "got $error")
        assertEquals(ObservationView.FULL, (error as CliError.UnavailableView).view)
    }

    @Test
    fun `VIEW-3 console view is refused by run and points at its own verb`() {
        val error = rejected(arrayOf("run", "--view", "console", "demo.kts"))

        assertTrue(error is CliError.UnavailableView, "got $error")
        assertTrue(error.toString().contains("pipeline console"), "message must name the verb")
    }

    @Test
    fun `VIEW-4 an unknown view is rejected`() {
        assertTrue(rejected(arrayOf("run", "--view", "verbose", "demo.kts")) is CliError.InvalidView)
    }

    @Test
    fun `FORMAT-1 json is opt-in and reachable`() {
        assertEquals(
            ObservationFormat.JSON,
            parsed(arrayOf("run", "--format", "json", "demo.kts")).format,
        )
        assertEquals(
            ObservationFormat.JSON_LINES,
            parsed(arrayOf("run", "--format", "jsonl", "demo.kts")).format,
        )
    }

    @Test
    fun `FORMAT-2 an unknown format is rejected`() {
        assertTrue(rejected(arrayOf("run", "--format", "yaml", "demo.kts")) is CliError.InvalidFormat)
    }

    @Test
    fun `FORMAT-3 a missing flag value is rejected`() {
        assertTrue(rejected(arrayOf("run", "--format")) is CliError.MissingOptionValue)
        assertTrue(rejected(arrayOf("run", "--view")) is CliError.MissingOptionValue)
    }

    @Test
    fun `TRAIL-1 an option after the script path is rejected, not discarded`() {
        // Before this contract the scan stopped at the first non-`--` token, so
        // this command ran and printed the DEFAULT output: the user believed
        // `--format json` was applied and it was not.
        val error = rejected(arrayOf("run", "demo.kts", "--format", "json"))

        assertTrue(error is CliError.TrailingOption, "got $error")
        assertEquals("--format", (error as CliError.TrailingOption).value)
    }

    @Test
    fun `TRAIL-2 options before the script path still work`() {
        val flags = parsed(arrayOf("run", "--view", "events", "--format", "json", "demo.kts"))

        assertEquals("demo.kts", flags.scriptPath)
        assertEquals(ObservationView.EVENTS, flags.view)
        assertEquals(ObservationFormat.JSON, flags.format)
    }

    @Test
    fun `TRAIL-3 validate accepts the same observation flags`() {
        val flags = parsed(arrayOf("validate", "--view", "quiet", "demo.kts"))

        assertEquals(CliCommand.VALIDATE, flags.command)
        assertEquals(ObservationView.QUIET, flags.view)
    }

    // ---- filters ---------------------------------------------------------

    @Test
    fun `GREP-1 repeated --grep becomes an OR group, not a last-wins overwrite`() {
        val flags = parsed(arrayOf("run", "--grep", "alpha", "--grep", "beta", "demo.kts"))

        val lines = flags.query.lines
        assertTrue(lines is LineSelector.Only, "got $lines")
        assertEquals(2, (lines as LineSelector.Only).selectors.size)
    }

    @Test
    fun `GREP-2 --grep-invert normalises to Except, not a negate flag`() {
        val flags = parsed(arrayOf("run", "--grep", "noise", "--grep-invert", "demo.kts"))

        assertTrue(flags.query.lines is LineSelector.Except, "got ${flags.query.lines}")
    }

    @Test
    fun `GREP-3 --grep-invert without --grep is refused`() {
        assertTrue(
            rejected(arrayOf("run", "--grep-invert", "demo.kts")) is CliError.InvertWithoutGrep,
        )
    }

    @Test
    fun `GREP-4 an empty pattern is refused rather than matching everything`() {
        val error = rejected(arrayOf("run", "--grep", "", "demo.kts"))

        assertTrue(error is CliError.EmptyTextFilter, "got $error")
    }

    @Test
    fun `GREP-5 an uncompilable regex is refused before any effect`() {
        val error = rejected(arrayOf("run", "--grep-regex", "([unclosed", "demo.kts"))

        assertTrue(error is CliError.InvalidQuery, "got $error")
        assertTrue((error as CliError.InvalidQuery).reason.contains("invalid regular expression"))
    }

    @Test
    fun `STAGE-1 a stage dimension reaches the query`() {
        val flags = parsed(arrayOf("run", "--stage", "build", "demo.kts"))

        assertEquals(setOf("build"), flags.query.stageNames)
    }

    @Test
    fun `KIND-1 repeated --kind unions instead of overwriting`() {
        // `pipeline events` has the opposite defect today
        // (MainEventsCli.kt:34-41 overwrites, :62-70 drops --subject when --kind
        // is present). The observation path must not reproduce it.
        val flags = parsed(arrayOf("run", "--kind", "RunStarted", "--kind", "RunFinished", "demo.kts"))

        assertEquals(setOf("RunStarted", "RunFinished"), flags.query.eventKinds)
    }

    @Test
    fun `QUERY-1 the identity query is the default`() {
        val flags = parsed(arrayOf("run", "demo.kts"))

        assertTrue(flags.query.isIdentity)
        assertEquals(LineSelector.All, flags.query.lines)
    }
}
