package dev.rubentxu.pipeline.v2.sdk

import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Compile-time + runtime guard on 16-field StepDescriptor widening.
 * Ensures HelloPipelineFixture source compiles unchanged (backward compat).
 */
class StepDescriptorSchemaTest {

    @Test
    fun `StepDescriptor has 16 fields`() {
        val descriptor = StepDescriptor(
            stepId = "hello-echo",
            name = "echo",
            configRef = "hello.echo.config",
        )
        // Access all 16 fields to force compile-time verification
        assertEquals("hello-echo", descriptor.stepId)
        assertEquals("echo", descriptor.name)
        assertEquals("hello.echo.config", descriptor.configRef)
        assertEquals("core", descriptor.pluginId)
        assertEquals("0.0.0", descriptor.pluginVersion)
        assertEquals("v1", descriptor.apiVersion)
        assertEquals(ExecutionLocation.WORKER, descriptor.executionLocation)
        assertEquals("{}", descriptor.inputSchema)
        assertEquals("{}", descriptor.outputSchema)
        assertEquals(emptyList<String>(), descriptor.requiredCapabilities)
        assertEquals(emptyList<Effect>(), descriptor.effects)
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
        assertEquals("", descriptor.idempotencyModel)
        assertEquals("", descriptor.timeoutModel)
        assertEquals("", descriptor.jenkinsSurface)
        assertEquals("", descriptor.securityProfile)
        assertEquals("", descriptor.deprecation)
    }

    @Test
    fun `default values are as expected`() {
        val descriptor = StepDescriptor(
            stepId = "test-step",
            name = "test",
            configRef = "test.config",
        )
        assertEquals("core", descriptor.pluginId)
        assertEquals("0.0.0", descriptor.pluginVersion)
        assertEquals("v1", descriptor.apiVersion)
        assertEquals(ExecutionLocation.WORKER, descriptor.executionLocation)
        assertEquals(emptyList<String>(), descriptor.requiredCapabilities)
        assertEquals(emptyList<String>(), descriptor.effects)
        assertEquals(ReplayPolicy.MEMOIZED, descriptor.replayPolicy)
    }
}
