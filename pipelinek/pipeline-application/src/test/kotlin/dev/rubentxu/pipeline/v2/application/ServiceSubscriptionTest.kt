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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
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
 *
 * ## Why every row carries a time limit
 *
 * A street that cannot decide waits, and a waiting street never fails: it
 * hangs, and a hang is a lost lane, not a red one. The limit is on the class
 * so that EVERY row can turn that hang into a red row —a defect that stops a
 * street from ending has to be a datum, not a lost lane.
 */
@Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
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
            history = listOf(runFinished(1L)),
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
    fun `the console street refuses a run nobody knows by name`() {
        // The console of a run that does not exist. Nothing in the output lane says so: the
        // output plane answers an empty page and the run plane says the run is live, because a
        // run nobody wrote a row in looks exactly like a run that is still going. Only the read
        // port knows the difference, and its refusal is the name the street carries.
        val port = ScriptedEventPort { _ ->
            EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun("run-1"))
        }
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street refused and ended: $frames")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "unknown-run",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
            "the refusal names the fact that stopped the street",
        )
    }

    @Test
    fun `a console that already delivered frames is not asked whether the run exists`() {
        // The run has a console, so it exists. The street that delivered a frame has already
        // answered the question, and it does not ask it: a run whose frames were published took
        // its lease in the same control root, so the read port knows it.
        //
        // The row has the street DELIVER a frame and then go quiet, because a street that ends
        // in the round that delivered never reaches the question at all. A street that asked
        // there would answer the client with a refusal that contradicts the transcript it just
        // delivered.
        var asked = 0
        val port = ScriptedEventPort { _ ->
            asked++
            EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun("run-1"))
        }
        val lanes = FakeLanes(
            outputs = listOf(
                ObservationOutputRead.Page(
                    ObservationOutputPage(
                        records = listOf(frame(0L, "hello")),
                        lastOrdinal = 0L,
                        moreFrames = false,
                    ),
                ),
                ObservationOutputRead.Page(
                    ObservationOutputPage(records = emptyList(), lastOrdinal = 0L, moreFrames = false),
                ),
            ),
            history = historyOfTwoPages(),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        assertEquals(0, asked, "the street did not ask whether the run exists")
        val frames = connection.frames()
        assertEquals(2, frames.size, "the street delivered the frame and ended: $frames")
        assertEquals("record", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("end", frames[1]["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", frames[1]["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a console of a run that is over ends without asking whether it exists`() {
        // The run is over and the client resumed past its console: the street ends on the
        // terminal fact it observed. It does not ASK whether the run exists, because it is not
        // going to wait for anything, and a question it does not need is a chance to answer the
        // client with a refusal where the honest answer is an end.
        val port = ScriptedEventPort { _ ->
            EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun("run-1"))
        }
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            history = listOf(runFinished(1L)),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        assertEquals(0, port.calls.size, "the street did not ask: it was going to end")
        val frames = connection.frames()
        assertEquals(1, frames.size, "the street ended with the terminal fact: $frames")
        assertEquals("end", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", frames[0]["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the question about the existence asks for one row and no cursor`() {
        // The question asks whether the run EXISTS, so it carries no cursor: the cursor of the
        // client is a position in the console, and the cursor of the port is a position in the
        // events. A question that carried the client's cursor would be refused with the wrong
        // word, and a question that asked for the run's whole history would cost a read it does
        // not need.
        val port = ScriptedEventPort { _ ->
            EventRecordReadResult.Refused(EventRecordReadRefusal.UnknownRun("run-1"))
        }
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        assertEquals(1, port.calls.size, "the street asked once")
        val call = port.calls.single()
        assertNull(call.after, "the question does not carry a cursor")
        assertEquals(EventQuery.All, call.query, "the question is not filtered")
        assertEquals(1, call.limit, "the question needs one row, not the run's whole history")
    }

    @Test
    fun `a run whose console arrives late is not refused`() {
        // The first round finds nothing, and that is the moment the street asks whether the run
        // exists. The run does exist, so the street waits and the console arrives. A street that
        // refused a quiet run would refuse every run that has not spoken yet.
        val port = ScriptedEventPort { _ -> page(emptyList(), 0L) }
        val lanes = FakeLanes(
            outputs = listOf(
                ObservationOutputRead.Page(
                    ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
                ),
                ObservationOutputRead.Page(
                    ObservationOutputPage(
                        records = listOf(frame(0L, "hello")),
                        lastOrdinal = 0L,
                        moreFrames = false,
                    ),
                ),
            ),
            history = historyOfTwoPages(),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(2, frames.size, "the street delivered the late frame and ended: $frames")
        assertEquals("record", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("hello", frames[0]["record"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals("end", frames[1]["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", frames[1]["reason"]?.jsonPrimitive?.content)
        assertEquals(
            1,
            port.calls.size,
            "the street asked about the existence ONCE, and not on every round",
        )
    }

    @Test
    fun `a console whose run plane throws is refused by name`() {
        // The run plane is there and could not answer. The street cannot end on a fact nobody
        // observed, and it cannot wait for ever either, so it refuses with the word that names
        // the failure — and the word carries the cause.
        val port = ScriptedEventPort { _ -> page(emptyList(), 0L) }
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            sliceFailure = IllegalStateException("the store is gone"),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street refused and ended: $frames")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        val refusal = frames[0]["refusal"]?.jsonObject
        assertEquals("storage-failed", refusal?.get("kind")?.jsonPrimitive?.content)
        assertEquals(
            "IllegalStateException: the store is gone",
            refusal?.get("cause")?.jsonPrimitive?.content,
            "the cause says what failed, so the word alone does not have to",
        )
    }

    @Test
    fun `a console whose run plane cannot be asked is refused by name`() {
        // The run plane has no execution authority to ask. That is not the same fact as a
        // failure: there is nobody to fail, so the word says there is no plane.
        val port = ScriptedEventPort { _ -> page(emptyList(), 0L) }
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            laneAbsent = true,
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street refused and ended: $frames")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "not-configured-event-plane",
            frames[0]["refusal"]?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the events street does not consult the plane once it has delivered a row`() {
        // The lane's answer comes from a read that FAILS CLOSED on a row this binary cannot
        // decode, and that row is the one the street just delivered as a record. A street that
        // asked the plane after delivering it would die with a word about the store, and the
        // client would lose the rest of a run that is still going. The terminal fact will be
        // DELIVERED, so the plane is not needed.
        val port = ScriptedEventPort { call ->
            when (call) {
                0 -> page(listOf(EventRecordRead.Decoded(runStarted(1L))), lastSequence = 1L)
                1 -> page(emptyList(), lastSequence = 1L)
                else -> error("the port went away")
            }
        }
        val lanes = FakeLanes(planeFailure = IllegalStateException("the store is gone"))
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        val failure = runCatching {
            subscription.serve(
                connection,
                ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
            )
        }.exceptionOrNull()

        assertEquals(
            "the port went away",
            failure?.message,
            "the street kept reading: it reached the port's next round instead of the plane",
        )
        assertTrue(
            connection.frames().none { it["kind"]?.jsonPrimitive?.content == "refused" },
            "the street did not refuse: ${connection.frames()}",
        )
    }

    @Test
    fun `the events street names the failure of the run plane too`() {
        // The same failure, in the other street. The two streets refuse with the same word
        // because there is ONE translation, and a client that gets a name in one street gets
        // the same name in the other.
        val port = ScriptedEventPort { _ -> page(emptyList(), 0L) }
        val lanes = FakeLanes(planeFailure = IllegalStateException("the store is gone"))
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street refused and ended: $frames")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        val refusal = frames[0]["refusal"]?.jsonObject
        assertEquals("storage-failed", refusal?.get("kind")?.jsonPrimitive?.content)
        assertEquals(
            "IllegalStateException: the store is gone",
            refusal?.get("cause")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the events street names a run plane that cannot be asked`() {
        val port = ScriptedEventPort { _ -> page(emptyList(), 0L) }
        val lanes = FakeLanes(runPlane = RunPlane.Unavailable)
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = port)

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.EventsFollow(ServiceProtocol.SCHEMA, "run-1", null),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street refused and ended: $frames")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals(
            "not-configured-event-plane",
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
            history = listOf(runFinished(1L)),
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

    @Test
    fun `the street asks the plane in the first round and then only when its answer can change`() {
        // The run plane is the run's own authority, and reading it is not free: it reads the
        // run's history. The street asks it in the FIRST round —the first answer is the one that
        // proves the plane can answer at all, so a plane that cannot answer is named in the first
        // round— and after that only in the round whose answer can change what the street does.
        //
        // In the rounds that delivered a frame the answer changes nothing: the street reads again
        // whatever the plane says. A street that asks anyway pays for an answer nobody reads, and
        // a street that asks in EVERY round pays for the whole history in every round.
        val lanes = FakeLanes(
            outputs = List(5) { round ->
                ObservationOutputRead.Page(
                    ObservationOutputPage(
                        records = listOf(frame(round.toLong(), "hello")),
                        lastOrdinal = round.toLong(),
                        moreFrames = true,
                    ),
                )
            } + ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = 4L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            history = listOf(runFinished(1L)),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        assertEquals(
            1,
            lanes.planeReads,
            "the street asked the plane once in six rounds: the first one. The terminal fact it " +
                "brought was OBSERVED, and an observed fact is not asked for again",
        )
        assertEquals(
            listOf(1),
            lanes.planeReadRounds,
            "the plane was read in the first round and in no other",
        )
        assertEquals(6, connection.frames().size, "the street delivered five frames and ended")
    }

    @Test
    fun `the street asks the plane only when the answer can change the decision`() {
        // The rows that count the consultations, with a stream that is OPEN: an open stream still
        // owes bytes, so the plane's answer cannot change what the street does, and the street
        // must not ask. The stream seals at the end, and THAT is the round whose answer matters:
        // the street asks the plane, the terminal fact arrives, and the street ends.
        //
        // A street that asked in every round, or one that asked whenever no frame was pending,
        // would read a page in the rounds in which the answer changes nothing —and the cost of a
        // page is the cost of the run's history, so that is the difference this row measures.
        // The ROUNDS in which the plane was read are what tell the two apart: a street that asks
        // when it should not asks EARLIER, and the count alone would not see it.
        val lanes = FakeLanes(
            outputs = List(4) { round ->
                ObservationOutputRead.Page(
                    ObservationOutputPage(
                        records = listOf(frame(round.toLong(), "hello")),
                        lastOrdinal = round.toLong(),
                        moreFrames = false,
                    ),
                )
            },
            tailsPerRound = listOf(
                listOf(OutputTailState.Open(committedEnd = 1L)),
                listOf(OutputTailState.Open(committedEnd = 1L)),
                listOf(OutputTailState.Open(committedEnd = 1L)),
                listOf(OutputTailState.Sealed(finalEnd = 5L)),
            ),
            history = historyOfTwoPages(),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        assertEquals(
            2,
            lanes.planeReads,
            "the street asked the plane twice in four rounds: the first one, and the one whose " +
                "answer could change the decision",
        )
        assertEquals(
            listOf(1, 4),
            lanes.planeReadRounds,
            "the plane was read in the first round and in the round the stream sealed, and NOT in " +
                "the two rounds in between, where the stream was still open",
        )
        assertEquals(5, connection.frames().size, "the street delivered four frames and ended")
    }

    @Test
    fun `a run whose terminal fact is in the middle of its history ends with the terminal fact`() {
        // The rule of the plane is the rule the tree has always had: the run ENDED when its rows
        // hold a terminal fact, not when its last row happens to be one. A run that was resumed
        // carries more than one terminal fact, and a street that waited for the LAST row would
        // keep a run that is over alive —and would contradict the rest of the product, which
        // reports the LAST terminal fact as the run's current state.
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            history = listOf(runStarted(1L), runFinished(2L), runStarted(3L)),
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street ended on the terminal fact: $frames")
        assertEquals("end", frames[0]["kind"]?.jsonPrimitive?.content)
        assertEquals("terminal-observed", frames[0]["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a page with a row this binary cannot read ends the street with the store's word`() {
        // The page FAILS CLOSED: the store hands the whole page or it hands nothing, so a page
        // that carries a terminal fact AND a row this binary cannot read never delivers the
        // terminal fact —and the street says so with the word that names the failure.
        //
        // This is the price of reading the run's history a page at a time: the sweep stopped at
        // the terminal fact and never looked at the rows behind it, and the page looks at the
        // rows of the page it brings.
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            history = listOf(runStarted(1L), runFinished(2L), runStarted(3L)),
            undecodableFrom = 3L,
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        val frames = connection.frames()
        assertEquals(1, frames.size, "the street refused and ended: $frames")
        assertEquals("refused", frames[0]["kind"]?.jsonPrimitive?.content)
        val refusal = frames[0]["refusal"]?.jsonObject
        assertEquals("storage-failed", refusal?.get("kind")?.jsonPrimitive?.content)
        assertEquals(
            "IllegalStateException: a row this binary cannot read",
            refusal?.get("cause")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a subscriber of a long run does not read the whole history in every round`() {
        // The instrument: the double counts the rows it serves. The run's history is 10.000 rows
        // and its terminal fact is the last of them, so a street that read the whole history in
        // every round would serve 10.000 rows per round. The street reads the history a PAGE at a
        // time, from where the last page stopped, so every row is served ONCE and no round reads
        // more than a page.
        val history = (1..10_000).map { runStarted(it.toLong()) } + runFinished(10_001L)
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            history = history,
        )
        val connection = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            connection,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        assertEquals(
            history.size,
            lanes.rowsServed,
            "every row of the history was served once, not once per round",
        )
        assertEquals(
            40,
            lanes.planeReads,
            "the street read the history in pages of 256 rows and stopped at the terminal fact",
        )
        assertEquals(
            256,
            lanes.maxRowsPerRead,
            "no page carried more rows than the page size, so no round cost more than a page",
        )
        assertEquals("end", connection.frames().single()["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `each subscriber reads the run's history for itself`() {
        // The reading position belongs to the SUBSCRIPTION, not to the run: two subscribers of the
        // same run each start at the beginning of its history, and each pays for the pages it
        // reads. A street that shared the position would hand the second subscriber a history
        // already read, and the second subscriber would see nothing at all.
        //
        // The cost of sharing it would be invisible to a single subscriber, which is why this row
        // has two of them: the one that reads and the one that is left without a history.
        val history = listOf(runStarted(1L), runFinished(2L))
        val lanes = FakeLanes(
            output = ObservationOutputRead.Page(
                ObservationOutputPage(records = emptyList(), lastOrdinal = -1L, moreFrames = false),
            ),
            tails = listOf(OutputTailState.Sealed(5L)),
            history = history,
        )
        val first = RecordingConnection()
        val second = RecordingConnection()
        val subscription = ServiceSubscription(lanes = lanes, events = ScriptedEventPort { _ -> page(emptyList(), 0L) })

        subscription.serve(
            first,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )
        subscription.serve(
            second,
            ServiceSubscriptionRequest.OutputFollow(ServiceProtocol.SCHEMA, "run-1", -1L),
        )

        assertEquals(
            2,
            lanes.planeReads,
            "each subscriber read the history for itself: two subscribers, two readings",
        )
        assertEquals(
            history.size * 2,
            lanes.rowsServed,
            "each subscriber was served every row of the history",
        )
        assertEquals(1, first.frames().size, "the first subscriber ended on the terminal fact")
        assertEquals(1, second.frames().size, "the second subscriber ended on the terminal fact")
    }

    /**
     * A history whose terminal fact is in its SECOND page.
     *
     * The street reads the run plane a page at a time, so a run that ends in the first page reads
     * as over from the first answer. A history that ends in the second page is how a test says
     * "the run was still going when the street asked".
     */
    private fun historyOfTwoPages(): List<DomainEvent> =
        (1..256).map { runStarted(it.toLong()) } + runFinished(257L)

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

        /** The parameters of every call, in order: the shape of the question is part of it. */
        val calls = mutableListOf<Call>()

        /** One call to the port. */
        data class Call(val after: EventCursor?, val query: EventQuery, val limit: Int)

        override fun readRecords(
            runId: String,
            after: EventCursor?,
            query: EventQuery,
            limit: Int,
        ): EventRecordReadResult {
            calls.add(Call(after, query, limit))
            val index = call
            call++
            return answer(index)
        }
    }

    /**
     * The lanes, faked.
     *
     * @param output the page every round answers, when [outputs] is empty.
     * @param outputs the pages the rounds answer, in order; the last one answers every round
     *   after it. A lane that answers differently on the second round is how a test says "the
     *   console arrives late".
     * @param tailsPerRound the tails each round answers, in order; the last one answers every
     *   round after it. A stream that opens and then seals is how a test says "the console is
     *   still being written, and then it is written".
     * @param planeFailure the failure the run plane throws, when it cannot answer at all.
     * @param history the rows of the run's event history. The OUTPUT street reads the run plane a
     *   PAGE at a time, so the plane's answer is a fact about these rows and not a flag the fake
     *   carries: a street that reads the whole history in every round is a street this double can
     *   count.
     * @param sliceFailure the failure the event lane throws when it cannot answer a page.
     * @param laneAbsent whether the event lane is absent, which is the one case in which the run
     *   plane cannot be asked at all.
     * @param undecodableFrom the sequence of the first row the event lane cannot read. The lane
     *   fails closed when the page it is asked for holds that row or a later one, which is how a
     *   store that cannot decode a row behaves.
     */
    private class FakeLanes(
        private val output: ObservationOutputRead? = null,
        private val outputs: List<ObservationOutputRead?> = emptyList(),
        private val tails: List<OutputTailState?> = emptyList(),
        private val tailsPerRound: List<List<OutputTailState?>> = emptyList(),
        private val runPlane: RunPlane = RunPlane.Live,
        private val planeFailure: Throwable? = null,
        private val history: List<DomainEvent> = emptyList(),
        private val sliceFailure: Throwable? = null,
        private val laneAbsent: Boolean = false,
        private val undecodableFrom: Long? = null,
    ) : ObserveLanes {
        private var outputRounds = 0
        private var tailRounds = 0

        /** How many pages of the run's history the street read. */
        var planeReads = 0
            private set

        /** How many rows of the run's history the street was served, in total. */
        var rowsServed = 0
            private set

        /** The most rows that ONE page of the run's history ever carried. */
        var maxRowsPerRead = 0
            private set

        /**
         * The rounds in which the street read the plane, counted from ONE.
         *
         * The COUNT of the readings says how many pages the street paid for; this says WHEN it
         * paid for them, and that is what tells a street that asks in every round from a street
         * that asks only in the round whose answer can change the decision: the first one pays
         * for a page in a round that needed no answer, the second one does not.
         */
        val planeReadRounds: MutableList<Int> = mutableListOf()

        override val hasEventStore: Boolean get() = !laneAbsent
        override val hasOutputPlane: Boolean get() = true
        override fun eventsOf(runId: String): Sequence<DomainEvent> = emptySequence()
        override fun runPlaneOf(runId: String): RunPlane {
            val failure = planeFailure
            if (failure != null) throw failure
            return runPlane
        }
        override fun outputOf(runId: String, afterOrdinal: Long, frameLimit: Int): ObservationOutputRead? {
            if (outputs.isEmpty()) return output
            val round = outputRounds
            outputRounds++
            return outputs[round.coerceAtMost(outputs.size - 1)]
        }
        override fun tailOf(runId: String, tailBytes: Long): ObservationOutputRead? = null
        override fun eventSliceOf(
            runId: String,
            after: EventCursor?,
            limit: Int,
        ): dev.rubentxu.pipeline.v2.events.EventSlice? {
            sliceFailure?.let { throw it }
            if (laneAbsent) return null
            val from = after?.lastSequence ?: 0L
            val page = history.filter { it.sequence > from }.take(limit)
            val unreadable = undecodableFrom
            if (unreadable != null && page.any { it.sequence >= unreadable }) {
                error("a row this binary cannot read")
            }
            planeReads++
            rowsServed += page.size
            if (page.size > maxRowsPerRead) maxRowsPerRead = page.size
            planeReadRounds.add(tailRounds)
            val next = page.lastOrNull()?.sequence ?: from
            return dev.rubentxu.pipeline.v2.events.EventSlice(
                events = page,
                nextCursor = EventCursor(runId, next),
                hasMore = history.any { it.sequence > next },
            )
        }
        override fun outputTailsOf(runId: String): List<OutputTailState?>? {
            val round = tailRounds
            tailRounds++
            if (tailsPerRound.isEmpty()) return tails
            return tailsPerRound[round.coerceAtMost(tailsPerRound.size - 1)]
        }
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
