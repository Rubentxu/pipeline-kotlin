package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for StepDescriptor body declaration (EM-4; reworked by B10 / W1d).
 *
 * W1d replaced the six independent body-metadata fields with one coherent value,
 * [StepBody]. These tests are the behavioural half of that change: a Step either declares
 * no body at all, or declares a body together with its cardinality, owner and shape.
 */
class StepDescriptorBodyMetadataTest {

    @Test
    fun `a StepDescriptor that declares nothing has no body`() {
        val descriptor = StepDescriptor("x", "y", "z")

        assertEquals(
            StepBody.None,
            descriptor.body,
            "The default must be 'no body': it is the only value that cannot imply semantics " +
                "the Step never stated",
        )
        assertNull(
            descriptor.body.declared,
            "A terminal Step has no declaration, so it has no cardinality, owner or shape to read",
        )
    }

    @Test
    fun `explicit body values are preserved`() {
        val declared = StepBody.Declared(
            invocation = BodyInvocationPolicy.ZERO_OR_MORE,
            execution = BodyExecution(
                owner = BodyExecutionOwner.CANONICAL_ENGINE,
                policy = BodyExecutionPolicy.Sequential,
            ),
            introduces = ContextKind.ENVIRONMENT,
            catchesInterruptions = true,
        )
        val descriptor = StepDescriptor(
            stepId = "test-step",
            name = "TestStep",
            configRef = "test-config",
            body = declared,
        )

        assertEquals(declared, descriptor.body)
        assertEquals(BodyInvocationPolicy.ZERO_OR_MORE, descriptor.body.declared?.invocation)
        assertEquals(
            BodyExecutionOwner.CANONICAL_ENGINE,
            descriptor.body.declared?.execution?.owner,
        )
        assertEquals(BodyExecutionPolicy.Sequential, descriptor.body.declared?.execution?.policy)
        assertTrue(descriptor.body.declared?.introduces is ContextKind.ENVIRONMENT)
        assertTrue(descriptor.body.declared?.catchesInterruptions == true)
    }

    /**
     * W1d's exit criterion, as a behaviour: ownership and shape are REQUIRED by the
     * constructor, so "this Step takes a body and nobody owns it" has no spelling. The old
     * model defaulted the owner to CANONICAL_ENGINE, which silently granted canonical
     * semantics to any Step that forgot to state them.
     */
    @Test
    fun `a declared body cannot omit its owner or shape`() {
        val declared = StepBody.Declared(
            invocation = BodyInvocationPolicy.ONCE,
            execution = BodyExecution(
                owner = BodyExecutionOwner.LEGACY_LINEAR,
                policy = BodyExecutionPolicy.Sequential,
            ),
        )

        // The only way to construct a Declared is to supply both: the fields have no
        // defaults, so the compiler rejects every other spelling.
        assertEquals(BodyExecutionOwner.LEGACY_LINEAR, declared.execution.owner)
        assertEquals(BodyExecutionPolicy.Sequential, declared.execution.policy)
        assertNull(declared.introduces, "Introducing no context kind is a fact, not a sentinel")
        assertEquals(false, declared.catchesInterruptions)
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
        // `deprecation`, and the single `body` value that replaced six metadata fields in
        // W1d) all keep defaults; this regression guard exercises the pre-A4.1 prefix
        // through the post-A4.1 fields.
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
        // The body default is 'no body', with nothing to read from it.
        assertEquals(StepBody.None, descriptor.body)
        assertNull(descriptor.body.declared)
        // A4-1: pre-decode recovery policy defaults to None.
        assertEquals(dev.rubentxu.pipeline.v2.domain.durable.RecoveryPolicy.None, descriptor.recoveryPolicy)
    }
}
