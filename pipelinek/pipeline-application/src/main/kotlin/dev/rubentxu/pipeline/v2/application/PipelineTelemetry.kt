package dev.rubentxu.pipeline.v2.application

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import java.util.concurrent.TimeUnit

/**
 * Application-owned seam for live tracing. Runtime/domain modules do not depend on OpenTelemetry.
 * The CLI opts in with [OTEL_ENABLED_ENV]; otherwise no SDK, exporter, or worker is constructed.
 */
internal interface PipelineTelemetry : AutoCloseable {
    fun startSpan(name: String): PipelineSpan
}

internal fun interface PipelineSpan : AutoCloseable

internal object PipelineTelemetryFactory {
    const val OTEL_ENABLED_ENV: String = "PIPELINEK_OTEL_ENABLED"
    private const val SERVICE_NAME = "service.name"
    private const val SERVICE_VERSION = "service.version"
    private const val TRACER_SCOPE = "dev.rubentxu.pipeline.v2"

    /** Selects a runtime by an explicit opt-in. Only the literal `true` enables the SDK. */
    internal fun create(
        environment: Map<String, String> = System.getenv(),
        sdkFactory: () -> PipelineTelemetry = { createSdkRuntime() },
        warning: (String) -> Unit = { System.err.println(it) },
    ): PipelineTelemetry {
        if (!environment[OTEL_ENABLED_ENV].equals("true", ignoreCase = true)) {
            return NoopPipelineTelemetry
        }
        return try {
            sdkFactory()
        } catch (_: Exception) {
            warning("WARNING: OpenTelemetry initialization failed; continuing without telemetry.")
            NoopPipelineTelemetry
        }
    }

    private fun createSdkRuntime(): PipelineTelemetry {
        val sdk = AutoConfiguredOpenTelemetrySdk.builder()
            .addResourceCustomizer { resource, _ ->
                val configuredName = resource.getAttribute(AttributeKey.stringKey(SERVICE_NAME))
                val version = RuntimeApiVersion.current().let { "${it.major}.${it.minor}.${it.patch}" }
                resource.toBuilder()
                    .put(SERVICE_NAME, configuredName?.takeUnless { it.startsWith("unknown_service") } ?: "pipelinek")
                    .put(SERVICE_VERSION, version)
                    .build()
            }
            .build()
            .openTelemetrySdk
        return SdkPipelineTelemetry(sdk)
    }

    private object NoopPipelineTelemetry : PipelineTelemetry {
        private val span = PipelineSpan { }

        override fun startSpan(name: String): PipelineSpan = span

        override fun close() = Unit
    }

    private class SdkPipelineTelemetry(
        private val sdk: io.opentelemetry.sdk.OpenTelemetrySdk,
    ) : PipelineTelemetry {
        private val tracer = sdk.getTracer(TRACER_SCOPE)

        override fun startSpan(name: String): PipelineSpan {
            val span = tracer.spanBuilder(name).startSpan()
            return PipelineSpan { span.end() }
        }

        override fun close() {
            // A CLI invocation has a finite lifetime. Bound each flush and the shared shutdown so
            // an unavailable collector cannot hold the process open indefinitely.
            sdk.sdkTracerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS)
            sdk.sdkMeterProvider.forceFlush().join(500, TimeUnit.MILLISECONDS)
            sdk.sdkLoggerProvider.forceFlush().join(500, TimeUnit.MILLISECONDS)
            sdk.shutdown().join(2, TimeUnit.SECONDS)
        }
    }
}

internal fun withPipelineTelemetry(run: () -> Int): Int {
    val telemetry = PipelineTelemetryFactory.create()
    return try {
        run()
    } finally {
        runCatching { telemetry.close() }
    }
}
