package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.runtime.LiveSpanStatus
import dev.rubentxu.pipeline.v2.runtime.MetricBoundary
import dev.rubentxu.pipeline.v2.runtime.MetricFailureClass
import dev.rubentxu.pipeline.v2.runtime.MetricOutcome
import dev.rubentxu.pipeline.v2.runtime.MetricStatus
import dev.rubentxu.pipeline.v2.runtime.NoopPipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetry
import dev.rubentxu.pipeline.v2.runtime.PipelineTelemetryFactory
import dev.rubentxu.pipeline.v2.service.ServiceInputDecision
import dev.rubentxu.pipeline.v2.service.ServiceProtocol
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import dev.rubentxu.pipeline.v2.service.ServiceRequest
import dev.rubentxu.pipeline.v2.service.ServiceResponse
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the service says about the requests it serves, and what it never says.
 *
 * ## The guarantee under trial
 *
 * A request ends in one class and that class is read the same way by the
 * metric and by the span; the run a request is about travels on the span and
 * never on a metric dimension; and nothing the client sent — a credential, the
 * identity of a question, a script — can leave the process through this door.
 *
 * ## The capacity this serves
 *
 * An operator who reads the telemetry of a service has to be able to tell a
 * client that asked for what it may not have from a service that broke, and to
 * do it from a series whose size does not grow with the number of runs.
 *
 * ## Why this instrument
 *
 * The mapping from a request to its words is pure and is read as a table. The
 * observer itself is read through the upstream in-memory exporter and reader,
 * which are the ones the library's own tests use: a reader written in this
 * project would be a second understanding of what a collector receives.
 *
 * ## What this does not freeze
 *
 * The names of the instruments the runtime publishes, which belong to the
 * runtime's own rows, and the order in which spans are exported.
 */
class ServiceObservationTest {

    private val spans = InMemorySpanExporter.create()
    private val reader = InMemoryMetricReader.create()

    private fun telemetry(): PipelineTelemetry {
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build(),
            )
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build()
        return PipelineTelemetryFactory.sdkPipelineTelemetry(sdk, warning = { })
    }

    @Test
    fun `a response is classified by who has to act on it`() {
        val storage = ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.StorageFailed("the disk"))
        val inconsistent = ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.Inconsistent("two truths"))

        assertEquals(
            ServiceOutcome.Answered,
            serviceOutcomeOf(ServiceResponse.Answered(ServiceProtocol.SCHEMA, JsonObject(emptyMap()))),
        )
        assertEquals(
            ServiceOutcome.Refused,
            serviceOutcomeOf(ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.NotAuthorized)),
            "a client that asked for what it may not have is a refusal, not a fault",
        )
        assertEquals(
            ServiceOutcome.Refused,
            serviceOutcomeOf(
                ServiceResponse.Refused(ServiceProtocol.SCHEMA, ServiceRefusal.RunStartRefused("no", "no")),
            ),
            "a start the supervisor refused is a refusal: the client asked for something that cannot be",
        )
        assertEquals(
            ServiceOutcome.Faulted(FailureKind.INFRASTRUCTURE),
            serviceOutcomeOf(storage),
            "a substrate that failed is the one refusal an operator must act on",
        )
        assertEquals(
            ServiceOutcome.Faulted(FailureKind.ENGINE),
            serviceOutcomeOf(inconsistent),
            "a substrate that contradicted itself is a fault of the engine",
        )
    }

    @Test
    fun `the three requests that change something are mutations and the rest are questions`() {
        val schema = ServiceProtocol.SCHEMA
        val mutations = listOf(
            ServiceRequest.StartRun(schema, "build.kts", null, null),
            ServiceRequest.CancelRun(schema, "run-1", null),
            ServiceRequest.AnswerInput(schema, "run-1", "op-1", ServiceInputDecision.PROCEED, null),
        )
        val questions = listOf(
            ServiceRequest.Version(schema),
            ServiceRequest.Health(schema),
            ServiceRequest.RunState(schema, "run-1"),
            ServiceRequest.ListInputs(schema, "run-1"),
            ServiceRequest.DescribeParameters(schema, "build.kts"),
            ServiceRequest.ProviderRegister(schema, "vault", emptyList()),
            ServiceRequest.ProviderPoll(schema, "session-1", 0L),
            ServiceRequest.ProviderAnswer(schema, "session-1", "request-1", "r1", emptyMap()),
            ServiceRequest.ProviderClose(schema, "session-1"),
            ServiceRequest.RunConfigurationQuery(schema, "run-1"),
        )

        mutations.forEach { assertEquals(ServiceRequestKind.Mutation, kindOf(it), "$it changes something") }
        questions.forEach { assertEquals(ServiceRequestKind.Query, kindOf(it), "$it only asks") }
    }

    @Test
    fun `a request is about a run only when it names one`() {
        val schema = ServiceProtocol.SCHEMA

        assertEquals("run-1", runOf(ServiceRequest.RunState(schema, "run-1")))
        assertEquals("run-2", runOf(ServiceRequest.CancelRun(schema, "run-2", null)))
        assertEquals("run-3", runOf(ServiceRequest.ListInputs(schema, "run-3")))
        assertEquals(
            "run-4",
            runOf(ServiceRequest.AnswerInput(schema, "run-4", "op-1", ServiceInputDecision.ABORT, null)),
        )
        assertEquals(
            null,
            runOf(ServiceRequest.StartRun(schema, "build.kts", null, null)),
            "a start has no run yet: the one it makes is not the one it is about",
        )
        assertEquals(null, runOf(ServiceRequest.Version(schema)))
        assertEquals(null, runOf(ServiceRequest.Health(schema)))
        assertEquals(null, runOf(ServiceRequest.DescribeParameters(schema, "build.kts")))
        assertEquals("run-5", runOf(ServiceRequest.RunConfigurationQuery(schema, "run-5")))
        assertEquals(
            null,
            runOf(ServiceRequest.ProviderRegister(schema, "vault", emptyList())),
            "a provider session belongs to no run: a run asks for it, not the other way round",
        )
        assertEquals(null, runOf(ServiceRequest.ProviderPoll(schema, "session-1", 0L)))
        assertEquals(
            null,
            runOf(ServiceRequest.ProviderAnswer(schema, "session-1", "request-1", "r1", emptyMap())),
        )
        assertEquals(null, runOf(ServiceRequest.ProviderClose(schema, "session-1")))
    }

    @Test
    fun `an outcome is the same fact for the metric and for the span`() {
        assertEquals(MetricOutcome(MetricStatus.Success), metricOutcomeOf(ServiceOutcome.Answered))
        assertEquals(MetricOutcome(MetricStatus.AdmissionRefused), metricOutcomeOf(ServiceOutcome.Refused))
        assertEquals(MetricOutcome(MetricStatus.Cancelled), metricOutcomeOf(ServiceOutcome.Abandoned))
        assertEquals(
            MetricOutcome(MetricStatus.WorkerError, MetricFailureClass.Product(FailureKind.INFRASTRUCTURE)),
            metricOutcomeOf(ServiceOutcome.Faulted(FailureKind.INFRASTRUCTURE)),
        )

        assertEquals(LiveSpanStatus.Completed, spanStatusOf(ServiceOutcome.Answered))
        assertEquals(LiveSpanStatus.Rejected, spanStatusOf(ServiceOutcome.Refused))
        assertEquals(LiveSpanStatus.Failed, spanStatusOf(ServiceOutcome.Faulted(FailureKind.ENGINE)))
        assertEquals(LiveSpanStatus.Failed, spanStatusOf(ServiceOutcome.Abandoned))
    }

    @Test
    fun `every kind of request has a boundary and a span name of its own`() {
        val kinds = ServiceRequestKind.entries

        assertEquals(kinds.size, kinds.map { it.boundary }.toSet().size, "a boundary says one kind")
        assertEquals(kinds.size, kinds.map { it.spanName }.toSet().size, "a span name says one kind")
        assertEquals(
            setOf(MetricBoundary.ServiceQuery, MetricBoundary.ServiceMutation, MetricBoundary.ServiceSubscription),
            kinds.map { it.boundary }.toSet(),
        )
    }

    @Test
    fun `an answered question publishes one span and one count with the closed dimensions`() {
        val observer = TelemetryServiceObserver(telemetry())

        observer.begin(ServiceRequestKind.Query, null).end(ServiceOutcome.Answered)

        val span = spans.finishedSpanItems.single()
        assertEquals("pipelinek.service.query", span.name)
        assertEquals(StatusCode.OK, span.status.statusCode)
        assertEquals(
            emptyMap<String, String>(),
            span.attributes.asStringMap(),
            "a question about the service is about no run, and a span with nothing to say says nothing",
        )
        assertEquals(
            mapOf("operation_kind" to "service.query", "status" to "success"),
            countOf("pipelinek.boundary_total").single().attributes.asStringMap(),
        )
        assertEquals(1L, countOf("pipelinek.boundary_total").single().value)
    }

    @Test
    fun `the run a request is about is an attribute of the span and never a dimension of the metric`() {
        val observer = TelemetryServiceObserver(telemetry())

        observer.begin(ServiceRequestKind.Mutation, "run-77").end(ServiceOutcome.Answered)

        assertEquals(
            mapOf("run.id" to "run-77"),
            spans.finishedSpanItems.single().attributes.asStringMap(),
            "the span names the run it is about, with the key the published identity table allows",
        )
        val dimensions = countOf("pipelinek.boundary_total").single().attributes.asStringMap()
        assertEquals(setOf("operation_kind", "status"), dimensions.keys)
        assertFalse(
            dimensions.values.any { "run-77" in it },
            "a dimension that carried the run would make one series per run",
        )
    }

    @Test
    fun `a refusal and a fault publish different statuses and only the fault carries a failure class`() {
        val observer = TelemetryServiceObserver(telemetry())

        observer.begin(ServiceRequestKind.Query, null).end(ServiceOutcome.Refused)
        observer.begin(ServiceRequestKind.Query, null).end(ServiceOutcome.Faulted(FailureKind.INFRASTRUCTURE))

        val byStatus = countOf("pipelinek.boundary_total").associate {
            it.attributes.asStringMap().getValue("status") to it.attributes.asStringMap()
        }
        assertEquals(
            mapOf("operation_kind" to "service.query", "status" to "refused"),
            byStatus.getValue("refused"),
            "a refusal is not a failure: no class travels with it",
        )
        assertEquals(
            mapOf(
                "operation_kind" to "service.query",
                "status" to "worker_error",
                "failure_class" to "INFRASTRUCTURE",
            ),
            byStatus.getValue("worker_error"),
        )
        assertEquals(
            listOf(StatusCode.ERROR, StatusCode.ERROR),
            spans.finishedSpanItems.map { it.status.statusCode },
            "neither a refusal nor a fault is a span that completed",
        )
    }

    @Test
    fun `an abandoned subscription is published as cancelled and its span failed`() {
        val observer = TelemetryServiceObserver(telemetry())

        observer.begin(ServiceRequestKind.Subscription, "run-1").end(ServiceOutcome.Abandoned)

        assertEquals("pipelinek.service.subscription", spans.finishedSpanItems.single().name)
        assertEquals(StatusCode.ERROR, spans.finishedSpanItems.single().status.statusCode)
        assertEquals(
            mapOf("operation_kind" to "service.subscription", "status" to "cancelled"),
            countOf("pipelinek.boundary_total").single().attributes.asStringMap(),
        )
    }

    @Test
    fun `a request that is ended twice is published once and with its first ending`() {
        val observer = TelemetryServiceObserver(telemetry())

        val observation = observer.begin(ServiceRequestKind.Query, null)
        observation.end(ServiceOutcome.Answered)
        observation.end(ServiceOutcome.Faulted(FailureKind.ENGINE))

        assertEquals(1, spans.finishedSpanItems.size)
        assertEquals(StatusCode.OK, spans.finishedSpanItems.single().status.statusCode)
        assertEquals(1L, countOf("pipelinek.boundary_total").single().value)
        assertEquals(
            "success",
            countOf("pipelinek.boundary_total").single().attributes.asStringMap().getValue("status"),
        )
    }

    @Test
    fun `a text that is not a run id cannot widen what a span carries`() {
        val observer = TelemetryServiceObserver(telemetry())
        val hostile = "run-1" + "x".repeat(HOSTILE_LENGTH)

        observer.begin(ServiceRequestKind.Query, hostile).end(ServiceOutcome.Answered)

        val carried = spans.finishedSpanItems.single().attributes.asStringMap()
        assertTrue(
            carried.getValue("run.id").length <= MAX_ATTRIBUTE_TEXT,
            "the published redaction rule bounds the text of the attribute: $carried",
        )
    }

    @Test
    fun `a telemetry that is disabled publishes nothing and the observer costs one boolean`() {
        val observer = TelemetryServiceObserver(NoopPipelineTelemetry)

        observer.begin(ServiceRequestKind.Mutation, "run-1").end(ServiceOutcome.Answered)

        assertTrue(spans.finishedSpanItems.isEmpty())
        assertTrue(reader.collectAllMetrics().isEmpty())
    }

    @Test
    fun `an observer that nobody asked for hands back an observation that does nothing`() {
        val observation = NoServiceObserver.begin(ServiceRequestKind.Query, "run-1")

        observation.end(ServiceOutcome.Faulted(FailureKind.ENGINE))
        observation.end(ServiceOutcome.Answered)

        assertTrue(spans.finishedSpanItems.isEmpty())
    }

    private fun countOf(name: String): List<LongPointData> =
        reader.collectAllMetrics().filter { it.name == name }.flatMap { metric: MetricData ->
            metric.longSumData.points.toList()
        }

    private fun Attributes.asStringMap(): Map<String, String> =
        asMap().entries.associate { (key, value) -> key.key to value.toString() }

    private companion object {
        /** A run id far longer than any run id the product has ever minted. */
        private const val HOSTILE_LENGTH: Int = 4_000

        /** The longest text the published rule lets an attribute carry. */
        private const val MAX_ATTRIBUTE_TEXT: Int = 64
    }
}
