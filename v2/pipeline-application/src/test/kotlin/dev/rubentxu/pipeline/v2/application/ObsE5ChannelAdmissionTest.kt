package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationQuery
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.application.observation.SelectorCompileResult
import dev.rubentxu.pipeline.v2.application.observation.compileQuery
import dev.rubentxu.pipeline.v2.application.observation.selectRecords
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * OBS-E5a — `--channel` reaches the query the run is filtered by.
 *
 * ## What was broken, and why no row noticed
 *
 * `ObservationQuery.channels` was implemented, compiled and consumed by
 * `CompiledObservationQuery.accepts` from OBS-D onwards, and the entire surrounding suite talks about
 * `--channel stderr` as a real surface: `ObsC23NoChannelFusionFitnessTest` exists to keep
 * `redirectErrorStream(true)` from making channel attribution unrecoverable, and
 * `ObsC23ChannelSeparationUatTest` exists to prove the stderr stream really holds the stderr bytes.
 *
 * None of that was reachable. `CliParser` had no `--channel` option, `buildObservationQuery` had no
 * parameter for it, and the field's own KDoc explained why in a sentence that had become false:
 * "no durable carrier exists for stdout vs stderr, so offering the dimension would be a filter that
 * cannot filter". So the producer existed, the consumer existed, the tests claimed the surface
 * existed, and the flag could not be typed. Everything was green.
 *
 * The unit tests above could not see it because they construct `ObservationQuery(channels = ...)`
 * directly — they start from the object the flag is supposed to produce. A whole dimension was
 * certified by tests that bypassed the only thing under suspicion.
 *
 * ## What these rows pin
 *
 * CH-ADM-5 is the one that matters: it starts from `argv` and ends at a record being kept or
 * dropped. Every other row here could stay green while the dimension was collected and thrown away.
 *
 * ```text
 * CH-ADM-1  the flag reaches the assembled query
 * CH-ADM-2  a flag with no value is refused before anything runs
 * CH-ADM-2b a script sitting in the value position is named back, not swallowed
 * CH-ADM-2c a flag after the script is refused as trailing, not ignored
 * CH-ADM-3  an unknown token names itself AND the vocabulary
 * CH-ADM-4  repeating the flag unions, per AND-across / OR-within
 * CH-ADM-5  argv -> query -> accepts actually excludes a stdout record
 * ```
 *
 * Mutations, one per claim, with the rows each one actually turned RED — measured, not predicted,
 * because the first draft of this list was wrong and said M-C1 touched "only" two rows when it
 * touched four:
 *
 * - `M-C1` (the dimension is decorative) → stop passing `state.channels` into
 *   `buildObservationQuery` at both call sites. REDS **CH-ADM-1, CH-ADM-4, CH-ADM-5** and
 *   `a channel makes the query non-identity`. Four rows, not two: an empty `channels` also breaks
 *   the union row and the identity row, because a flag that never reaches the query leaves nothing
 *   behind. That is the point of the attribution — the original defect would have stayed invisible
 *   to any single one of these rows.
 * - `M-C2` (unknown tokens accepted silently) → resolve an unrecognised token to `STDERR` instead of
 *   refusing. REDS **CH-ADM-3 and CH-ADM-2b**. A filter that quietly keeps nothing, or quietly
 *   becomes a different filter, is the trap `EmptyTextFilter` already exists to avoid.
 * - `M-C3` (intersect instead of union) → clear before adding. REDS **CH-ADM-4 alone**.
 *
 * ## The harness caught itself lying once, while measuring these
 *
 * The first `M-C3` assigned to the `val MutableSet`, which does not compile. Gradle then ran the
 * PREVIOUSLY COMPILED classes and left the previous run's XML in `test-results`, so the mutation was
 * reported with M-C2's rows. A compile failure is not a RED, and neither is stale evidence — so the
 * mutation runner now refuses to report anything unless the build was green, which is the same law
 * that forbids reading `BUILD SUCCESSFUL` with everything `UP-TO-DATE` as a result.
 */
class ObsE5ChannelAdmissionTest {

    private val script = "/path/to/script.kts"

    private fun parsed(vararg args: String): CliFlags {
        val result = CliParser.parse(arrayOf("run", *args, script))
        assertTrue(result is CliParseResult.Parsed, "expected admission, got: $result")
        return (result as CliParseResult.Parsed).flags
    }

    @Test
    fun `CH-ADM-1 the flag reaches the assembled query`() {
        val flags = parsed("--channel", "stderr")

        assertEquals(
            setOf(OutputChannel.STDERR),
            flags.query.channels,
            "the dimension was collected but never handed to the query, which is the defect this " +
                "row exists to catch",
        )
    }

    @Test
    fun `CH-ADM-2 a channel flag with no value is refused before anything runs`() {
        val result = CliParser.parse(arrayOf("run", "--channel"))

        assertEquals(CliParseResult.Rejected(CliError.MissingOptionValue("--channel")), result)
    }

    @Test
    fun `CH-ADM-2c a channel flag after the script is refused as trailing, not ignored`() {
        val result = CliParser.parse(arrayOf("run", script, "--channel", "stderr"))

        assertEquals(CliParseResult.Rejected(CliError.TrailingOption("--channel")), result)
    }

    @Test
    fun `CH-ADM-2b a script in the value position is named, not silently swallowed`() {
        // getopt's ambiguity, kept rather than papered over: `--channel` requires a value, so the
        // token after it IS the value. A user who forgets the flag value gets their script named
        // back at them by name instead of a bare "missing script", which is the difference between
        // a diagnosis and a guess.
        val result = CliParser.parse(arrayOf("run", "--channel", script))

        assertEquals(CliParseResult.Rejected(CliError.InvalidChannel(script)), result)
    }

    @Test
    fun `CH-ADM-3 an unknown token names itself and the vocabulary`() {
        val result = CliParser.parse(arrayOf("run", "--channel", "STDERR", script))

        assertEquals(CliParseResult.Rejected(CliError.InvalidChannel("STDERR")), result)
        // The message is the whole point for someone who typed the wrong thing: refusing without
        // saying what would have been accepted makes the next attempt a guess.
        val message = (result as CliParseResult.Rejected).error.toString()
        assertTrue(message.contains("STDERR"), "must echo the rejected token: $message")
        assertTrue(message.contains("stderr"), "must name an accepted token: $message")
        assertTrue(message.contains("stdout"), "must name an accepted token: $message")
    }

    @Test
    fun `CH-ADM-4 repeating the flag unions, per AND across and OR within`() {
        val flags = parsed("--channel", "stdout", "--channel", "stderr")

        assertEquals(
            setOf(OutputChannel.STDOUT, OutputChannel.STDERR),
            flags.query.channels,
            "two values in one dimension union, exactly as two --kind values do",
        )
    }

    @Test
    fun `CH-ADM-5 argv to accepts excludes a stdout record`() {
        val flags = parsed("--channel", "stderr")
        val compiled = compileQuery(flags.query)
        assertTrue(compiled is SelectorCompileResult.Ok, "query must compile: $compiled")

        val stderrBytes = Fixture.output(OutputChannel.STDERR, "warning: deprecated")
        val stdoutBytes = Fixture.output(OutputChannel.STDOUT, "compiling")
        val structural = Fixture.message("a script message")

        val kept = selectRecords(
            listOf(stderrBytes, stdoutBytes, structural),
            (compiled as SelectorCompileResult.Ok).value,
        )

        assertEquals(
            listOf(stderrBytes),
            kept,
            "only the stderr bytes should survive. A StageStarted carries no channel, and the " +
                "stated rule is that a dimension a record cannot satisfy EXCLUDES it — so keeping " +
                "the structural event here would be the plausible wrong fix, not the right one.",
        )
    }

    /** Built here rather than borrowed, so the row does not lean on another test's helpers. */
    private object Fixture {
        private val at: Instant = Instant.parse("2026-10-07T10:00:00Z")

        fun output(channel: OutputChannel, text: String): ObservationRecord {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val stream = "run-1/build/sh-0/${channel.token}"
            return ObservationRecord.Output(
                OutputFrame(1L, OutputStreamId(stream), channel, 0, bytes.size.toLong()),
                bytes,
                text,
            )
        }

        fun message(text: String): ObservationRecord =
            ObservationRecord.Event(EchoOutputCaptured("e-m", "run-1", 10, at, 0, text))
    }
}

/** The identity query must not acquire a channel by accident. */
class ObsE5ChannelDefaultTest {

    @Test
    fun `no channel flag means the channel dimension is not set`() {
        val result = CliParser.parse(arrayOf("run", "/path/to/script.kts"))
        assertTrue(result is CliParseResult.Parsed, "got: $result")
        val query = (result as CliParseResult.Parsed).flags.query

        assertEquals(emptySet<OutputChannel>(), query.channels)
        assertTrue(query.isIdentity, "a default parse must stay the identity query: $query")
    }

    @Test
    fun `a channel makes the query non-identity, which is what stops it being discarded`() {
        val result = CliParser.parse(arrayOf("run", "--channel", "stderr", "/path/to/script.kts"))
        assertTrue(result is CliParseResult.Parsed, "got: $result")
        val query: ObservationQuery = (result as CliParseResult.Parsed).flags.query

        assertTrue(!query.isIdentity, "a query that filters by channel is not the identity query")
    }
}
