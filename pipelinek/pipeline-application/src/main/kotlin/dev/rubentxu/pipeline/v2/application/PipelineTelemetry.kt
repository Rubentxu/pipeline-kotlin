package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import java.util.concurrent.TimeUnit

/**
 * Application-owned seam for live tracing. Runtime/domain modules do not depend on OpenTelemetry.
 * The CLI opts in with [OTEL_ENABLED_ENV]; otherwise no SDK, exporter, or worker is constructed.
 *
 * ## WHY THE PORT KNOWS WHETHER IT IS ENABLED
 *
 * A disabled runtime must cost NOTHING: no span, no attribute list, no call
 * into an exporter. The only way to guarantee that is for the boundary to be
 * able to ask before it builds anything, so [enabled] is part of the port and
 * the first thing every boundary checks.
 *
 * ## WHY A SPAN CAN BE DISCARDED AND NOT ONLY ENDED
 *
 * A boundary sometimes knows that the work a span describes did NOT happen —
 * a replay skipped an effect that a previous attempt had already performed.
 * Ending the span would publish a span saying the effect ran. Discarding it
 * leaves it unended, and an unended span is never exported. That is the
 * difference between a truthful trace and a flattering one.
 */
internal interface PipelineTelemetry : AutoCloseable {
    /** Whether this runtime produces spans at all. */
    val enabled: Boolean

    /**
     * The parent of the OUTERMOST spans this runtime opens.
     *
     * A run that an operator attached to an existing trace starts inside
     * that trace; a run that was not attached starts its own. The answer is
     * decided once, when the runtime is built, and every outermost span
     * reads it from here — so the decision cannot differ between two spans
     * of the same run.
     */
    val sessionParent: LiveSpanParent

    /** Starts a span with [attributes] under [parent], and hands it to the caller. */
    fun startSpan(name: String, attributes: List<TelemetryAttribute>, parent: LiveSpanParent): PipelineSpan
}

/**
 * A span in flight.
 *
 * The two endings are different on purpose: [end] publishes the span with a
 * status, [discard] publishes nothing at all.
 */
internal interface PipelineSpan {
    /**
     * The identity of this span, when the implementation knows it.
     *
     * A child span is parented by naming its parent's identity, so an
     * implementation that cannot name itself cannot be a parent. `null` means
     * exactly that, and the adapter treats it as "no parent" rather than
     * inventing one.
     */
    val traceIdentity: TraceIdentity? get() = null

    /** Ends the span with [status], so it becomes exportable. */
    fun end(status: LiveSpanStatus)

    /** Abandons the span: it is never ended and therefore never exported. */
    fun discard()
}

/**
 * The telemetry that produces NOTHING.
 *
 * It is the default for a run that did not opt in, and it is also what a
 * composition hands to an engine that must be exercised without a collector.
 * It is a real implementation of the port rather than a null: the port is
 * held by value all the way down, so a boundary never has to ask whether
 * there is an observer — it asks [PipelineTelemetry.enabled], and a disabled
 * one answers before anything is built.
 */
internal object NoopPipelineTelemetry : PipelineTelemetry {
    override val enabled: Boolean = false

    override val sessionParent: LiveSpanParent = LiveSpanParent.Root

    override fun startSpan(
        name: String,
        attributes: List<TelemetryAttribute>,
        parent: LiveSpanParent,
    ): PipelineSpan = NoopPipelineSpan

    override fun close() = Unit
}

/** The span that does nothing, and publishes nothing. */
internal object NoopPipelineSpan : PipelineSpan {
    override fun end(status: LiveSpanStatus) = Unit

    override fun discard() = Unit
}

internal object PipelineTelemetryFactory {
    const val OTEL_ENABLED_ENV: String = "PIPELINEK_OTEL_ENABLED"

    /**
     * The trace context an operator hands to the process.
     *
     * The operator's environment is the ONLY channel this product reads a
     * trace context from. It is a channel the operator controls and the
     * pipeline content does not, which is what makes the value trustworthy
     * enough to become the parent of the run's spans.
     */
    const val OTEL_TRACEPARENT_ENV: String = "PIPELINEK_OTEL_TRACEPARENT"

    private const val SERVICE_NAME = "service.name"
    private const val DEPLOYMENT_ENVIRONMENT = "deployment.environment.name"
    private const val TRACER_SCOPE = "dev.rubentxu.pipeline.v2"

    /** Selects a runtime by an explicit opt-in. Only the literal `true` enables the SDK. */
    internal fun create(
        environment: Map<String, String> = System.getenv(),
        sdkFactory: (LiveSpanParent) -> PipelineTelemetry = { createSdkRuntime(it) },
        warning: (String) -> Unit = { System.err.println(it) },
    ): PipelineTelemetry {
        if (!environment[OTEL_ENABLED_ENV].equals("true", ignoreCase = true)) {
            return NoopPipelineTelemetry
        }
        val sessionParent = sessionParentOf(environment, warning)
        return try {
            sdkFactory(sessionParent)
        } catch (_: Exception) {
            warning("WARNING: OpenTelemetry initialization failed; continuing without telemetry.")
            NoopPipelineTelemetry
        }
    }

    /**
     * The parent of the outermost spans, read from the operator's environment.
     *
     * A value that is not well formed is not an error: the run still happens,
     * it simply starts its own trace. The operator is told that the value was
     * refused — WITHOUT the value itself, because a value an operator typed
     * into an environment is still a value this product does not echo.
     */
    private fun sessionParentOf(
        environment: Map<String, String>,
        warning: (String) -> Unit,
    ): LiveSpanParent {
        val raw = environment[OTEL_TRACEPARENT_ENV]?.trim()
        if (raw.isNullOrEmpty()) return LiveSpanParent.Root
        val parsed = parseTraceParent(raw)
        if (parsed == null) {
            warning("WARNING: the configured trace context is not well formed; the run starts its own trace.")
            return LiveSpanParent.Root
        }
        return LiveSpanParent.Remote(parsed)
    }

    private fun createSdkRuntime(sessionParent: LiveSpanParent): PipelineTelemetry {
        // Keep the official autoconfigure batch processor: its finite queue is configured by
        // OTEL_BSP_* and drops spans rather than blocking the pipeline when full. Do not add a
        // second queue, retry loop, or telemetry about telemetry here.
        val sdk = AutoConfiguredOpenTelemetrySdk.builder()
            .addResourceCustomizer { resource, _ ->
                val configuredName = resource.getAttribute(AttributeKey.stringKey(SERVICE_NAME))
                val version = RuntimeApiVersion.current().let { "${it.major}.${it.minor}.${it.patch}" }
                val deploymentEnvironment = resource.getAttribute(AttributeKey.stringKey(DEPLOYMENT_ENVIRONMENT))
                val safeAttributes = safeTelemetryResourceAttributes(configuredName, version, deploymentEnvironment)
                Resource.builder().apply {
                    safeAttributes.forEach { (key, value) -> put(key, value) }
                }.build()
            }
            .build()
            .openTelemetrySdk
        return sdkPipelineTelemetry(sdk, sessionParent = sessionParent)
    }

    internal fun sdkPipelineTelemetry(
        sdk: io.opentelemetry.sdk.OpenTelemetrySdk,
        warning: (String) -> Unit = { System.err.println(it) },
        sessionParent: LiveSpanParent = LiveSpanParent.Root,
    ): PipelineTelemetry = SdkPipelineTelemetry(sdk, warning, sessionParent)

    private class SdkPipelineTelemetry(
        private val sdk: io.opentelemetry.sdk.OpenTelemetrySdk,
        private val warning: (String) -> Unit,
        override val sessionParent: LiveSpanParent,
    ) : PipelineTelemetry {
        private val tracer = sdk.getTracer(TRACER_SCOPE)

        override val enabled: Boolean = true

        override fun startSpan(
            name: String,
            attributes: List<TelemetryAttribute>,
            parent: LiveSpanParent,
        ): PipelineSpan {
            val builder = tracer.spanBuilder(name)
            applyParent(builder, parent)
            attributes.forEach { attribute ->
                when (attribute) {
                    is TelemetryAttribute.Text -> builder.setAttribute(attribute.key, attribute.value)
                    is TelemetryAttribute.Count -> builder.setAttribute(attribute.key, attribute.value)
                    is TelemetryAttribute.Flag -> builder.setAttribute(attribute.key, attribute.value)
                }
            }
            return SdkPipelineSpan(builder.startSpan())
        }

        /**
         * Attaches the span to the parent the product decided on.
         *
         * A parent with no identity of its own cannot be named, and a span
         * that cannot name its parent is a root span — never a span with an
         * invented link. The root case says so explicitly instead of leaving
         * the ambient context of the tracing library to decide, because that
         * context is exactly the thing this product refuses to depend on.
         */
        private fun applyParent(builder: SpanBuilder, parent: LiveSpanParent) {
            when (parent) {
                LiveSpanParent.Root -> builder.setNoParent()
                is LiveSpanParent.Local -> {
                    val identity = parent.handle.span.traceIdentity
                    if (identity == null) {
                        builder.setNoParent()
                    } else {
                        builder.setParent(parentContext(localSpanContext(identity)))
                    }
                }
                is LiveSpanParent.Remote -> builder.setParent(parentContext(remoteSpanContext(parent.context)))
            }
        }

        override fun close() {
            // A CLI invocation has a finite lifetime. Bound each flush and the shared shutdown so
            // an unavailable collector cannot hold the process open indefinitely. A failed signal
            // must not skip the other providers or the SDK shutdown.
            runTelemetryCleanup(
                actions = listOf(
                    { check(sdk.sdkTracerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS).isSuccess) },
                    { check(sdk.sdkMeterProvider.forceFlush().join(500, TimeUnit.MILLISECONDS).isSuccess) },
                    { check(sdk.sdkLoggerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS).isSuccess) },
                    { check(sdk.shutdown().join(2, TimeUnit.SECONDS).isSuccess) },
                ),
                warning = warning,
            )
        }
    }

    private class SdkPipelineSpan(private val span: io.opentelemetry.api.trace.Span) : PipelineSpan {

        override val traceIdentity: TraceIdentity = TraceIdentity(
            traceId = span.spanContext.traceId,
            spanId = span.spanContext.spanId,
            sampled = span.spanContext.traceFlags.isSampled,
        )

        override fun end(status: LiveSpanStatus) {
            // The failure class is a word from the product's own closed vocabulary, never a
            // message from the run, so it carries nothing a redaction rule would have
            // removed. It is written here because the outcome is only known at the end.
            status.failureClass?.let { span.setAttribute(LiveSpanAttributes.FAILURE_CLASS, it) }
            span.setStatus(if (status == LiveSpanStatus.Completed) StatusCode.OK else StatusCode.ERROR)
            span.end()
        }

        override fun discard() {
            // Deliberately empty: the span is NOT ended, and an unended span is never
            // exported. Ending it here would publish a span describing an effect that the
            // replay skipped.
        }
    }
}

/** The context of a span this process opened: local, and therefore not remote. */
private fun localSpanContext(identity: TraceIdentity): SpanContext = SpanContext.create(
    identity.traceId,
    identity.spanId,
    if (identity.sampled) TraceFlags.getSampled() else TraceFlags.getDefault(),
    TraceState.getDefault(),
)

/** The context of a span another process opened: remote, and therefore marked as such. */
private fun remoteSpanContext(context: RemoteTraceContext): SpanContext = SpanContext.createFromRemoteParent(
    context.traceId,
    context.spanId,
    if (context.sampled) TraceFlags.getSampled() else TraceFlags.getDefault(),
    TraceState.getDefault(),
)

/**
 * The context that carries [spanContext] as the parent.
 *
 * It is built on the ROOT context and never on the ambient one. The ambient
 * context is the tracing library's own thread-local, and a parent read from
 * it would be a parent nobody declared — the very ambiguity this product
 * refuses. The parent of a span is always the one this product named.
 */
private fun parentContext(spanContext: SpanContext): io.opentelemetry.context.Context =
    io.opentelemetry.context.Context.root().with(io.opentelemetry.api.trace.Span.wrap(spanContext))

private val SAFE_RESOURCE_VALUE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

/** Only short, operator-supplied service identity is retained from SDK resource discovery. */
internal fun safeTelemetryResourceAttributes(
    configuredServiceName: String?,
    serviceVersion: String,
    deploymentEnvironment: String?,
): Map<String, String> = buildMap {
    val serviceName = configuredServiceName
        ?.takeUnless { it.startsWith("unknown_service") }
        ?.takeIf(SAFE_RESOURCE_VALUE::matches)
        ?: "pipelinek"
    put("service.name", serviceName)
    put("service.version", serviceVersion.takeIf(SAFE_RESOURCE_VALUE::matches) ?: "unknown")
    deploymentEnvironment
        ?.takeIf(SAFE_RESOURCE_VALUE::matches)
        ?.let { put("deployment.environment.name", it) }
}

/** Attempts every bounded cleanup action and emits one sanitized diagnostic if any failed. */
internal fun runTelemetryCleanup(
    actions: List<() -> Unit>,
    warning: (String) -> Unit,
) {
    var failed = false
    actions.forEach { action ->
        try {
            action()
        } catch (_: Exception) {
            failed = true
        }
    }
    if (failed) {
        warning("WARNING: OpenTelemetry export or shutdown failed; pipeline result is unchanged.")
    }
}

/**
 * Runs [run] with a telemetry runtime and closes the runtime afterwards.
 *
 * The runtime is HANDED to the run rather than kept here, because the
 * boundaries that produce spans live inside the run and the runtime has to
 * reach them. The close still happens here, in a `finally`, so a run that
 * throws still flushes what it produced — and a close that throws still does
 * not change the run's exit code.
 */
internal fun withPipelineTelemetry(run: (PipelineTelemetry) -> Int): Int {
    return withPipelineTelemetry(PipelineTelemetryFactory.create(), run)
}

internal fun withPipelineTelemetry(telemetry: PipelineTelemetry, run: (PipelineTelemetry) -> Int): Int {
    return try {
        run(telemetry)
    } finally {
        runCatching { telemetry.close() }
    }
}
