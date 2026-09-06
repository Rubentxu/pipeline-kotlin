package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for StepDescriptorRegistry (EM-4).
 */
class StepDescriptorRegistryTest {

    private val registry = StepDescriptorRegistry.standard()

    @Test
    fun `core catchError has takesBody true`() {
        val descriptor = registry.get(PluginStepId("core.catchError"))
        assertNotNull(descriptor)
        assertTrue(descriptor!!.takesBody)
        assertEquals(BodyInvocationPolicy.ONCE, descriptor.bodyInvocations)
        assertTrue(descriptor.catchesInterruptions)
    }

    @Test
    fun `core warnError has takesBody true`() {
        val descriptor = registry.get(PluginStepId("core.warnError"))
        assertNotNull(descriptor)
        assertTrue(descriptor!!.takesBody)
        assertEquals(BodyInvocationPolicy.AT_MOST_ONCE, descriptor.bodyInvocations)
    }

    @Test
    fun `core withEnv has takesBody true`() {
        val descriptor = registry.get(PluginStepId("core.withEnv"))
        assertNotNull(descriptor)
        assertTrue(descriptor!!.takesBody)
        assertEquals(ContextKind.ENVIRONMENT, descriptor.introducesContext)
    }

    @Test
    fun `core dir has takesBody true`() {
        val descriptor = registry.get(PluginStepId("core.dir"))
        assertNotNull(descriptor)
        assertTrue(descriptor!!.takesBody)
        assertEquals(ContextKind.CWD, descriptor.introducesContext)
    }

    @Test
    fun `core withCredentials has takesBody true`() {
        val descriptor = registry.get(PluginStepId("core.withCredentials"))
        assertNotNull(descriptor)
        assertTrue(descriptor!!.takesBody)
        assertEquals(ContextKind.CREDENTIALS, descriptor.introducesContext)
    }

    @Test
    fun `core timeout has takesBody true`() {
        val descriptor = registry.get(PluginStepId("core.timeout"))
        assertNotNull(descriptor)
        assertTrue(descriptor!!.takesBody)
        assertEquals(ContextKind.CANCELLATION, descriptor.introducesContext)
    }

    @Test
    fun `core retry has takesBody true with ZERO_OR_MORE`() {
        val descriptor = registry.get(PluginStepId("core.retry"))
        assertNotNull(descriptor)
        assertTrue(descriptor!!.takesBody)
        assertEquals(BodyInvocationPolicy.ZERO_OR_MORE, descriptor.bodyInvocations)
    }

    @Test
    fun `core emit event has takesBody false`() {
        val descriptor = registry.get(PluginStepId("core.emit.event"))
        assertNotNull(descriptor)
        assertFalse(descriptor!!.takesBody)
    }

    @Test
    fun `core sh has takesBody false`() {
        val descriptor = registry.get(PluginStepId("core.sh"))
        assertNotNull(descriptor)
        assertFalse(descriptor!!.takesBody)
    }

    @Test
    fun `core echo has takesBody false`() {
        val descriptor = registry.get(PluginStepId("core.echo"))
        assertNotNull(descriptor)
        assertFalse(descriptor!!.takesBody)
    }

    @Test
    fun `unknown step returns null`() {
        val descriptor = registry.get(PluginStepId("core.unknown"))
        assertNull(descriptor)
    }
}
