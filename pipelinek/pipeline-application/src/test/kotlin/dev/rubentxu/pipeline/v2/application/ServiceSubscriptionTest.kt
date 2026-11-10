package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputPage
import dev.rubentxu.pipeline.v2.application.observation.ObservationOutputRead
import dev.rubentxu.pipeline.v2.application.observation.ObservationRecord
import dev.rubentxu.pipeline.v2.application.observation.RunPlane
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventRecordRead
import dev.rubentxu.pipeline.v2.events.EventRecordSlice
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.UndecodableReason
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventQuery
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadPort
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadRefusal
import dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.OutputTailState
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceSubscriptionRequest

/**
 * The streets of the local service.
 *
 * ## The guarantee under trial
 *
 * A street delivers RECORDS and ends ONCE, and the end says why. The end
 * says that the run's terminal fact was observed —never that the run
 * succeeded, which is another authority's answer— and a street that cannot
 * observe that fact does NOT end: it refuses, naming the fact that stopped
 * it.
 *
 * ## The capacity this serves
 *
 * A client that reconnects from a cursor must be able to tell three cases
 * apart: the run is still going, the run is over and the client is resumed
 * past its last row, and the run is over and there is nothing left to
 * deliver. A street that cannot tell them apart either hangs for ever or
 * ends on a fact nobody observed, and both are the failure a supervisor
 * cannot recover from.
 *
 * ## Why this instrument
 *
 * The streets are pure decisions over what the durable authorities answered:
 * no socket, no store, no clock. The cheapest instrument that can fail is
 * the fake authority and the lines the street wrote, so that is what this
 * lane uses.
 *
 * ## What this does not freeze
 *
 * The transport that carries the lines, and whether the real authorities
 * answer the way these fakes say they do. That is what the integration lane
 * is for.
 */
class ServiceSubscriptionTest {

    @Test
    fun `the events street delivers every row and ends with the terminal fact`() {
        val port = ScriptedEventPort { _ ->
            page(
                listOf(
                    EventRecordRead.Decoded(runStarted(1L)),
                    EventRecordRead.Decoded(runFinished(2L)),
                ),
                lastSequence = 2L,
            )
        }
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = FakeLanes(), events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
        )

        val frames = connection.frames()
        assertEquals(3, frames.size, "the street delivered two records and ended: $frames")
        assertEquals("record", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("record", frames[1]["kind"]?.jsonPrimitive?.content)
        assertEquals("end", frames[2]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "terminal-observed",
            frames[2]["reason"]?.jsonPrimitive?.content,
            "the end says that the terminal fact was observed, and nothing more",
        )
        assertEquals(
            EventCursor("run-1", 2L).encode(),
            frames[2]["cursor"]?.jsonPrimitive?.content,
            "the end carries the position the street stood at",
        )
    }

    @Test
    fun `a client resumed past the terminal row still gets the end of the street`() {
        // The reconnect of a run that already finished: the client persisted the
        // cursor of the last row it processed, so the store has nothing to
        // deliver and the terminal row will never be delivered again. A street
        // that waits for that row waits for ever.
        val resumed = EventCursor("run-1", 5L)
        val port = ScriptedEventPort { _ -> page(emptyList(), lastSequence = 5L) }
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(
            lanes = FakeLanes(runPlane = RunPlane.Completed),
            events = port,
        )

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", resumed),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street ended without delivering a record: $frames")
        assertEquals("end", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", frames[0]["reason"]?.jsonPrimitive?.content)
        assertEquals(
            resumed.encode(),
            frames[0]["cursor"]?.jsonPrimitive?.content,
            "the end carries the position the client resumed from",
        )
    }

    @Test
    fun `a run that is still going leaves the street open`() {
        val port = ScriptedEventPort { call ->
            if (call == 0) page(emptyList(), lastSequence = 0L) else error("gone")
        }
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(
            lanes = FakeLanes(runPlane = RunPlane.Live),
            events = port,
        )

        val failure = runCatching {
            subscription.serve(
                connection,
                ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
            )
        }.exceptionOrNull()

        assertTrue(
            failure is IllegalStateException,
            "the street was still reading when the store went away",
        )
        assertTrue(
            connection.frames().none { it["kind"]?.jsonPrimitive?.content == "end" },
            "a run that is still going does not end the street: ${connection.frames()}",
        )
    }

    @Test
    fun `a run nobody knows is refused by name`() {
        val port = ScriptedEventPort { _ ->
            EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun("run-1"))
        }
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = FakeLanes(), events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size)
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "unknown-run",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a cursor past the tail is refused by name`() {
        val port = ScriptedEventPort { _ ->
            EventRecordReadResult.Refused(
                EventRecordReadRefusal.CursorBeyondTail("run-1", 40L, 12L),
            )
        }
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = FakeLanes(), events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size)
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        val refusal = frames[0]["refusal"]?.jsonObject
        assertEquals("cursor-beyond-tail", refusal?.get("kind")?.jsonPrimitive?.content)
        assertEquals("40", refusal?.get("requestedSequence")?.jsonPrimitive?.content)
        assertEquals("12", refusal?.get("tailSequence")?.jsonPrimitive?.content)
    }

    @Test
    fun `a row this binary cannot read travels as a record and does not stop the street`() {
        val port = ScriptedEventPort { _ ->
            page(
                listOf(
                    EventRecordRead.Undecodable(
                        sequence = 1L,
                        kind = "PluginEvent",
                        eventId = "event-9",
                        reason = UndecodableReason.UnknownKind("PluginEvent"),
                    ),
                    EventRecordRead.Decoded(runFinished(2L)),
                ),
                lastSequence = 2L,
            )
        }
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = FakeLanes(), events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
        )

        val frames = connection.frames()
        assertEquals(3, frames.size, "the unreadable row travelled and the street carried on")
        assertEquals("undecodable", frames[0]["record"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("1", frames[0]["record"]?.jsonObject?.get("sequence")?.jsonPrimitive?.content)
        assertEquals("unknown-kind", frames[0]["record"]?.jsonObject?.get("reason")?.jsonPrimitive?.content)
        assertEquals("end", frames[2]["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the events street resumes from the cursor the client named`() {
        val resumed = EventCursor("run-1", 4L)
        val seen = mutableListOf<EventCursor?>()
        val port = ScriptedEventPort { _ ->
            page(emptyList(), lastSequence = 4L).also { seen.add(resumed) }
        }
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(
            lanes = FakeLanes(runPlane = RunPlane.Completed),
            events = port,
        )

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", resumed),
        )

        assertEquals(listOf(resumed), seen, "the street read from the cursor the client named")
    }

    @Test
    fun `the console street delivers the frames and ends when the run is over`() {
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(
                    records = listOf(frame(0L, "hello")),
                    lastOrdinal = 0L,
                    moreFrames = false,
                ),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            runPlane = RunPlane.Completed,
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(2, frames.size, "the street delivered the frame and ended: $frames")
        assertEquals("record", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "out-frame-ord-v1:0",
            frames[0]["cursor"]?.jsonPrimitive?.content,
            "the record carries the position it can be resumed from",
        )
        assertEquals("hello", frames[0]["record"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals("end", frames[1]["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", frames[1]["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a console that the plane refuses is refused with the plane's own word`() {
        val lanes = FakeLanes(
            output = ObservationOutputRead.Refused(
                OutputRefusal.RetentionGap(OutputStreamId("run-1/op-1/stdout"), 0L..10L, 4L),
            ),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size)
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        val refusal = frames[0]["refusal"]?.jsonObject
        assertEquals("output-plane-refused", refusal?.get("kind")?.jsonPrimitive?.content)
        assertEquals(
            "retention-gap",
            refusal?.get("reason")?.jsonPrimitive?.content,
            "the refusal carries the plane's own word, not a paraphrase of it",
        )
    }

    @Test
    fun `a console that does not exist is refused by name`() {
        val lanes = FakeLanes(output = null)
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size)
        assertEquals(
            "not-configured-output-plane",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `bytes that are not text travel whole and the channel stays distinguishable`() {
        // The process wrote bytes that are not valid UTF-8. A projection that
        // replaced them with U+FFFD would hand the client a character the
        // process never wrote, so the street carries the bytes in base64 and
        // says explicitly that there is no text.
        val raw = byteArrayOf(0xFF.toByte(), 0x41, 0xC3.toByte())
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(
                    records = listOf(rawFrame(0L, raw, OutputChannel.STDOUT), rawFrame(1L, raw, OutputChannel.STDERR)),
                    lastOrdinal = 1L,
                    moreFrames = false,
                ),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            runPlane = RunPlane.Completed,
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val records = connection.frames().filter { it["kind"]?.jsonPrimitive?.content == "record" }
        assertEquals(2, records.size, "both frames travelled: ${connection.frames()}")
        records.forEach { frame ->
            val record = frame["record"]?.jsonObject
            assertEquals(
                "null",
                record?.get("text").toString(),
                "the bytes are not text, and the record says so instead of inventing a text",
            )
            val carried = java.util.Base64.getDecoder().decode(record?.get("base64")?.jsonPrimitive?.content)
            assertTrue(
                raw.contentEquals(carried),
                "the bytes travelled whole: ${raw.toList()} != ${carried.toList()}",
            )
        }
        assertEquals(
            "stdout",
            records[0]["record"]?.jsonObject?.get("channel")?.jsonPrimitive?.content,
        )
        assertEquals(
            "stderr",
            records[1]["record"]?.jsonObject?.get("channel")?.jsonPrimitive?.content,
            "the two channels stay distinguishable, so a client can tell them apart",
        )
    }

    @Test
    fun `the end of a street never carries a success`() {
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(
            lanes = FakeLanes(runPlane = RunPlane.Completed),
            events = ScriptedEventPort { _ -> page(emptyList(), 0L) },
        )

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
        )

        val frames = connection.frames()
        assertFalse(
            frames.any { frame -> frame.containsKey("success") || frame.containsKey("ok") },
            "a street that ended does not report a success: $frames",
        )
    }

    /**
     * A page with [records] and the position that follows them.
     */
    private fun page(records: List<EventRecordRead>, lastSequence: Long): EventRecordReadResult.Page =
        EventRecordReadResult.Page(
            EventRecordSlice(
                records = records,
                nextCursor = EventCursor("run-1", lastSequence),
                hasMore = false,
            ),
        )

    /**
     * A frame of the run's console.
     */
    private fun frame(ordinal: Long, text: String): ObservationRecord.Output {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ObservationRecord.Output(
            frame = OutputFrame(
                ordinal = ordinal,
                stream = OutputStreamId("run-1/op-1/stdout"),
                channel = OutputChannel.STDOUT,
                from = 0L,
                to = bytes.size.toLong(),
            ),
            bytes = bytes,
            text = text,
        )
    }

    /**
     * A frame of the run's console that carries raw bytes.
     *
     * The `text` it carries is the LOSSY view —the replacement character a
     * human reader would see— and the projection is expected to ignore it and
     * decide from the bytes, because a replacement character on the wire is a
     * character the process never wrote.
     */
    private fun rawFrame(ordinal: Long, bytes: ByteArray, channel: OutputChannel): ObservationRecord.Output =
        ObservationRecord.Output(
            frame = OutputFrame(
                ordinal = ordinal,
                stream = OutputStreamId("run-1/op-1/stdout"),
                channel = channel,
                from = 0L,
                to = bytes.size.toLong(),
            ),
            bytes = bytes,
            text = "�",
        )

    private fun runStarted(sequence: Long): DomainEvent = RunStarted(
        eventId = "event-$sequence",
        runId = "run-1",
        sequence = sequence,
        occurredAt = Instant.EPOCH,
        scriptPath = "/tmp/pipeline.kts",
    )

    private fun runFinished(sequence: Long): DomainEvent = RunFinished(
        eventId = "event-$sequence",
        runId = "run-1",
        sequence = sequence,
        occurredAt = Instant.EPOCH,
        outcome = "success",
        diagnostics = emptyList(),
    )

    /**
     * The read port, scripted.
     *
     * The script is a function of the call number, so a lane can answer one
     * round and then stop answering —which is how a test says "the run is
     * still going" without a clock.
     */
    private class ScriptedEventPort(
        private val answer: (Int) -> EventRecordReadResult,
    ) : EventRecordReadPort {
        private var call = 0
        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult {
            val index = call
            call++
            return answer(index)
        }
    }

    /**
     * The lanes, faked.
     */
    private class FakeLanes(
        private val output: ObservationOutputRead? = null,
        private val tails: List<OutputTailState?> = emptyList(),
        private val runPlane: RunPlane = RunPlane.Live,
    ) : ObserveLanes {
        override val hasEventStore: Boolean get() = true
        override val hasOutputPlane: Boolean get() = true
        override fun eventsOf(runId: String): Sequence<DomainEvent> = emptySequence()
        override fun runPlaneOf(runId: String): RunPlane = runPlane
        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int) = output
        override fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead? = null
        override fun eventSliceOf(
            runId: String,
            after: EventCursor?,
            limit: Int,
        ): dev.rubentxu.pipeline.v2.events.EventSlice? = null
        override fun outputTailsOf(runId: String): List<OutputTailState?>? = tails
    }

    /**
     * The connection, recording what the street wrote on it.
     */
    private class RecordingConnection : ServiceConnection {
        private val written = mutableListOf<String>()

        override fun readLine(maxBytes: Int, deadlineMillis: Long): ServiceRead = ServiceRead.Closed

        override fun writeLine(line: String) {
            written.add(line)
        }

        override fun close() = Unit

        /**
         * The frames the street wrote, as documents.
         */
        fun frames(): List<kotlinx.serialization.json.JsonObject> =
            written.map { Json.parseToJsonElement(it).jsonObject }
    }
}
