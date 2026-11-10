package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetryFactory
import dev.rubentxu.pipeline.v2.runtime.inspect.OperationInspectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RunStructureResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionPort
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.StructureRunOptions
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceSubscriptionRequest
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What the server hands to its observer, and what the observer cannot do to the server.
 *
 * ## The guarantee under trial
 *
 * Every request that decoded is observed ONCE and ends ONCE, with the class
 * of how it ended; a line the codec refuses is not a request and is not
 * observed; a refused client is observed with no run on it; and an observer
 * that fails changes nothing a client can read.
 *
 * ## The capacity this serves
 *
 * An operator who reads the telemetry of a service has to be able to trust
 * that a request which left no trace did not happen, and that a trace which
 * says "answered" is a request whose answer was written.
 *
 * ## Why this instrument
 *
 * The server is driven over scripted connections with a recording observer,
 * so the rows read what the SERVER reports without any telemetry in the way.
 * One row at the end puts the real observer behind the server over the
 * upstream in-memory exporters, to read what leaves the process.
 *
 * ## What this does not freeze
 *
 * The order in which two requests of the same service end, and the names of
 * the instruments, which belong to the rows of the observer.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServiceServerObservationTest {

    @Test
    fun `a question about the service is observed once and answered`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))

        serve(observer, connection)

        assertEquals(listOf(ServiceRequestKind.Query to null), observer.begun.toList())
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Answered), observer.ended.toList())
        assertEquals(1, connection.written.size, "the client got its answer")
    }

    @Test
    fun `a question about a run carries the run only when the client may ask about it`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(LIST_INPUTS_REQUEST))

        serve(
            observer,
            connection,
            authorizations = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer))),
        )

        assertEquals(
            listOf(ServiceRequestKind.Query to "run-1"),
            observer.begun.toList(),
            "an authorized request names the run it is about",
        )
        assertEquals(
            listOf<ServiceOutcome>(ServiceOutcome.Refused),
            observer.ended.toList(),
            "a service that composes no door for the answers refuses with a name: that is a refusal",
        )
    }

    @Test
    fun `a client that may not ask about a run is observed with no run on the observation`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(RUN_STATE_REQUEST))

        serve(observer, connection, authorizations = authorizations(ServiceScope.Runs(setOf("run-2"))))

        assertEquals(
            listOf(ServiceRequestKind.Query to null),
            observer.begun.toList(),
            "the run a refused client names is text that client chose: it is not published",
        )
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Refused), observer.ended.toList())
    }

    @Test
    fun `a start the door accepts is observed as a mutation and answered`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(START_REQUEST))
        val starter = ServiceTestStarter(ServiceStartOutcome.Accepted("run-9", "corr-1", "owner-1", 1700L))

        serve(
            observer,
            connection,
            operations = operations(starter),
            authorizations = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))),
        )

        assertEquals(
            listOf(ServiceRequestKind.Mutation to null),
            observer.begun.toList(),
            "the run a start makes is not the run it is about: it has none yet",
        )
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Answered), observer.ended.toList())
    }

    @Test
    fun `a start the door refuses is a refusal and a start the substrate breaks is a fault`() {
        val refused = RecordingObserver(expectedEndings = 1)
        serve(
            refused,
            ScriptedConnection(ServiceRead.Line(START_REQUEST)),
            operations = operations(ServiceTestStarter()),
            authorizations = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))),
        )
        val broken = RecordingObserver(expectedEndings = 1)
        serve(
            broken,
            ScriptedConnection(ServiceRead.Line(START_REQUEST)),
            operations = operations(
                ServiceTestStarter(ServiceStartOutcome.Refused(ServiceRefusal.StorageFailed("the disk is gone"))),
            ),
            authorizations = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))),
        )

        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Refused), refused.ended.toList())
        assertEquals(
            listOf<ServiceOutcome>(ServiceOutcome.Faulted(FailureKind.INFRASTRUCTURE)),
            broken.ended.toList(),
            "a refusal that is about the machinery is the one an operator has to act on",
        )
    }

    @Test
    fun `an answer to a question is observed as a mutation with the run it is about`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(ANSWER_INPUT_REQUEST))

        serve(
            observer,
            connection,
            authorizations = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer))),
        )

        assertEquals(listOf(ServiceRequestKind.Mutation to "run-1"), observer.begun.toList())
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Refused), observer.ended.toList())
    }

    @Test
    fun `a client that presents nothing is observed as a refused mutation with no run`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(ANONYMOUS_CANCEL_REQUEST))

        serve(observer, connection)

        assertEquals(listOf(ServiceRequestKind.Mutation to null), observer.begun.toList())
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Refused), observer.ended.toList())
    }

    @Test
    fun `a subscription is observed with its run and ends as answered when its street returns`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))

        serve(observer, connection)

        assertEquals(listOf(ServiceRequestKind.Subscription to "run-1"), observer.begun.toList())
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Answered), observer.ended.toList())
    }

    @Test
    fun `a subscription of a client that presents nothing is a refusal with no run`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(ANONYMOUS_EVENTS_REQUEST))

        serve(observer, connection)

        assertEquals(listOf(ServiceRequestKind.Subscription to null), observer.begun.toList())
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Refused), observer.ended.toList())
    }

    @Test
    fun `a subscription over the budget is observed with its run and refused`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))

        serve(observer, connection, limits = ServiceLimits(maxSubscriptions = 0))

        assertEquals(
            listOf(ServiceRequestKind.Subscription to "run-1"),
            observer.begun.toList(),
            "the client may follow the run; the service had no room: the run is still the one it is about",
        )
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Refused), observer.ended.toList())
    }

    @Test
    fun `a street that breaks ends as abandoned and gives its slot back`() {
        val observer = RecordingObserver(expectedEndings = 0)
        val firstEnded = CountDownLatch(1)
        observer.onEnd = { firstEnded.countDown() }
        val first = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST))
        val gate = CountDownLatch(1)
        val second = ScriptedConnection(ServiceRead.Line(EVENTS_REQUEST), gate)
        val calls = AtomicInteger()
        val streets = object : ServiceStreets {
            override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) {
                if (calls.incrementAndGet() == 1) throw IOException("the street broke")
            }
        }
        val server = ServiceServer(
            ScriptedTransport(listOf(first, second)),
            operations(),
            streets,
            authorizations(),
            ServiceLimits(maxSubscriptions = 1),
            observer,
        )
        try {
            server.serve()
            assertTrue(firstEnded.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the first subscription ended")
            gate.countDown()
            second.awaitAnswered()
            observer.awaitEnded(2)
        } finally {
            server.close()
        }

        assertEquals(
            listOf<ServiceOutcome>(ServiceOutcome.Abandoned, ServiceOutcome.Answered),
            observer.ended.toList(),
            "the second subscription found the slot the broken one held: it was given back",
        )
    }

    @Test
    fun `a line the codec refuses is not a request and is not observed`() {
        val observer = RecordingObserver(expectedEndings = 0)
        val connection = ScriptedConnection(ServiceRead.Line("esto no es una peticion"))

        serve(observer, connection)

        assertEquals(1, connection.written.size, "the client is told what it did")
        assertTrue(observer.begun.isEmpty(), "nothing was admitted: ${observer.begun}")
        assertTrue(observer.ended.isEmpty())
    }

    @Test
    fun `an answer that cannot be written ends as abandoned`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST), failWrites = true)

        serve(observer, connection)

        assertEquals(
            listOf<ServiceOutcome>(ServiceOutcome.Abandoned),
            observer.ended.toList(),
            "a request whose answer was not delivered is not an answered request",
        )
        assertTrue(connection.closed, "the connection is closed all the same")
    }

    @Test
    fun `a refusal that cannot be written ends as abandoned and not as refused`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(ANONYMOUS_CANCEL_REQUEST), failWrites = true)

        serve(observer, connection)

        assertEquals(
            listOf<ServiceOutcome>(ServiceOutcome.Abandoned),
            observer.ended.toList(),
            "a client that never read its refusal was not refused: it was abandoned",
        )
        assertTrue(connection.written.isEmpty(), "nothing reached the client")
        assertTrue(connection.closed, "the connection is closed all the same")
    }

    @Test
    fun `an operation that breaks ends as a fault of the engine and the connection is closed`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(RUN_STATE_REQUEST))

        serve(observer, connection)

        assertEquals(
            listOf<ServiceOutcome>(ServiceOutcome.Faulted(FailureKind.ENGINE)),
            observer.ended.toList(),
            "an operation that threw before it answered is the engine's fault, not the client's",
        )
        assertTrue(connection.written.isEmpty(), "nothing was answered")
        connection.awaitAnswered()
        assertTrue(connection.closed, "the client is not left holding the socket")
    }

    @Test
    fun `a mutation that arrives as the service stops ends as abandoned`() {
        val observer = RecordingObserver(expectedEndings = 1)
        val connection = ScriptedConnection(ServiceRead.Line(START_REQUEST))
        val serverOf = AtomicReference<ServiceServer>()
        observer.onBegin = { serverOf.get().close() }
        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(),
            quietStreets(),
            authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))),
            ServiceLimits(),
            observer,
        )
        serverOf.set(server)
        try {
            server.serve()
            observer.awaitEnded(1)
            connection.awaitAnswered()
        } finally {
            server.close()
        }

        assertEquals(
            listOf<ServiceOutcome>(ServiceOutcome.Abandoned),
            observer.ended.toList(),
            "a request nobody was left to answer is abandoned, not faulted and not answered",
        )
        assertTrue(connection.closed, "the connection that nobody could answer is closed")
    }

    @Test
    fun `an observer that breaks on begin changes nothing the client reads`() {
        val observer = RecordingObserver(expectedEndings = 0).also { it.failOnBegin = true }
        val connection = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))

        serve(observer, connection)

        assertEquals(1, observer.begun.size, "the observer was asked, and it broke")
        assertEquals(1, connection.written.size, "the client got its answer")
        assertTrue(connection.closed, "the connection is closed all the same")
        assertTrue(observer.ended.isEmpty(), "an observation that never opened has nothing to end")
    }

    @Test
    fun `an observer that breaks on end changes nothing the client reads`() {
        val observer = RecordingObserver(expectedEndings = 1).also { it.failOnEnd = true }
        val connection = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))
        val plain = ScriptedConnection(ServiceRead.Line(VERSION_REQUEST))

        serve(observer, connection)
        serve(RecordingObserver(expectedEndings = 1), plain)

        assertEquals(
            plain.written,
            connection.written,
            "the answer is the one the operations gave, whatever the observer did afterwards",
        )
        assertEquals(listOf<ServiceOutcome>(ServiceOutcome.Answered), observer.ended.toList())
    }

    @Test
    fun `the real observer behind the server publishes the request and never the credential or the question`() {
        val spans = InMemorySpanExporter.create()
        val reader = InMemoryMetricReader.create()
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build()
        val telemetry = PipelineTelemetryFactory.sdkPipelineTelemetry(sdk, warning = { })
        val connection = ScriptedConnection(ServiceRead.Line(ANSWER_INPUT_REQUEST))
        val ended = CountDownLatch(1)
        val real = TelemetryServiceObserver(telemetry)
        val observer = object : ServiceObserver {
            override fun begin(kind: ServiceRequestKind, runId: String?): ServiceObservation {
                val inner = real.begin(kind, runId)
                return ServiceObservation { outcome ->
                    inner.end(outcome)
                    ended.countDown()
                }
            }
        }

        val server = ServiceServer(
            ScriptedTransport(listOf(connection)),
            operations(),
            quietStreets(),
            authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer))),
            ServiceLimits(),
            observer,
        )
        try {
            server.serve()
            assertTrue(ended.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the request ended")
        } finally {
            server.close()
        }

        val span = spans.finishedSpanItems.single()
        assertEquals("pipelinek.service.mutation", span.name)
        assertEquals(
            mapOf("run.id" to "run-1", "failure.class" to "rejected"),
            span.attributes.asMap().entries.associate { (key, value) -> key.key to value.toString() },
            "the span says which run the request was about and how it ended, and nothing else",
        )
        val published = buildString {
            spans.finishedSpanItems.forEach { item ->
                append(item.name).append(' ')
                item.attributes.asMap().forEach { (key, value) -> append(key.key).append('=').append(value).append(' ') }
            }
            reader.collectAllMetrics().forEach { metric ->
                append(metric.name).append(' ')
                metric.longSumData.points.forEach { point ->
                    point.attributes.asMap().forEach { (key, value) ->
                        append(key.key).append('=').append(value).append(' ')
                    }
                }
            }
        }
        assertFalse("token-1" in published, "the credential never leaves the process: $published")
        assertFalse("run-1-s0-0" in published, "the identity of the question never leaves the process: $published")
        assertFalse("run-1" in published.substringAfter("pipelinek.boundary_total"), "the run is not a dimension")
    }

    /** Serves [connections] with [observer] and waits for the answers and for the endings the observer expects. */
    private fun serve(
        observer: RecordingObserver,
        vararg connections: ScriptedConnection,
        operations: ServiceOperations = operations(),
        authorizations: ServiceAuthorizations = authorizations(),
        limits: ServiceLimits = ServiceLimits(),
    ) {
        val server = ServiceServer(
            ScriptedTransport(connections.toList()),
            operations,
            quietStreets(),
            authorizations,
            limits,
            observer,
        )
        try {
            server.serve()
            connections.forEach { it.awaitAnswered() }
            observer.awaitEnded(observer.expectedEndings)
        } finally {
            server.close()
        }
    }

    private fun authorizations(scope: ServiceScope = ServiceScope.All): ServiceAuthorizations =
        ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", scope)))

    private fun operations(starter: ServiceRunStarter = ServiceTestStarter()): ServiceOperations =
        ServiceOperations(
            introspection = UnusedPort(),
            substrate = {
                ServiceSubstrate(
                    eventStore = "/tmp/state/pipelinek.db",
                    controlRoot = "/tmp/state/ctl",
                    eventStorePresent = true,
                    controlRootPresent = true,
                )
            },
            starts = starter,
            cancellations = null,
            inputs = null,
        )

    /** Streets that serve their subscription and return without writing: these rows are about the observation. */
    private fun quietStreets(): ServiceStreets = object : ServiceStreets {
        override fun serve(connection: ServiceConnection, request: ServiceSubscriptionRequest) = Unit
    }

    /** Records what the server reports, and can be told to break, to wait or to stop the server. */
    private class RecordingObserver(val expectedEndings: Int) : ServiceObserver {

        val begun = CopyOnWriteArrayList<Pair<ServiceRequestKind, String?>>()
        val ended = CopyOnWriteArrayList<ServiceOutcome>()

        @Volatile var failOnBegin: Boolean = false

        @Volatile var failOnEnd: Boolean = false

        @Volatile var onBegin: (() -> Unit)? = null

        @Volatile var onEnd: (() -> Unit)? = null

        private val endings = Semaphore(0)

        override fun begin(kind: ServiceRequestKind, runId: String?): ServiceObservation {
            begun += kind to runId
            onBegin?.invoke()
            check(!failOnBegin) { "the observer broke on begin" }
            return ServiceObservation { outcome ->
                ended += outcome
                endings.release()
                onEnd?.invoke()
                check(!failOnEnd) { "the observer broke on end" }
            }
        }

        /** Waits for [count] endings to be reported. */
        fun awaitEnded(count: Int) {
            if (count == 0) return
            assertTrue(
                endings.tryAcquire(count, AWAIT_SECONDS, TimeUnit.SECONDS),
                "expected $count endings and saw ${ended.size}: ${ended.toList()}",
            )
        }
    }

    private class ScriptedTransport(connections: List<ScriptedConnection>) : ServiceTransport {

        private val pending = ArrayDeque(connections)

        override fun accept(): ServiceConnection? = pending.removeFirstOrNull()

        override fun close() {
            // The queue survives the close on purpose: a transport that is
            // closed may still hand out a connection that arrived before.
        }
    }

    private class ScriptedConnection(
        private val read: ServiceRead,
        private val gate: CountDownLatch? = null,
        private val failWrites: Boolean = false,
    ) : ServiceConnection {

        val written: MutableList<String> = mutableListOf()

        var closed: Boolean = false
            private set

        private val answered = CountDownLatch(1)

        override fun readLine(maxBytes: Int, deadlineMillis: Long): ServiceRead {
            gate?.await()
            return read
        }

        override fun writeLine(line: String) {
            if (failWrites) throw IOException("the client is gone")
            written.add(line)
            answered.countDown()
        }

        override fun close() {
            closed = true
            answered.countDown()
        }

        fun awaitAnswered() {
            answered.await(AWAIT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private class UnusedPort : RuntimeIntrospectionPort {

        override fun inspect(runId: String): RuntimeIntrospectionResult =
            throw UnsupportedOperationException("these rows do not ask about a run")

        override fun runStructure(runId: String, options: StructureRunOptions): RunStructureResult =
            throw UnsupportedOperationException("these rows do not ask about a run")

        override fun inspectOperation(operationId: String, attempt: Int): OperationInspectionResult =
            throw UnsupportedOperationException("these rows do not ask about a run")
    }

    private companion object {
        private const val TOKEN: String = "token-1"
        private const val AWAIT_SECONDS: Long = 5L

        private const val VERSION_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"version","auth":"token-1"}"""

        private const val EVENTS_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1","auth":"token-1"}"""

        private const val ANONYMOUS_EVENTS_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"events.follow","runId":"run-1"}"""

        private const val RUN_STATE_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.state","runId":"run-1","auth":"token-1"}"""

        private const val START_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.start","script":"build.kts","auth":"token-1"}"""

        private const val ANONYMOUS_CANCEL_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"run.cancel","runId":"run-1"}"""

        private const val LIST_INPUTS_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"inputs.list","runId":"run-1","auth":"token-1"}"""

        private const val ANSWER_INPUT_REQUEST: String =
            """{"schema":"pipelinek.service/2","op":"input.answer","runId":"run-1","opId":"run-1-s0-0","decision":"proceed","auth":"token-1"}"""
    }
}
