package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.runtime.telemetry.NoopStepObservability
import dev.rubentxu.pipeline.v2.runtime.telemetry.StepObservability
import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
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
interface PipelineTelemetry : AutoCloseable {
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

    /**
     * The metrics this runtime produces.
     *
     * A span says what HAPPENED along one path; a metric says how often and
     * how long, across every run. The two answer different questions and
     * neither replaces the other, so the runtime exposes both.
     *
     * The default is the metrics that produce nothing. A runtime that does
     * not measure never has to be told so: the boundaries ask the port, and
     * the port answers before anything is built.
     */
    val metrics: PipelineMetrics get() = NoopPipelineMetrics

    /**
     * The seam a step observes itself through.
     *
     * The parent is the span of the step the seam is handed to, decided by
     * the engine before the handler ran. The default observes nothing: a
     * runtime that does not trace has nothing to hand down, and a step that
     * asked for observation still gets a seam — one that observes nothing —
     * so the step has a single code path.
     */
    fun stepObservability(parent: LiveSpanParent): StepObservability = NoopStepObservability
}

/**
 * A span in flight.
 *
 * The two endings are different on purpose: [end] publishes the span with a
 * status, [discard] publishes nothing at all.
 */
interface PipelineSpan {
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
 *
 * It is PUBLIC because it is part of what the engine offers its consumers:
 * a caller that composes an engine by hand needs a telemetry that produces
 * nothing, and there is exactly one, so it is handed over rather than
 * re-implemented on the other side of the seam.
 */
object NoopPipelineTelemetry : PipelineTelemetry {
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

object PipelineTelemetryFactory {
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
    private const val METRIC_SCOPE = "dev.rubentxu.pipeline.v2"
    private const val SHUTDOWN_BOUND_MILLIS = 2_000L
    private const val SHUTDOWN_THREAD_NAME = "pipelinek-telemetry-shutdown"

    /**
     * Selects a runtime by an explicit opt-in. Only the literal `true` enables the SDK.
     *
     * It is public because a caller that composes the engine by hand decides
     * whether the run is observed: the engine does not read the operator's
     * environment on its own, and the seam that carries the decision has to
     * be reachable from the side that makes it.
     */
    fun create(
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

    /**
     * The telemetry over an SDK the caller already built.
     *
     * It is public because the SDK is a fact the CALLER owns: the engine
     * consumes it, and a caller that built its own SDK has to be able to
     * hand it over instead of having the engine build a second one.
     */
    fun sdkPipelineTelemetry(
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

        private val meter = sdk.getMeter(METRIC_SCOPE)

        override val enabled: Boolean = true

        override val metrics: PipelineMetrics = SdkPipelineMetrics(meter)

        override fun stepObservability(parent: LiveSpanParent): StepObservability =
            SdkStepObservability(telemetry = this, meter = meter, parent = parent)

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
            // A CLI invocation has a finite lifetime. Each flush and the shared shutdown is bounded,
            // so an unavailable collector holds the way out for at most the sum of the bounds. A
            // failed signal must not skip the other providers or the SDK shutdown.
            //
            // Each step of the way out carries the reason it is, so a failed export can be
            // COUNTED and not only reported on stderr: a reader of the metrics then sees that
            // the telemetry did not survive the trip, which is a fact no amount of local
            // success can replace. The reason and the action live in ONE list, so the
            // mapping from a failed step to its reason cannot drift.
            val wayOut = listOf(
                MetricExportLoss.TracerFlush to
                    { check(sdk.sdkTracerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS).isSuccess) },
                MetricExportLoss.MeterFlush to
                    { check(sdk.sdkMeterProvider.forceFlush().join(500, TimeUnit.MILLISECONDS).isSuccess) },
                MetricExportLoss.LoggerFlush to
                    { check(sdk.sdkLoggerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS).isSuccess) },
                MetricExportLoss.Shutdown to
                    { check(shutDownWithin(SHUTDOWN_BOUND_MILLIS)) },
            )
            runTelemetryCleanup(
                actions = wayOut.map { it.second },
                warning = warning,
                onFailure = { index -> metrics.exportLoss(wayOut[index].first) },
            )
        }

        /**
         * Whether the SDK shut down within [millis].
         *
         * The shutdown is more than the result it returns: the periodic metric reader waits, on
         * the thread that shuts it down, for the export in flight and then for a last export,
         * each for up to five seconds. So the call is made on a daemon thread of its own, and the
         * bound is the one this close declares and not the reader's; a shutdown still under way
         * when the bound is reached does not hold the process open.
         */
        private fun shutDownWithin(millis: Long): Boolean {
            val shutdown = CompletableResultCode()
            val worker = Thread({
                try {
                    val result = sdk.shutdown()
                    result.whenComplete { if (result.isSuccess) shutdown.succeed() else shutdown.fail() }
                } catch (_: Exception) {
                    shutdown.fail()
                }
            }, SHUTDOWN_THREAD_NAME)
            worker.isDaemon = true
            worker.start()
            return shutdown.join(millis, TimeUnit.MILLISECONDS).isSuccess
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
    // The producer's name. This product publishes the same telemetry twice
    // over — once while the run happens, once read back from the journal
    // afterwards — and a backend that receives both can only tell them apart
    // if the document says which one it is. The label is a fact about the
    // PRODUCER, so it belongs to the resource and not to the spans.
    put(TelemetrySource.KEY, TelemetrySource.LIVE)
}

/**
 * Attempts every bounded cleanup action and emits one sanitized diagnostic if any failed.
 *
 * [onFailure] is told WHICH action failed, right after it failed, so a caller
 * that keeps a count of its own losses can add to it before the next action
 * runs. It is told nothing else: a failure here is a fact about the export,
 * and the only thing that may be published about it is the class of the
 * failure.
 *
 * A failure to publish the loss must not stop the remaining cleanup, so the
 * callback is itself guarded. A telemetry that cannot report its own loss
 * still has to shut down cleanly.
 */
internal fun runTelemetryCleanup(
    actions: List<() -> Unit>,
    warning: (String) -> Unit,
    onFailure: (Int) -> Unit = {},
) {
    var failed = false
    actions.forEachIndexed { index, action ->
        try {
            action()
        } catch (_: Exception) {
            failed = true
            runCatching { onFailure(index) }
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
fun withPipelineTelemetry(run: (PipelineTelemetry) -> Int): Int {
    return withPipelineTelemetry(PipelineTelemetryFactory.create(), run)
}

fun withPipelineTelemetry(telemetry: PipelineTelemetry, run: (PipelineTelemetry) -> Int): Int {
    return try {
        run(telemetry)
    } finally {
        runCatching { telemetry.close() }
    }
}
