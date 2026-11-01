package dev.rubentxu.pipeline.v2.application

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
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
    private const val DEPLOYMENT_ENVIRONMENT = "deployment.environment.name"
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
        return sdkPipelineTelemetry(sdk)
    }

    internal fun sdkPipelineTelemetry(
        sdk: io.opentelemetry.sdk.OpenTelemetrySdk,
        warning: (String) -> Unit = { System.err.println(it) },
    ): PipelineTelemetry = SdkPipelineTelemetry(sdk, warning)

    private object NoopPipelineTelemetry : PipelineTelemetry {
        private val span = PipelineSpan { }

        override fun startSpan(name: String): PipelineSpan = span

        override fun close() = Unit
    }

    private class SdkPipelineTelemetry(
        private val sdk: io.opentelemetry.sdk.OpenTelemetrySdk,
        private val warning: (String) -> Unit,
    ) : PipelineTelemetry {
        private val tracer = sdk.getTracer(TRACER_SCOPE)

        override fun startSpan(name: String): PipelineSpan {
            val span = tracer.spanBuilder(name).startSpan()
            return PipelineSpan { span.end() }
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
}

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

internal fun withPipelineTelemetry(run: () -> Int): Int {
    return withPipelineTelemetry(PipelineTelemetryFactory.create(), run)
}

internal fun withPipelineTelemetry(telemetry: PipelineTelemetry, run: () -> Int): Int {
    return try {
        run()
    } finally {
        runCatching { telemetry.close() }
    }
}
