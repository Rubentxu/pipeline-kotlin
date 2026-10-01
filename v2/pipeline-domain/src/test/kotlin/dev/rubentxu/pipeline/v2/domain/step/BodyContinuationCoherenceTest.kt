package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-RP-035 / slice B: coherence between the declared body owner and the declared
 * capabilities (ADR-0081 as amended 2026-10-01).
 *
 * A `HANDLER_CONTINUATION` Step and the `BODY_CONTINUATION_CAPABILITY` are two halves of
 * one declaration. Either alone is a defect, and the defect is a Step-side mistake — the
 * engine must not paper over it by running the body itself, because that is the semantic
 * substitution the open-world design exists to prevent.
 */
class BodyContinuationCoherenceTest {

    private val stringCodec = object : StepCodec<String> {
        private val json = Json
        override fun encode(value: String): EncodedStepValue =
            EncodedStepValue(json.encodeToString(String.serializer(), value))

        override fun decode(encoded: EncodedStepValue): String =
            json.decodeFromString(String.serializer(), encoded.value)
    }

    private fun definition(
        key: String,
        owner: BodyExecutionOwner,
        capabilities: Set<StepCapability>,
    ) = object : StepDefinition<String, String> {
        override val contract = StepContract(
            key = PluginStepId(key),
            descriptor = StepDescriptor(
                stepId = key,
                name = key.substringAfter('.'),
                configRef = "",
                executionLocation = ExecutionLocation.CONTROLLER,
                effects = listOf(Effect.READ_ONLY),
                replayPolicy = ReplayPolicy.MEMOIZED,
                body = StepBody.Declared(
                    invocation = BodyInvocationPolicy.ONCE,
                    execution = BodyExecution(owner = owner, policy = BodyExecutionPolicy.Sequential),
                    introduces = null,
                ),
            ),
            inputCodec = stringCodec,
            outputCodec = stringCodec,
            requiredCapabilities = capabilities,
        )

        override val handler = StepHandler<String, String> { input, _ -> input }
    }

    private fun resolve(
        owner: BodyExecutionOwner,
        capabilities: Set<StepCapability>,
    ): BodyPolicyResolution = resolveBodyExecutionPolicy(
        definition = definition("test.coherence", owner, capabilities),
        support = BodyExecutionSupport.SEQUENTIAL_ONLY,
    )

    @Test
    fun `handler continuation owner with the continuation capability resolves`() {
        val resolution = resolve(BodyExecutionOwner.HANDLER_CONTINUATION, setOf(BODY_CONTINUATION_CAPABILITY))

        assertEquals(
            BodyPolicyResolution.Resolved(
                PluginStepId("test.coherence"),
                BodyExecutionPolicy.Sequential,
            ),
            resolution,
        )
    }

    @Test
    fun `handler continuation owner without the capability is rejected fail-closed`() {
        val resolution = resolve(BodyExecutionOwner.HANDLER_CONTINUATION, emptySet())

        val reason = (resolution as BodyPolicyResolution.Rejected).reason
        assertTrue(
            reason is BodyPolicyRejection.IncoherentCapabilities,
            "expected IncoherentCapabilities, got $reason",
        )
        val rejection = reason as BodyPolicyRejection.IncoherentCapabilities
        assertEquals(BodyExecutionOwner.HANDLER_CONTINUATION, rejection.owner)
        assertTrue(
            BODY_CONTINUATION_CAPABILITY.key in rejection.detail,
            "the diagnostic must name the missing capability, got '${rejection.detail}'",
        )
    }

    @Test
    fun `the continuation capability under a non-continuation owner is rejected`() {
        val resolution = resolve(BodyExecutionOwner.CANONICAL_ENGINE, setOf(BODY_CONTINUATION_CAPABILITY))

        val reason = (resolution as BodyPolicyResolution.Rejected).reason
        assertTrue(
            reason is BodyPolicyRejection.IncoherentCapabilities,
            "expected IncoherentCapabilities, got $reason",
        )
        val rejection = reason as BodyPolicyRejection.IncoherentCapabilities
        assertEquals(BodyExecutionOwner.CANONICAL_ENGINE, rejection.owner)
    }

    @Test
    fun `existing core shapes are unaffected`() {
        listOf(
            BodyExecutionOwner.CANONICAL_ENGINE,
            BodyExecutionOwner.LEGACY_LINEAR,
        ).forEach { owner ->
            val resolution = resolve(owner, emptySet())
            assertTrue(
                resolution is BodyPolicyResolution.Resolved,
                "owner $owner with no declared capabilities must keep resolving, got $resolution",
            )
        }
    }

    @Test
    fun `the continuation is a functional interface a plugin can implement with a lambda`() = runBlocking {
        val continuation = BodyContinuation { BodyOutcome.Cancelled(CancellationReason.ParentCancelled) }

        assertEquals(
            BodyOutcome.Cancelled(CancellationReason.ParentCancelled),
            continuation.invoke(BodyInvocationContext()),
        )
    }

    @Test
    fun `the capability key is distinct from the engine-side invoker key`() {
        assertTrue(
            BODY_CONTINUATION_CAPABILITY.key != BODY_INVOKER_CAPABILITY.key,
            "the bound continuation and the raw engine invoker must not share a capability key",
        )
    }
}
