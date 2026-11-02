package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.telemetry.TelemetryAttribute
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineTelemetryFactoryTest {
    @Test
    fun disabledByDefaultDoesNotConstructTheSdkOrPrintAnything() {
        var sdkCreated = false
        val warnings = mutableListOf<String>()

        val telemetry = PipelineTelemetryFactory.create(
            environment = emptyMap(),
            sdkFactory = {
                sdkCreated = true
                RecordingTelemetry
            },
            warning = warnings::add,
        )

        telemetry.startSpan("run", emptyList(), LiveSpanParent.Root).end(LiveSpanStatus.Completed)
        telemetry.close()

        assertEquals(false, sdkCreated)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun explicitTrueSelectsTheSdkAdapter() {
        var sdkCreated = false
        val telemetry = PipelineTelemetryFactory.create(
            environment = mapOf(PipelineTelemetryFactory.OTEL_ENABLED_ENV to "true"),
            sdkFactory = {
                sdkCreated = true
                RecordingTelemetry
            },
            warning = {},
        )

        telemetry.close()

        assertEquals(true, sdkCreated)
    }

    @Test
    fun sdkInitializationFailureFallsBackWithoutExposingTheCause() {
        val warnings = mutableListOf<String>()
        val telemetry = PipelineTelemetryFactory.create(
            environment = mapOf(PipelineTelemetryFactory.OTEL_ENABLED_ENV to "true"),
            sdkFactory = { throw IllegalStateException("credential-value") },
            warning = warnings::add,
        )

        telemetry.startSpan("run", emptyList(), LiveSpanParent.Root).end(LiveSpanStatus.Completed)
        telemetry.close()

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("continuing without telemetry"))
        assertEquals(false, warnings.single().contains("credential-value"))
    }

    @Test
    fun resourceKeepsOnlyBoundedServiceIdentityAndEnvironment() {
        val attributes = safeTelemetryResourceAttributes(
            configuredServiceName = "pipelinek.worker",
            serviceVersion = "2.4.1",
            deploymentEnvironment = "production-eu1",
        )

        assertEquals(
            mapOf(
                "service.name" to "pipelinek.worker",
                "service.version" to "2.4.1",
                "deployment.environment.name" to "production-eu1",
                TelemetrySource.KEY to TelemetrySource.LIVE,
            ),
            attributes,
        )
        assertTrue(attributes.values.none { it.contains('/') || it.contains('\\') })
    }

    @Test
    fun unsafeResourceValuesAreRejectedWithoutLeakingIntoFallback() {
        val attributes = safeTelemetryResourceAttributes(
            configuredServiceName = "/home/user/private/token",
            serviceVersion = "2.4.1",
            deploymentEnvironment = "prod\nsecret",
        )

        assertEquals("pipelinek", attributes["service.name"])
        assertEquals("2.4.1", attributes["service.version"])
        assertTrue("deployment.environment.name" !in attributes)

        val overlongName = safeTelemetryResourceAttributes("s".repeat(65), "2.4.1", null)
        assertEquals("pipelinek", overlongName["service.name"])
    }

    @Test
    fun failedFlushDoesNotSkipRemainingCleanupOrExposeFailureDetails() {
        val attempted = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        runTelemetryCleanup(
            actions = listOf(
                {
                    attempted += "trace-flush"
                    error("authorization-header-secret")
                },
                { attempted += "metrics-flush" },
                { attempted += "sdk-shutdown" },
            ),
            warning = warnings::add,
        )

        assertEquals(listOf("trace-flush", "metrics-flush", "sdk-shutdown"), attempted)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("result is unchanged"))
        assertTrue(!warnings.single().contains("authorization-header-secret"))
    }

    @Test
    fun exporterShutdownFailureDoesNotChangeTheRunExitCode() {
        val telemetry = object : PipelineTelemetry {
            override val enabled: Boolean = true
            override val sessionParent: LiveSpanParent = LiveSpanParent.Root
            override fun startSpan(
                name: String,
                attributes: List<TelemetryAttribute>,
                parent: LiveSpanParent,
            ): PipelineSpan = RecordingSpan
            override fun close() = error("collector-unavailable")
        }

        val exitCode = withPipelineTelemetry(telemetry) { 17 }

        assertEquals(17, exitCode)
    }

    @Test
    fun failingInMemoryExporterCannotChangeTheRunExitCodeOrLeakItsCause() {
        var exportedSpans = 0
        val warnings = mutableListOf<String>()
        val exporter = object : SpanExporter {
            override fun export(spans: Collection<SpanData>): CompletableResultCode {
                exportedSpans += spans.size
                return CompletableResultCode.ofFailure()
            }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode =
                CompletableResultCode.ofExceptionalFailure(IllegalStateException("authorization-header-secret"))
        }
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
            .build()
        val telemetry = PipelineTelemetryFactory.sdkPipelineTelemetry(sdk, warnings::add)

        val exitCode = withPipelineTelemetry(telemetry) {
            telemetry.startSpan("pipelinek.run", emptyList(), LiveSpanParent.Root).end(LiveSpanStatus.Completed)
            17
        }

        assertEquals(17, exitCode)
        assertEquals(1, exportedSpans)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("result is unchanged"))
        assertTrue(!warnings.single().contains("authorization-header-secret"))
    }

    private object RecordingTelemetry : PipelineTelemetry {
        override val enabled: Boolean = true
        override val sessionParent: LiveSpanParent = LiveSpanParent.Root
        override fun startSpan(
            name: String,
            attributes: List<TelemetryAttribute>,
            parent: LiveSpanParent,
        ): PipelineSpan = RecordingSpan
        override fun close() = Unit
    }

    private object RecordingSpan : PipelineSpan {
        override fun end(status: LiveSpanStatus) = Unit
        override fun discard() = Unit
    }
}
