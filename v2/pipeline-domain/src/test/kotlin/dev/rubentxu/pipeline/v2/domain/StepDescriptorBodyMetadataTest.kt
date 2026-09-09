package dev.rubentxu.pipeline.v2.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for StepDescriptor body-metadata fields (EM-4).
 */
class StepDescriptorBodyMetadataTest {

    @Test
    fun `default StepDescriptor has safe body-metadata values`() {
        val descriptor = StepDescriptor("x", "y", "z")

        assertFalse(descriptor.takesBody, "Default takesBody should be false")
        assertEquals(BodyInvocationPolicy.ONCE, descriptor.bodyInvocations, "Default bodyInvocations should be ONCE")
        assertNull(descriptor.introducesContext, "Default introducesContext should be null")
        assertFalse(descriptor.catchesInterruptions, "Default catchesInterruptions should be false")
    }

    @Test
    fun `explicit body-metadata values are preserved`() {
        val descriptor = StepDescriptor(
            stepId = "test-step",
            name = "TestStep",
            configRef = "test-config",
            takesBody = true,
            bodyInvocations = BodyInvocationPolicy.ZERO_OR_MORE,
            introducesContext = ContextKind.ENVIRONMENT,
            catchesInterruptions = true,
        )

        assertTrue(descriptor.takesBody)
        assertEquals(BodyInvocationPolicy.ZERO_OR_MORE, descriptor.bodyInvocations)
        assertTrue(descriptor.introducesContext is ContextKind.ENVIRONMENT)
        assertTrue(descriptor.catchesInterruptions)
    }

    @Test
    fun `BodyInvocationPolicy has three variants`() {
        val once = BodyInvocationPolicy.ONCE
        val zeroOrMore = BodyInvocationPolicy.ZERO_OR_MORE
        val atMostOnce = BodyInvocationPolicy.AT_MOST_ONCE

        assertNotNull(once)
        assertNotNull(zeroOrMore)
        assertNotNull(atMostOnce)

        assertTrue(once is BodyInvocationPolicy.ONCE)
        assertTrue(zeroOrMore is BodyInvocationPolicy.ZERO_OR_MORE)
        assertTrue(atMostOnce is BodyInvocationPolicy.AT_MOST_ONCE)
    }

    @Test
    fun `ContextKind has five variants`() {
        val env = ContextKind.ENVIRONMENT
        val cwd = ContextKind.CWD
        val creds = ContextKind.CREDENTIALS
        val output = ContextKind.OUTPUT_DECORATOR
        val cancel = ContextKind.CANCELLATION

        assertTrue(env is ContextKind.ENVIRONMENT)
        assertTrue(cwd is ContextKind.CWD)
        assertTrue(creds is ContextKind.CREDENTIALS)
        assertTrue(output is ContextKind.OUTPUT_DECORATOR)
        assertTrue(cancel is ContextKind.CANCELLATION)
    }

    @Test
    fun `existing StepDescriptor constructor still compiles after A4-1 added recoveryPolicy`() {
        // Regression guard: a consumer that supplies positional arguments up to the
        // `recoveryPolicy` slot (added by G3-A4.1.1) must still compile and produce a
        // descriptor with sensible defaults. The named-only fields after recoveryPolicy
        // (`idempotencyModel`, `timeoutModel`, `jenkinsSurface`, `securityProfile`,
        // `deprecation`, `takesBody`, `bodyInvocations`, `introducesContext`,
        // `catchesInterruptions`) all keep defaults; this regression guard exercises the
        // pre-A4.1 prefix through the post-A4.1 fields.
        @Suppress("DEPRECATION")
        val descriptor = StepDescriptor(
            "step-id",
            "step-name",
            "config-ref",
            "core",
            "1.0.0",
            "v1",
            ExecutionLocation.WORKER,
            "{}",
            "{}",
            listOf("cap1"),
            emptyList(),
            dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy.MEMOIZED,
            dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.None,
            "idempotent",
            "timeout-model",
            "jenkins-surface",
            "security-profile",
            "deprecated-message",
        )

        assertEquals("step-id", descriptor.stepId)
        assertEquals("step-name", descriptor.name)
        assertEquals("config-ref", descriptor.configRef)
        // New fields should have defaults
        assertFalse(descriptor.takesBody)
        assertEquals(BodyInvocationPolicy.ONCE, descriptor.bodyInvocations)
        // A4-1: pre-decode recovery policy defaults to None.
        assertEquals(dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.None, descriptor.recoveryPolicy)
    }
}
