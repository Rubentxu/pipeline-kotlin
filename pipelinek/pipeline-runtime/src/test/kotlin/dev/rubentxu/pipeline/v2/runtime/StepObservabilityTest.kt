package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationName
import dev.rubentxu.pipeline.v2.runtime.telemetry.ObservationStatus
import dev.rubentxu.pipeline.v2.runtime.telemetry.inSpan
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the observability adapter publishes for a plugin's own work.
 *
 * ## THE GUARANTEE
 *
 * An observation a plugin opens is part of the run's own trace and hangs
 * under the step that caused it; it carries no payload of its own; the
 * measure it records is labelled with the product's own closed vocabulary;
 * and a telemetry that cannot record is an observation that is LOST, never a
 * failure of the work it was watching.
 *
 * ## THE CAPACITY IT SERVES
 *
 * The reader of a trace decides whether to trust it by asking two questions:
 * is this observation really about the step it appears under, and does it
 * carry anything it should not. A plugin's observation that floated beside
 * the run, or that carried a fragment of output, would make the whole trace
 * untrustworthy for the steps that did nothing wrong — and the plugin is the
 * one code this product does not own.
 *
 * ## WHY THE LAST ROW MATTERS MOST
 *
 * The work a plugin does is the product's effect. If a collector that is down
 * could make that effect fail, then the observability would decide the
 * pipeline's result, and a pipeline's result would stop depending on the
 * pipeline. So every call into the library is guarded, and the row below
 * breaks the collector on purpose to prove the work still finished.
 */
class StepObservabilityTest {

    @Test
    fun `the observation a plugin opens hangs under the step that caused it`() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryWith(exporter)

        // The step's own span, exactly as the engine opens it.
        val dispatch = LiveSpans.open(telemetry, LiveSpanNames.STEP_DISPATCH, LiveSpanParent.Root)!!
        val invoke = LiveSpans.open(telemetry, LiveSpanNames.STEP_INVOKE, liveSpanParentOf(dispatch))!!

        // The seam the engine hands the plugin, under the same cause as the
        // step's own execution.
        val observability = telemetry.stepObservability(liveSpanParentOf(dispatch))
        observability.openSpan(ObservationName.of("uppercase.transform"))
            .end(ObservationStatus.Succeeded)

        LiveSpans.end(invoke, LiveSpanStatus.Completed)
        LiveSpans.end(dispatch, LiveSpanStatus.Completed)

        val observation = exporter.exported.single { it.name == "pipelinek.plugin.uppercase.transform" }
        val step = exporter.exported.single { it.name == LiveSpanNames.STEP_DISPATCH }

        assertEquals(step.spanId, observation.parentSpanId, "the observation hangs under the step")
        assertEquals(step.traceId, observation.traceId, "the observation belongs to the run's trace")
    }

    @Test
    fun `the observation carries no payload of its own`() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryWith(exporter)

        val observability = telemetry.stepObservability(LiveSpanParent.Root)
        observability.openSpan(ObservationName.of("uppercase.transform"))
            .end(ObservationStatus.Succeeded)

        val observation = exporter.exported.single { it.name == "pipelinek.plugin.uppercase.transform" }

        assertTrue(
            observation.attributes.isEmpty(),
            "a plugin's observation carries no attribute: the name is the whole of it",
        )
    }

    @Test
    fun `a discarded observation is never published`() {
        val exporter = LiveSpanExporter()
        val telemetry = telemetryWith(exporter)

        val observability = telemetry.stepObservability(LiveSpanParent.Root)
        observability.openSpan(ObservationName.of("uppercase.transform")).discard()

        assertTrue(
            exporter.exported.none { it.name.startsWith("pipelinek.plugin.") },
            "an observation that did not happen publishes nothing",
        )
    }

    @Test
    fun `a counted observation is published with the product's own dimensions`() {
        val exporter = LiveSpanExporter()
        val reader = InMemoryMetricReader.create()
        val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build(),
            )
            .setMeterProvider(provider)
            .build()
        val telemetry = PipelineTelemetryFactory.sdkPipelineTelemetry(sdk)

        val observability = telemetry.stepObservability(LiveSpanParent.Root)
        observability.count(ObservationName.of("uppercase.transform"), ObservationStatus.Failed)

        val counter = metric(reader, "pipelinek.plugin.uppercase.transform.total")
        val point = counter.data.points.single() as LongPointData

        assertEquals(1L, point.value)
        assertEquals(
            mapOf(
                MetricDimensions.OPERATION_KIND to "plugin.execute",
                MetricDimensions.STATUS to "failure",
                MetricDimensions.FAILURE_CLASS to "PLUGIN",
            ),
            point.attributes.asStringMap(),
        )
    }

    @Test
    fun `an observation that cannot be recorded does not fail the work it observes`() {
        val ledger = LiveSpanLedger()
        val provider = SdkMeterProvider.builder()
            .registerMetricReader(InMemoryMetricReader.create())
            .build()
        val observability = SdkStepObservability(
            telemetry = ledger,
            meter = provider.meterBuilder("pipelinek-test").build(),
            parent = LiveSpanParent.Root,
        )

        val value = observability.inSpan(ObservationName.of("uppercase.transform")) {
            // The collector disappears WHILE the work runs: ending the span throws.
            ledger.opened.single().failOnEnd = true
            "the work finished"
        }

        assertEquals("the work finished", value, "the work the plugin did is not the telemetry's business")
        assertNull(
            ledger.opened.single().endedWith,
            "the collector really did break: the span never recorded an ending",
        )
    }

    private fun metric(reader: InMemoryMetricReader, name: String): MetricData =
        reader.collectAllMetrics().firstOrNull { it.name == name }
            ?: error("the metric '$name' was not published")

    /**
     * The library's attributes as the plain string map the product speaks.
     *
     * The library keys its attributes by object and the product keys them by
     * name, so the comparison needs the name. Reading the name off the key is
     * what a collector does when it labels a series.
     */
    private fun Attributes.asStringMap(): Map<String, String> =
        asMap().entries.associate { (key, value) -> key.key to value.toString() }
}
