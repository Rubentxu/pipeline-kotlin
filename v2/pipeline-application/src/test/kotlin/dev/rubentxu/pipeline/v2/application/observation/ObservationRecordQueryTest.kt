package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * OBS-D1: the read model speaks records, and `channel` stops being a dead parameter.
 *
 * ## What this pins
 *
 * ```text
 * CHANNEL-1  --channel stderr keeps stderr and nothing else
 * CHANNEL-2  several channels in ONE dimension union
 * CHANNEL-3  channel intersects with the other dimensions
 * TEXT-1     --grep reaches committed process bytes, not only script messages
 * RECORD-1   a record's channel is READ from its frame, never inferred
 * RECORD-2   an opaque stream id loses its address but keeps its channel
 * ```
 *
 * ## Harness fidelity
 *
 * HF0 Pure Contract. The compiled query and the record accessors read no clock, no filesystem and no
 * ambient state, so calling the production functions directly is faithful rather than a
 * reimplementation. The frames here are constructed rather than read from a store, which is a
 * limitation worth stating: this class proves the SELECTION, and the end-to-end claim that a real
 * `sh` run's stderr is reachable through this query belongs to the behavioural harness.
 *
 * ## Why these rows could not have existed before OBS-D
 *
 * `channel` was absent from the query, and its absence was justified by a claim that OBS-C2.3 made
 * false. These rows are the evidence that the producer is real — every `OutputFrame` below carries a
 * channel durably — and that the dimension now has one. A dimension with no producer is the
 * constitution's dead semantic parameter; a producer with no dimension is a capability nobody can
 * reach, which is the same defect pointing the other way.
 *
 * ## Mutation
 *
 * - `M-D1` (channel excludes rather than ignores) → make the channel dimension `return true` when
 *   the record carries no channel, i.e. treat "no channel" as "channel does not apply". This is the
 *   mutation that matters most, because it is the plausible wrong fix: it would keep events and
 *   stdout alive under `--channel stderr`.
 * - `M-D2` (AND across dimensions) → skip the channel check when other dimensions are set.
 * - `M-D3` (OR within a dimension) → intersect channels instead of unioning.
 * - `M-D4` (text reaches output) → return `null` from `textCarriedBy` for an output record.
 */
class ObservationRecordQueryTest {

    private val at: Instant = Instant.parse("2026-10-07T10:00:00Z")

    private fun frame(
        ordinal: Long,
        channel: OutputChannel,
        from: Long = 0,
        to: Long = 8,
        stream: String = "run-1/build/sh-0/${channel.token}",
    ) = OutputFrame(ordinal, OutputStreamId(stream), channel, from, to)

    private fun out(
        ordinal: Long,
        channel: OutputChannel,
        text: String,
        stream: String = "run-1/build/sh-0/${channel.token}",
    ): ObservationRecord.Output {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ObservationRecord.Output(
            OutputFrame(ordinal, OutputStreamId(stream), channel, 0, bytes.size.toLong()),
            bytes,
            text,
        )
    }

    private fun message(text: String): ObservationRecord =
        ObservationRecord.Event(EchoOutputCaptured("e-m", "run-1", 10, at, 0, text))

    private fun select(
        records: List<ObservationRecord>,
        query: ObservationQuery,
    ): List<ObservationRecord> {
        val compiled = compileQuery(query)
        assertTrue(compiled is SelectorCompileResult.Ok, "query must compile: $compiled")
        return selectRecords(records, (compiled as SelectorCompileResult.Ok).value)
    }

    @Test
    fun `CHANNEL-1 a channel filter keeps that channel and excludes everything else`() {
        val records = listOf(
            message("a script message"),
            out(1, OutputChannel.STDOUT, "compiling"),
            out(2, OutputChannel.STDERR, "warning: deprecated"),
        )

        val kept = select(records, ObservationQuery(channels = setOf(OutputChannel.STDERR)))

        assertEquals(1, kept.size, "got $kept")
        assertEquals(OutputChannel.STDERR, channelCarriedBy(kept.single()))
        assertEquals("warning: deprecated", textCarriedBy(kept.single()))
    }

    @Test
    fun `CHANNEL-2 several channels in ONE dimension union, exactly as kinds do`() {
        val records = listOf(
            out(1, OutputChannel.STDOUT, "compiling"),
            out(2, OutputChannel.STDERR, "warning"),
        )

        val kept = select(
            records,
            ObservationQuery(channels = setOf(OutputChannel.STDOUT, OutputChannel.STDERR)),
        )

        assertEquals(2, kept.size, "OR within a dimension is the existing rule, not a new one")
    }

    @Test
    fun `CHANNEL-3 channel intersects with the other dimensions`() {
        val records = listOf(
            out(1, OutputChannel.STDOUT, "alpha", stream = "run-1/build/sh-0/stdout"),
            out(2, OutputChannel.STDERR, "alpha", stream = "run-1/test/sh-1/stderr"),
        )

        // Text narrows to one record; channel then intersects, leaving stderr only.
        val kept = select(
            records,
            ObservationQuery(
                channels = setOf(OutputChannel.STDERR),
                lines = LineSelector.Only(listOf(TextSelector.Literal("alpha"))),
            ),
        )

        assertEquals(1, kept.size, "got $kept")
        assertEquals(
            "run-1/test/sh-1/stderr",
            (kept.single() as ObservationRecord.Output).frame.stream.value,
            "the surviving record must be the stderr one, not merely the only match",
        )
    }

    @Test
    fun `TEXT-1 grep reaches committed process bytes, not only script messages`() {
        val records = listOf(
            message("nothing of interest here"),
            out(1, OutputChannel.STDOUT, "AssertionError: expected 3"),
            out(2, OutputChannel.STDERR, "at build/Module.kt:42"),
        )

        val kept = select(
            records,
            ObservationQuery(lines = LineSelector.Only(listOf(TextSelector.Literal("AssertionError")))),
        )

        assertEquals(
            listOf("AssertionError: expected 3"),
            kept.mapNotNull { textCarriedBy(it) },
            "--grep must reach process output; before OBS-D it could only ever have matched script " +
                "messages, which made it a declared filter over a plane it could not see",
        )
    }

    @Test
    fun `RECORD-1 a record reads its channel from the frame rather than from its stream id`() {
        val stdout = out(1, OutputChannel.STDOUT, "x", stream = "run-1/build/sh-0/stdout")

        assertEquals(OutputChannel.STDOUT, stdout.channel)
        assertEquals(OutputChannel.STDOUT, channelCarriedBy(stdout))
        assertEquals("run-1", stdout.address?.runId)
        assertEquals("build/sh-0", stdout.address?.operationId)
    }

    @Test
    fun `RECORD-2 an opaque stream id loses its address but keeps its channel`() {
        // The pre-OBS-C2 shape: `transcript` is not a channel token, so no address is recoverable.
        val legacy = OutputFrame(
            ordinal = 7,
            stream = OutputStreamId("run-1/build/sh-0/transcript"),
            channel = OutputChannel.STDOUT,
            from = 0,
            to = 4,
        )
        val payload = "text".toByteArray(Charsets.UTF_8)
        val record = ObservationRecord.Output(legacy, payload, "text")

        assertNull(
            record.address,
            "a trailing token that is not a channel must NOT be parsed into an attribution: a partial " +
                "parse would invent a channel the stream never claimed",
        )
        assertEquals(
            OutputChannel.STDOUT,
            record.channel,
            "the channel still comes from the frame, which is where OBS-C2.3 put it — it does not " +
                "depend on the stream id being parseable",
        )
    }

    @Test
    fun `RECORD-3 an event record carries no channel, and null is not a match`() {
        assertNull(
            channelCarriedBy(message("hello")),
            "a semantic fact has no channel. Returning null rather than a default is what lets a " +
                "channel filter exclude it instead of guessing",
        )
    }

    @Test
    fun `RECORD-4 the identity query admits both families`() {
        val records = listOf<ObservationRecord>(
            message("hello"),
            out(1, OutputChannel.STDOUT, "compiling"),
            out(2, OutputChannel.STDERR, "warning"),
        )

        assertEquals(records, select(records, ObservationQuery()))
        assertTrue(ObservationQuery().isIdentity)
        assertTrue(
            !ObservationQuery(channels = setOf(OutputChannel.STDERR)).isIdentity,
            "setting the channel dimension must leave the identity state",
        )
    }

    @Test
    fun `selectObservations stays the event lane and drops nothing it used to keep`() {
        val events: List<DomainEvent> = listOf(
            EchoOutputCaptured("e-m", "run-1", 10, at, 0, "alpha"),
            EchoOutputCaptured("e-m2", "run-1", 11, at, 0, "beta"),
        )
        val compiled = compileQuery(ObservationQuery(lines = LineSelector.Only(listOf(TextSelector.Literal("beta")))))
        assertTrue(compiled is SelectorCompileResult.Ok)

        val kept = selectObservations(events, (compiled as SelectorCompileResult.Ok).value)

        assertEquals(1, kept.size, "got $kept")
        assertEquals("beta", textCarriedBy(kept.single()))
    }
}
