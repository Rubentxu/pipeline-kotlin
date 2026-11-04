package dev.rubentxu.pipeline.v2.runtime

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The exporter the export rows read.
 *
 * ## WHY THIS FILE IS A COPY
 *
 * The engine's own unit tests keep the same harness in
 * `pipeline-runtime/src/test`, and this lane cannot reach it: a test source
 * set is not on another module's classpath, and there is no shared test
 * module to hang it on. The harness is copied rather than moved because
 * moving it would take the engine's unit tests' instrument away from them.
 *
 * ## THE SEAM
 *
 * Everything above the exporter is the product, and everything below it is the
 * wire. Replacing the exporter with a list keeps the rows honest about what a
 * collector would have received, at the price of a network and a collector this
 * product does not have. It is the ONLY instrument that can fail when the
 * adapter drops a parent, a status or an attribute on the way out.
 *
 * ## WHY THE LIST IS SAFE TO WRITE CONCURRENTLY
 *
 * The engine dispatches branches on another dispatcher, and two branches may
 * export at the same time. A plain list would lose a span here or throw a
 * concurrent modification, and the row would then be measuring the fake
 * instead of the product.
 */
internal class LiveSpanExporter : SpanExporter {

    /** The spans the SDK would have sent to a collector. */
    private val received = CopyOnWriteArrayList<SpanData>()

    /** The spans the SDK would have sent to a collector, in the order they arrived. */
    val exported: List<SpanData> get() = received.toList()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        received += spans
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/** An SDK whose only processor forwards finished spans to [exporter]. */
internal fun sdkWith(exporter: LiveSpanExporter): OpenTelemetrySdk =
    OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build(),
        )
        .build()

/**
 * A telemetry that exports into a list.
 *
 * The exporter is the seam: everything above it is the product, and
 * everything below it is the wire.
 */
internal fun telemetryWith(exporter: LiveSpanExporter): PipelineTelemetry =
    PipelineTelemetryFactory.sdkPipelineTelemetry(sdkWith(exporter))

/**
 * A telemetry whose outermost spans hang under the operator's context.
 *
 * The factory is the only place that reads the context, so a row goes
 * through it rather than handing the adapter a parent by hand: a parent
 * handed by hand would not prove that the operator's channel works.
 */
internal fun telemetryAttachedTo(exporter: LiveSpanExporter, traceParent: String): PipelineTelemetry {
    val sdk = sdkWith(exporter)
    return PipelineTelemetryFactory.create(
        environment = mapOf(
            PipelineTelemetryFactory.OTEL_ENABLED_ENV to "true",
            PipelineTelemetryFactory.OTEL_TRACEPARENT_ENV to traceParent,
        ),
        sdkFactory = { parent -> PipelineTelemetryFactory.sdkPipelineTelemetry(sdk, sessionParent = parent) },
        warning = { },
    )
}
