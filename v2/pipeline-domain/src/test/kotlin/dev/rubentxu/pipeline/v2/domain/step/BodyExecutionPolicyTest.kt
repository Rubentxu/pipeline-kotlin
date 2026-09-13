package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ContextKind
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * B10 / W1b — the typed body execution policy.
 *
 * Two obligations are proved here, and they are independent:
 *
 *  1. **Representability (criterion 7).** Every body-execution shape the coordinator
 *     implements TODAY, by name, is expressible as a value of the closed
 *     [BodyExecutionPolicy] ADT — with no case left unused and no shape missing. The
 *     expectation table below is stated against the coordinator's live routing
 *     semantics, not against the ADT's own definitions, so it can fail.
 *  2. **Fail-closed resolution (criterion 4).** Unknown, incoherent and unsupported
 *     declarations are rejected with distinct typed reasons, never defaulted.
 *
 * What this file deliberately does NOT claim: that the coordinator already routes
 * bodies through the policy. W1b introduces the mechanism; W1c migrates consumers.
 */
class BodyExecutionPolicyTest {

    /**
     * The body-bearing Step families the engine handles today, and the policy each one's
     * CURRENT behaviour corresponds to.
     *
     * Authority for each row is the existing routing code, not this model: since W1c the
     * coordinator projects the DECLARED policy (`projectBodyExecution` / `projectScopedBody`,
     * replacing the pre-W1c keyed scope projection) for dir / timestamps / withEnv / timeout /
     * retry, routes the credential lease to `dispatchWithCredentialsBlock` by projection, the
     * PAR-D stage branch aggregate owns parallel, and `StructuralOverlayProjection` containment
     * owns catchError / warnError.
     */
    private val currentEngineBehaviour: List<Pair<String, BodyExecutionPolicy>> = listOf(
        "core.dir" to BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory),
        "core.timestamps" to BodyExecutionPolicy.Scoped(BodyContextProjection.Timestamps),
        "core.withEnv" to BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
        "core.timeout" to BodyExecutionPolicy.Scoped(BodyContextProjection.Deadline),
        "core.withCredentials" to BodyExecutionPolicy.Scoped(BodyContextProjection.CredentialLease),
        "core.retry" to BodyExecutionPolicy.Retrying(RetryPolicy()),
        "core.parallel" to BodyExecutionPolicy.Parallel(ParallelPolicy()),
        "core.catchError" to BodyExecutionPolicy.Sequential,
        "core.warnError" to BodyExecutionPolicy.Sequential,
    )

    /**
     * Families whose descriptor row does not exist yet, so their policy is expressible
     * by the model but not yet declared by any descriptor. Declared as a GAP on purpose:
     * the assertion is two-directional, so adding or losing a row forces this ledger to
     * be updated rather than letting the gap drift silently.
     *
     * `core.timestamps` left this gap in W1c. `core.parallel` stays: it is a PAR-D stage
     * branch aggregate, not a body the body engine re-enters, and declaring a policy for it
     * before the fan-out exists would codify the current sequential execution as correct.
     */
    private val familiesWithoutDescriptorRow: Set<String> = setOf("core.parallel")

    private fun descriptorOf(key: String): StepDescriptor? =
        StepDescriptorRegistry.standard().get(PluginStepId(key))

    @Nested
    inner class Representability {

        /**
         * The ADT is neither too weak (a current shape has no case) nor padded (a case
         * has no current shape). Both directions fail here.
         */
        @Test
        fun `the closed policy family exactly covers the shapes the engine implements today`() {
            val shapesUsed = currentEngineBehaviour.map { it.second.shape }.toSet()

            assertEquals(
                BodyExecutionPolicyShape.entries.toSet(),
                shapesUsed,
                "Every policy shape must correspond to a real engine behaviour, and every real " +
                    "behaviour must have a shape: adding or retiring a shape updates this table",
            )
        }

        /** Each of the five projections is a distinct value: a scope names one dimension. */
        @Test
        fun `the five projected scopes are distinct values`() {
            val projections = currentEngineBehaviour
                .mapNotNull { (it.second as? BodyExecutionPolicy.Scoped)?.projection }

            assertEquals(5, projections.size, "Five families project a scope")
            assertEquals(
                projections.size,
                projections.distinct().size,
                "Two families must not claim the same projection: $projections",
            )
        }

        /** Retry identity and parallel identity are distinct declared values. */
        @Test
        fun `retrying and parallel carry distinct structural policies`() {
            val retrying = currentEngineBehaviour.mapNotNull { it.second as? BodyExecutionPolicy.Retrying }
            val parallel = currentEngineBehaviour.mapNotNull { it.second as? BodyExecutionPolicy.Parallel }

            assertEquals(1, retrying.size)
            assertEquals(1, parallel.size)
            assertEquals(PluginStepId("retry-attempt"), retrying.single().policy.attemptKey)
            assertEquals(PluginStepId("parallel-branch"), parallel.single().policy.branchKey)
        }

        /**
         * The declarations in the descriptor authority agree with the coordinator's live
         * routing semantics. This is the cross-check that makes the model non-vacuous:
         * a wrong declaration fails here rather than at runtime.
         */
        @Test
        fun `declared descriptor policies agree with the coordinator's current routing`() {
            currentEngineBehaviour.forEach { (key, expected) ->
                val descriptor = descriptorOf(key) ?: return@forEach
                assertEquals(
                    expected,
                    descriptor.bodyExecutionPolicy,
                    "StepDescriptorRegistry declaration for '$key' disagrees with its live routing",
                )
            }
        }

        /** Every declared policy resolves coherently under a fully capable engine. */
        @Test
        fun `every declared policy resolves under full engine support`() {
            currentEngineBehaviour.forEach { (key, _) ->
                val descriptor = descriptorOf(key) ?: return@forEach
                val resolution = resolveBodyExecutionPolicy(descriptor, BodyExecutionSupport.FULL)
                assertInstanceOf(
                    BodyPolicyResolution.Resolved::class.java,
                    resolution,
                    "Descriptor declaration for '$key' must be coherent: $resolution",
                )
            }
        }

        /** The gap is declared, not forgotten: it fails in both directions. */
        @Test
        fun `families without a descriptor row are exactly the declared gap`() {
            val undeclared = currentEngineBehaviour
                .map { it.first }
                .filter { descriptorOf(it) == null }
                .toSet()

            assertEquals(
                familiesWithoutDescriptorRow,
                undeclared,
                "A family gained or lost its descriptor row: update this gap ledger and the W1b receipt",
            )
        }

        /**
         * Containment and output decoration are folds of a typed outcome, not execution
         * reshapes. Both share a context kind with a *reshaping* Step, which is exactly
         * why the shape cannot be derived from `introducesContext`.
         */
        @Test
        fun `containment families are sequential and share a context kind with a reshaping family`() {
            assertEquals(BodyExecutionPolicy.Sequential, descriptorOf("core.catchError")?.bodyExecutionPolicy)
            assertEquals(BodyExecutionPolicy.Sequential, descriptorOf("core.warnError")?.bodyExecutionPolicy)
            assertEquals(
                ContextKind.CANCELLATION,
                descriptorOf("core.catchError")?.introducesContext,
                "catchError declares CANCELLATION",
            )
            assertEquals(
                ContextKind.CANCELLATION,
                descriptorOf("core.timeout")?.introducesContext,
                "timeout declares the same CANCELLATION kind, yet is Scoped(Deadline)",
            )
            assertEquals(
                BodyExecutionPolicyShape.SCOPED,
                descriptorOf("core.timeout")?.bodyExecutionPolicy?.shape,
                "Same context kind, different shape: introducesContext cannot determine the policy",
            )
        }
    }

    @Nested
    inner class FailClosedResolution {

        @Test
        fun `an unknown descriptor is rejected, never defaulted`() {
            val resolution = resolveBodyExecutionPolicy(
                PluginStepId("core.doesNotExist"),
                descriptor = null,
                support = BodyExecutionSupport.FULL,
            )

            assertInstanceOf(BodyPolicyRejection.UnknownStep::class.java, resolution.rejectionOrNull)
            assertNull(resolution.policyOrNull, "A rejection must never carry a policy")
        }

        @Test
        fun `an unregistered key is rejected through the registry port`() {
            val resolver = RegistryBodyPolicyResolver(InMemoryStepRegistry(), BodyExecutionSupport.FULL)

            val resolution = resolver.resolve(PluginStepId("plugin.notInstalled"))

            assertInstanceOf(BodyPolicyRejection.UnknownStep::class.java, resolution.rejectionOrNull)
        }

        @Test
        fun `a declared shape the engine cannot execute is rejected as unsupported`() {
            val descriptor = requireNotNull(descriptorOf("core.dir"))
            val resolution = resolveBodyExecutionPolicy(
                PluginStepId("core.dir"),
                descriptor,
                BodyExecutionSupport.SEQUENTIAL_ONLY,
            )

            val rejection = assertInstanceOf(
                BodyPolicyRejection.UnsupportedByEngine::class.java,
                resolution.rejectionOrNull,
            )
            assertEquals(BodyExecutionPolicyShape.SCOPED, rejection.declared.shape)
            assertTrue(
                BodyExecutionPolicyShape.SCOPED !in rejection.support.shapes,
                "The rejection must report the support that caused it",
            )
        }

        @Test
        fun `a sequential-only engine admits sequential bodies`() {
            val descriptor = requireNotNull(descriptorOf("core.catchError"))
            val resolution = resolveBodyExecutionPolicy(
                PluginStepId("core.catchError"),
                descriptor,
                BodyExecutionSupport.SEQUENTIAL_ONLY,
            )

            assertEquals(BodyExecutionPolicy.Sequential, resolution.policyOrNull)
        }

        @Test
        fun `a non-body step declaring an execution reshape is rejected`() {
            val descriptor = StepDescriptor(
                stepId = "core.sh",
                name = "sh",
                configRef = "",
                takesBody = false,
                bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
            )

            val rejection = assertInstanceOf(
                BodyPolicyRejection.NotABodyStep::class.java,
                resolveBodyExecutionPolicy(descriptor, BodyExecutionSupport.FULL).rejectionOrNull,
            )
            assertEquals(PluginStepId("core.sh"), rejection.key)
            assertEquals(
                BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
                rejection.declared,
            )
            assertTrue(
                !descriptor.takesBody,
                "The rejection reports a descriptor that takes no body",
            )
        }

        @Test
        fun `retrying without repeat cardinality is incoherent`() {
            val descriptor = StepDescriptor(
                stepId = "core.retry",
                name = "retry",
                configRef = "",
                takesBody = true,
                bodyInvocations = BodyInvocationPolicy.ONCE,
                bodyExecutionPolicy = BodyExecutionPolicy.Retrying(RetryPolicy()),
            )

            val rejection = assertInstanceOf(
                BodyPolicyRejection.IncoherentMetadata::class.java,
                resolveBodyExecutionPolicy(descriptor, BodyExecutionSupport.FULL).rejectionOrNull,
            )
            assertTrue(
                rejection.detail.contains("ZERO_OR_MORE"),
                "The diagnostic must name the contradicting metadata: ${rejection.detail}",
            )
        }

        @Test
        fun `repeat cardinality without a repeating shape is incoherent`() {
            val descriptor = StepDescriptor(
                stepId = "core.retry",
                name = "retry",
                configRef = "",
                takesBody = true,
                bodyInvocations = BodyInvocationPolicy.ZERO_OR_MORE,
                bodyExecutionPolicy = BodyExecutionPolicy.Sequential,
            )

            val rejection = assertInstanceOf(
                BodyPolicyRejection.IncoherentMetadata::class.java,
                resolveBodyExecutionPolicy(descriptor, BodyExecutionSupport.FULL).rejectionOrNull,
            )
            assertTrue(rejection.detail.contains("cannot repeat the body"), rejection.detail)
        }

        @Test
        fun `a projected scope must match the declared context kind`() {
            val descriptor = StepDescriptor(
                stepId = "core.withEnv",
                name = "withEnv",
                configRef = "",
                takesBody = true,
                introducesContext = ContextKind.CWD,
                bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
            )

            val rejection = assertInstanceOf(
                BodyPolicyRejection.IncoherentMetadata::class.java,
                resolveBodyExecutionPolicy(descriptor, BodyExecutionSupport.FULL).rejectionOrNull,
            )
            assertTrue(rejection.detail.contains("ENVIRONMENT"), rejection.detail)
        }

        /**
         * A timestamp projection has no corresponding declared [ContextKind], so it is the
         * one scope exempt from the context-kind coherence check. That exemption is a fact
         * about the closed ContextKind family, asserted so it cannot be assumed silently.
         */
        @Test
        fun `the timestamp projection declares no required context kind`() {
            assertNull((BodyContextProjection.Timestamps as BodyContextProjection).requiredContextKind)
            assertEquals(
                ContextKind.CWD,
                (BodyContextProjection.WorkingDirectory as BodyContextProjection).requiredContextKind,
            )
            assertEquals(
                ContextKind.CANCELLATION,
                (BodyContextProjection.Deadline as BodyContextProjection).requiredContextKind,
            )
        }
    }

    @Nested
    inner class RegistryAuthority {

        /**
         * The policy authority is the open registry's contract descriptor, so an external
         * plugin Step and a core Step resolve identically. The StepKey is only a lookup
         * key, never a branch: the same descriptor under a different key resolves the same.
         */
        @Test
        fun `resolution reads the registered contract and not the step key`() {
            val registry = InMemoryStepRegistry()
            val descriptor = StepDescriptor(
                stepId = "example.reshape",
                name = "reshape",
                configRef = "",
                takesBody = true,
                introducesContext = ContextKind.ENVIRONMENT,
                bodyExecutionPolicy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
            )
            registry.register(UnitDefinition(PluginStepId("example.reshape"), descriptor))

            val resolver = RegistryBodyPolicyResolver(registry, BodyExecutionSupport.FULL)

            assertEquals(
                BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
                resolver.resolve(PluginStepId("example.reshape")).policyOrNull,
            )
            assertInstanceOf(
                BodyPolicyRejection.UnknownStep::class.java,
                resolver.resolve(PluginStepId("example.unregistered")).rejectionOrNull,
            )
        }

        /** The port resolves from the contract descriptor exactly as the pure function does. */
        @Test
        fun `the registry port agrees with the pure function over the same descriptor`() {
            val registry = InMemoryStepRegistry()
            val descriptor = StepDescriptor(
                stepId = "core.retry",
                name = "retry",
                configRef = "",
                takesBody = true,
                bodyInvocations = BodyInvocationPolicy.ZERO_OR_MORE,
                bodyExecutionPolicy = BodyExecutionPolicy.Retrying(RetryPolicy()),
            )
            val definition = UnitDefinition(PluginStepId("core.retry"), descriptor)
            registry.register(definition)

            val viaPort = RegistryBodyPolicyResolver(registry, BodyExecutionSupport.FULL)
                .resolve(PluginStepId("core.retry"))
            val viaFunction = resolveBodyExecutionPolicy(definition, BodyExecutionSupport.FULL)

            assertEquals(viaFunction, viaPort)
        }
    }

    @Nested
    inner class SupportAdmission {

        @Test
        fun `admission is over shapes and never over step keys`() {
            assertTrue(BodyExecutionSupport.FULL.supports(BodyExecutionPolicy.Sequential))
            assertTrue(BodyExecutionSupport.FULL.supports(BodyExecutionPolicy.Scoped(BodyContextProjection.Deadline)))
            assertTrue(BodyExecutionSupport.SEQUENTIAL_ONLY.supports(BodyExecutionPolicy.Sequential))
            assertTrue(
                !BodyExecutionSupport.SEQUENTIAL_ONLY.supports(BodyExecutionPolicy.Retrying(RetryPolicy())),
            )
            assertTrue(
                !BodyExecutionSupport.NONE.supports(BodyExecutionPolicy.Sequential),
                "NONE admits nothing, not even the sequential shape",
            )
        }

        @Test
        fun `full support covers every shape of the closed family`() {
            assertEquals(
                BodyExecutionPolicyShape.entries.toSet(),
                BodyExecutionSupport.FULL.shapes,
                "FULL must admit every expressible shape; a new shape widens this set explicitly",
            )
        }

        /**
         * W1c: the engine support is the shape set the coordinator actually interprets.
         * PARALLEL is absent because branch fan-out is not implemented, so a Step declaring
         * it is rejected instead of being silently executed as a plain sequence.
         */
        @Test
        fun `the W1c engine support admits every shape it interprets and not the silent one`() {
            val support = BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING

            assertTrue(support.supports(BodyExecutionPolicy.Sequential))
            assertTrue(support.supports(BodyExecutionPolicy.Scoped(BodyContextProjection.WorkingDirectory)))
            assertTrue(support.supports(BodyExecutionPolicy.Retrying(RetryPolicy())))
            assertTrue(
                !support.supports(BodyExecutionPolicy.Parallel(ParallelPolicy())),
                "PARALLEL is not implemented: admitting it would run branches in sequence",
            )
        }
    }

    /**
     * B10 / W1c — body execution OWNERSHIP.
     *
     * The canonical runner derives the body families it may execute from
     * [StepDescriptor.bodyExecutionOwner]. These laws pin that derivation in both
     * directions, because it is production routing: adding a row silently widens what the
     * durable engine executes, and losing one silently narrows it.
     */
    @Nested
    inner class Ownership {

        private val registry = StepDescriptorRegistry.standard()

        @Test
        fun `the canonical body set is the six families whose bodies this engine executes`() {
            assertEquals(
                setOf(
                    PluginStepId("core.dir"),
                    PluginStepId("core.timestamps"),
                    PluginStepId("core.withEnv"),
                    PluginStepId("core.timeout"),
                    PluginStepId("core.withCredentials"),
                    PluginStepId("core.retry"),
                ),
                registry.bodyStepIds(BodyExecutionOwner.CANONICAL_ENGINE),
                "Canonical body eligibility is registry-derived: a change here is a routing change",
            )
        }

        @Test
        fun `containment families declare legacy ownership and are not eligible`() {
            assertEquals(
                setOf(PluginStepId("core.catchError"), PluginStepId("core.warnError")),
                registry.bodyStepIds(BodyExecutionOwner.LEGACY_LINEAR),
                "catchError / warnError bodies belong to the legacy workflow-control rewrite",
            )
            assertEquals(
                BodyExecutionOwner.LEGACY_LINEAR,
                registry.get(PluginStepId("core.catchError"))?.bodyExecutionOwner,
                "catchError declares no canonical ownership even though its shape is Sequential",
            )
        }

        /**
         * Ownership is not derivable from the shape: both owners declare `Sequential`
         * somewhere. This is why the field exists.
         */
        @Test
        fun `ownership is independent of the declared shape`() {
            assertEquals(
                BodyExecutionPolicy.Sequential,
                registry.get(PluginStepId("core.catchError"))?.bodyExecutionPolicy,
            )
            assertEquals(
                BodyExecutionPolicy.Retrying(RetryPolicy()),
                registry.get(PluginStepId("core.retry"))?.bodyExecutionPolicy,
            )
            assertEquals(
                BodyExecutionOwner.CANONICAL_ENGINE,
                registry.get(PluginStepId("core.retry"))?.bodyExecutionOwner,
            )
            assertEquals(
                BodyExecutionOwner.CANONICAL_ENGINE,
                StepDescriptor(stepId = "example.bare", name = "bare", configRef = "").bodyExecutionOwner,
                "The default owner is the target state: a new body Step is canonical unless it says otherwise",
            )
        }

        @Test
        fun `a terminal Step owns no body regardless of its owner declaration`() {
            val terminal = StepDescriptor(
                stepId = "example.terminal",
                name = "terminal",
                configRef = "",
                bodyExecutionOwner = BodyExecutionOwner.CANONICAL_ENGINE,
            )

            assertTrue(!terminal.takesBody, "The fixture must be terminal for this law to mean anything")
            assertTrue(
                registry.bodyStepIds(BodyExecutionOwner.CANONICAL_ENGINE)
                    .none { key -> registry.get(key)?.takesBody != true },
                "A terminal Step must never appear in the body set",
            )
        }

        /**
         * The descriptor-table resolver is the authority for the core block families, which
         * have no registered handler. Its laws are the same as the registry port's: fail closed.
         */
        @Test
        fun `the descriptor resolver rejects unknown families and resolves declared ones`() {
            val resolver = registry.bodyPolicyResolver(BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING)

            assertInstanceOf(
                BodyPolicyResolution.Resolved::class.java,
                resolver.resolve(PluginStepId("core.retry")),
            )
            assertInstanceOf(
                BodyPolicyResolution.Rejected::class.java,
                resolver.resolve(PluginStepId("example.unregistered.body")),
                "An unknown family must never resolve to a default shape",
            )
        }

        @Test
        fun `an engine without the retrying shape rejects a retrying declaration`() {
            val result = registry
                .bodyPolicyResolver(BodyExecutionSupport.SEQUENTIAL_ONLY)
                .resolve(PluginStepId("core.retry"))

            assertInstanceOf(BodyPolicyResolution.Rejected::class.java, result)
            assertInstanceOf(
                BodyPolicyRejection.UnsupportedByEngine::class.java,
                (result as BodyPolicyResolution.Rejected).reason,
                "A retrying declaration is rejected by an engine that only runs sequential bodies",
            )
        }
    }

    /** Minimal definition used to drive the registry port without a real handler. */
    private class UnitDefinition(
        key: PluginStepId,
        descriptor: StepDescriptor,
    ) : StepDefinition<Unit, Unit> {
        override val contract: StepContract<Unit, Unit> =
            StepContract(key, descriptor, UnitCodec, UnitCodec)

        override val handler: StepHandler<Unit, Unit> = StepHandler { _, _ -> Unit }
    }

    @Suppress("UNUSED_PARAMETER")
    private object UnitCodec : StepCodec<Unit> {
        override fun encode(value: Unit): EncodedStepValue = EncodedStepValue("{}")

        override fun decode(encoded: EncodedStepValue): Unit = Unit
    }
}
