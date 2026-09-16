package dev.rubentxu.pipeline.v2.domain

import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.RetryPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for StepDescriptorRegistry (EM-4; reworked by B10 / W1d).
 *
 * The registry is the source of truth for which core Steps take a body, WHO executes that
 * body, and WHAT shape it has. W1d made those three facts a single required value
 * ([StepBody.Declared]) instead of independent defaulted fields, so every row below now
 * states its ownership explicitly and cannot acquire semantics by omission.
 */
class StepDescriptorRegistryTest {

    private val registry = StepDescriptorRegistry.standard()

    private fun declared(key: String): StepBody.Declared {
        val descriptor = registry.get(PluginStepId(key))
        assertNotNull(descriptor, "$key must be registered")
        return requireNotNull(descriptor!!.body.declared) { "$key must declare a body" }
    }

    private fun assertTerminal(key: String) {
        val descriptor = registry.get(PluginStepId(key))
        assertNotNull(descriptor, "$key must be registered")
        assertEquals(StepBody.None, descriptor!!.body, "$key must be terminal")
        assertNull(descriptor.body.declared, "$key has nothing to read a body policy from")
    }

    @Test
    fun `core catchError declares a once body that catches interruptions`() {
        val body = declared("core.catchError")
        assertEquals(BodyInvocationPolicy.ONCE, body.invocation)
        assertEquals(true, body.catchesInterruptions)
        // Containment is a fold of the body's typed outcome, not an execution reshape.
        assertEquals(BodyExecutionOwner.LEGACY_LINEAR, body.execution.owner)
        assertEquals(BodyExecutionPolicy.Sequential, body.execution.policy)
    }

    @Test
    fun `core warnError declares an at-most-once body`() {
        val body = declared("core.warnError")
        assertEquals(BodyInvocationPolicy.AT_MOST_ONCE, body.invocation)
        assertEquals(BodyExecutionOwner.LEGACY_LINEAR, body.execution.owner)
        assertEquals(BodyExecutionPolicy.Sequential, body.execution.policy)
    }

    @Test
    fun `core withEnv declares an environment scope`() {
        val body = declared("core.withEnv")
        assertEquals(ContextKind.ENVIRONMENT, body.introduces)
        assertEquals(BodyExecutionOwner.CANONICAL_ENGINE, body.execution.owner)
        assertEquals(
            BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
            body.execution.policy,
        )
    }

    @Test
    fun `core dir declares a working-directory scope`() {
        val body = declared("core.dir")
        assertEquals(ContextKind.CWD, body.introduces)
        assertEquals(BodyExecutionOwner.CANONICAL_ENGINE, body.execution.owner)
        assertEquals(
            BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory),
            body.execution.policy,
        )
    }

    @Test
    fun `core withCredentials declares a credential-lease scope`() {
        val body = declared("core.withCredentials")
        assertEquals(ContextKind.CREDENTIALS, body.introduces)
        assertEquals(BodyExecutionOwner.CANONICAL_ENGINE, body.execution.owner)
        assertEquals(
            BodyExecutionPolicy.Scoped(BodyContextProjection.CredentialLease),
            body.execution.policy,
        )
    }

    @Test
    fun `core timeout declares a deadline scope`() {
        val body = declared("core.timeout")
        assertEquals(ContextKind.CANCELLATION, body.introduces)
        assertEquals(BodyExecutionOwner.CANONICAL_ENGINE, body.execution.owner)
        assertEquals(
            BodyExecutionPolicy.Scoped(BodyContextProjection.Deadline),
            body.execution.policy,
        )
    }

    @Test
    fun `core timestamps declares a timestamp scope and no context kind`() {
        val body = declared("core.timestamps")
        assertNull(body.introduces, "A projected scope need not introduce a ContextKind")
        assertEquals(BodyExecutionOwner.CANONICAL_ENGINE, body.execution.owner)
        assertEquals(
            BodyExecutionPolicy.Scoped(BodyContextProjection.Timestamps),
            body.execution.policy,
        )
    }

    @Test
    fun `core retry declares a zero-or-more body with a retrying shape`() {
        val body = declared("core.retry")
        assertEquals(BodyInvocationPolicy.ZERO_OR_MORE, body.invocation)
        assertEquals(BodyExecutionOwner.CANONICAL_ENGINE, body.execution.owner)
        assertEquals(BodyExecutionPolicy.Retrying(RetryPolicy()), body.execution.policy)
    }

    @Test
    fun `terminal core steps declare no body at all`() {
        listOf("core.emit.event", "core.sh", "core.echo", "core.sleep", "core.file.writeFile")
            .forEach { assertTerminal(it) }
    }

    @Test
    fun `unknown step returns null`() {
        assertNull(registry.get(PluginStepId("core.unknown")))
    }

    /**
     * The registry split is a fact worth pinning: exactly the eight body Steps and exactly
     * the five terminal ones, with ownership counted. A row silently changing side (or
     * forgetting to state an owner, which no longer compiles) would otherwise only show up
     * in the coordinator's behaviour.
     */
    @Test
    fun `the registry declares nine body rows and five terminal rows`() {
        val entries = registry.keys().map { it to registry.get(it)!! }
        val bodyRows = entries.filter { it.second.body.declared != null }
        val terminalRows = entries.filter { it.second.body == StepBody.None }

        assertEquals(14, entries.size, "The pinned core registry size")
        assertEquals(
            setOf(
                "core.catchError",
                "core.warnError",
                "core.withEnv",
                "core.dir",
                "core.withCredentials",
                "core.timeout",
                "core.timestamps",
                "core.retry",
                "core.waitUntil", // WU-G5R.3: canonical polling loop via executeWaitUntilBody
            ),
            bodyRows.map { it.first.value }.toSet(),
        )
        assertEquals(5, terminalRows.size)
        assertEquals(
            7,
            bodyRows.count { (it.second.body as StepBody.Declared).execution.owner == BodyExecutionOwner.CANONICAL_ENGINE },
            "Seven core rows are executed by the canonical body engine (core.dir, core.timestamps, core.withEnv, core.timeout, core.withCredentials, core.retry, core.waitUntil)",
        )
        assertEquals(
            2,
            bodyRows.count { (it.second.body as StepBody.Declared).execution.owner == BodyExecutionOwner.LEGACY_LINEAR },
            "core.catchError and core.warnError are folded by the legacy workflow-control rewrite",
        )
    }
}
