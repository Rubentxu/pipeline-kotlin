package dev.rubentxu.pipeline.v2.application

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

        telemetry.startSpan("run").close()
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

        telemetry.startSpan("run").close()
        telemetry.close()

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("continuing without telemetry"))
        assertEquals(false, warnings.single().contains("credential-value"))
    }

    private object RecordingTelemetry : PipelineTelemetry {
        override fun startSpan(name: String): PipelineSpan = PipelineSpan { }
        override fun close() = Unit
    }
}
