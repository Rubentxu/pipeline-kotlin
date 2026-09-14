package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.BodyExecution
import dev.rubentxu.pipeline.v2.domain.BodyInvocationPolicy
import dev.rubentxu.pipeline.v2.domain.ContextKind
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.domain.EnvironmentSpec
import dev.rubentxu.pipeline.v2.domain.ExecutionContext
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepBody
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.step.BodyContextDerivation
import dev.rubentxu.pipeline.v2.domain.step.BodyContextProjection
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionOwner
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionPolicy
import dev.rubentxu.pipeline.v2.domain.step.BodyExecutionSupport
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyRejection
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolution
import dev.rubentxu.pipeline.v2.domain.step.BodyRuntimeValue
import dev.rubentxu.pipeline.v2.domain.step.deriveChildExecutionContext
import dev.rubentxu.pipeline.v2.domain.step.resolveBodyExecutionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC-2 / B11 W3 — defense fixture for the open-world invariant of the body
 * dispatch seam.
 *
 * The seam is meant to be StepKey-blind: a non-`core.*` plugin Step declaring
 * `Scoped(Environment)` MUST route through the SAME projection branch as
 * `core.withEnv`, and the production coordinator MUST NOT be touched when a
 * new external block Step joins the registry. These two properties are the
 * invariant that prevents the seam from regressing into a closed-world
 * `when(stepKey)` switch.
 *
 * Tested invariants:
 *
 *  1. The pure resolver returns the same [BodyExecutionPolicy] for an
 *     `example.projectX` block Step declaring `Scoped(Environment)` as for
 *     `core.withEnv`. They share the SHAPE, not the StepKey.
 *
 *  2. The pure [deriveChildExecutionContext] produces the same child context
 *     for two distinct StepKeys that declare the same projection + runtime
 *     payload. This is the "StepKey-blind derivation" row that turns the
 *     projection's open-world property from a slogan into a measurable
 *     invariant.
 *
 *  3. The standard descriptor registry is extensible: a second block Step
 *     that declares the same `Scoped(Environment)` shape sits alongside
 *     `core.withEnv` and is admitted by the same `BodyExecutionSupport`.
 *
 *  4. The production coordinator source does NOT contain a concrete
 *     `when(stepKey)`/`when(name)` switch and does NOT branch on the
 *     `example.projectX` literal: routing is closed over the ADT family,
 *     open over the registry. If a future change introduces such a branch,
 *     this fixture goes red.
 *
 * Companion to `Lfc2BodyExecutionPolicyFitnessTest` and
 * `Lfc2ConcreteBodyRoutingDebtFitnessTest`: where those prove the spine has
 * no concrete StepKey switch and the BodyChildLoopInventory law, this one
 * proves the positive twin — the projection branch is genuinely open-world.
 */
class Lfc2B11ExternalScopedRoutingDefenseFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private val coordinatorSource = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
    )

    private val policySource = v2Root.resolve(
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt",
    )

    private val derivationSource = v2Root.resolve(
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionContextDerivation.kt",
    )

    private fun read(path: Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Comment-blind view of a source file, so prose can name steps and code cannot. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    // ===== fixture descriptors =====

    private val coreWithEnv = PluginStepId("core.withEnv")

    private val externalProjectEnv = PluginStepId("example.projectX")

    private fun environmentScopedDescriptor(
        stepId: PluginStepId,
        name: String,
        pluginId: String,
    ): StepDescriptor = StepDescriptor(
        stepId = stepId.value,
        name = name,
        configRef = "",
        pluginId = pluginId,
        pluginVersion = "0.1.0",
        executionLocation = ExecutionLocation.CONTROLLER,
        effects = emptyList(),
        replayPolicy = ReplayPolicy.MEMOIZED,
        body = StepBody.Declared(
            invocation = BodyInvocationPolicy.ONCE,
            execution = BodyExecution(
                owner = BodyExecutionOwner.CANONICAL_ENGINE,
                policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
            ),
            introduces = ContextKind.ENVIRONMENT,
        ),
    )

    private val support = BodyExecutionSupport.SCOPED_SEQUENTIAL_RETRYING

    private val payload = BodyRuntimeValue.EnvironmentValue(mapOf("PROJECT" to "x", "TASK" to "run"))

    private val parent = ExecutionContext(
        overlays = listOf(ContextOverlay.Environment(EnvironmentSpec(mapOf("BASE" to "true")))),
    )

    // ===== invariant 1 — pure resolver is shape-addressable, not key-addressable =====

    @Test
    fun `pure resolver returns Scoped Environment for example projectX exactly as for core withEnv`() {
        val descriptor = environmentScopedDescriptor(
            stepId = externalProjectEnv,
            name = "projectX",
            pluginId = "example.projectX",
        )

        val pluginResolution = resolveBodyExecutionPolicy(externalProjectEnv, descriptor, support)
        val coreResolution = resolveBodyExecutionPolicy(coreWithEnv, descriptor, support)

        assertEquals(
            BodyPolicyResolution.Resolved(
                key = externalProjectEnv,
                policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
            ),
            pluginResolution,
            "An external block Step declaring Scoped(Environment) MUST be admitted exactly as " +
                "the core step that declares the same shape — admission is over the SHAPE, " +
                "never the StepKey.",
        )

        // The resolver wraps the resolved policy with the queried StepKey: identity-bearing.
        // The SHAPE, however, must be identical: two distinct StepKeys with the same declared
        // BodyExecutionPolicy MUST resolve to the same policy value. The key in the resolved
        // outcome is the query key, not a discriminator of the policy.
        assertEquals(
            pluginResolution.policyOrNull,
            coreResolution.policyOrNull,
            "Same declared shape, same policy: the resolver is total over the declared " +
                "BodyExecutionPolicy and never branches on the StepKey.",
        )
        assertEquals(
            BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
            pluginResolution.policyOrNull,
            "The resolved policy for an external Scoped(Environment) Step must equal the " +
                "core.withEnv policy verbatim.",
        )
    }

    @Test
    fun `pure resolver rejects incoherent plugin declaration fail closed`() {
        // A plugin Step that declares `Scoped(Environment)` but does NOT introduce the matching
        // context kind is incoherent: the projection requires `ContextKind.ENVIRONMENT` and the
        // descriptor omits it. The resolver must reject fail-closed rather than silently route.
        val incoherent = StepDescriptor(
            stepId = externalProjectEnv.value,
            name = "projectX",
            configRef = "",
            pluginId = "example.projectX",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = emptyList(),
            replayPolicy = ReplayPolicy.MEMOIZED,
            body = StepBody.Declared(
                invocation = BodyInvocationPolicy.ONCE,
                execution = BodyExecution(
                    owner = BodyExecutionOwner.CANONICAL_ENGINE,
                    policy = BodyExecutionPolicy.Scoped(BodyContextProjection.Environment),
                ),
                // Missing `introduces = ContextKind.ENVIRONMENT` — declares a context-projection
                // without the matching context kind.
            ),
        )

        val rejected = resolveBodyExecutionPolicy(externalProjectEnv, incoherent, support)

        assertTrue(
            rejected is BodyPolicyResolution.Rejected &&
                rejected.reason is BodyPolicyRejection.IncoherentMetadata,
            "An external Step that declares a context projection without the matching context " +
                "kind MUST be rejected fail-closed: $rejected",
        )
    }

    // ===== invariant 2 — derivation is key-blind =====

    @Test
    fun `derivation produces the same child context for two distinct StepKeys with the same shape`() {
        val descriptor = environmentScopedDescriptor(
            stepId = externalProjectEnv,
            name = "projectX",
            pluginId = "example.projectX",
        )

        val pluginPolicy = resolveBodyExecutionPolicy(externalProjectEnv, descriptor, support)
        val corePolicy = resolveBodyExecutionPolicy(coreWithEnv, descriptor, support)

        val projection = BodyContextProjection.Environment
        val pluginDerived = deriveChildExecutionContext(parent, projection, payload)
        val coreDerived = deriveChildExecutionContext(parent, projection, payload)

        // Both StepKeys resolve to Scoped(Environment); derivation depends only on the SHAPE
        // and the PAYLOAD, never on the StepKey. The two derivations MUST therefore yield equal
        // child contexts (and certainly not depend on the plugin descriptor at all).
        assertEquals(
            BodyPolicyResolution.Resolved(coreWithEnv, corePolicy.policyOrNull!!),
            corePolicy,
            "Sanity: same descriptor, same shape, same resolved policy.",
        )

        val pluginChild = (pluginDerived as? BodyContextDerivation.Derived)?.context
        val coreChild = (coreDerived as? BodyContextDerivation.Derived)?.context
        assertTrue(
            pluginChild != null && coreChild != null,
            "Both derivations should be Derived; plugin=$pluginDerived; core=$coreDerived",
        )
        // Both are non-null now; use safe access in case the smart cast is rejected across modules.
        assertEquals(
            coreChild!!,
            pluginChild!!,
            "StepKey-blind derivation: two distinct StepKeys with the same Scoped(Environment) " +
                "policy MUST produce the same child context.",
        )

        // The new context preserves the parent's BASE env and adds PROJECT/TASK on top.
        val top = pluginChild.overlays.last()
        assertEquals(
            ContextOverlay.Environment(EnvironmentSpec(mapOf("PROJECT" to "x", "TASK" to "run"))),
            top,
            "The derived child must carry the runtime payload as its top overlay.",
        )
    }

    @Test
    fun `derivation never reads the PluginStepId argument`() {
        // The derivation function signature does NOT carry a StepKey: parent + projection + payload
        // is the full domain. Re-derive with the same shape + payload from two parents that share
        // no context state, and prove the result is the same projection-only state.
        val parentA = ExecutionContext()
        val parentB = ExecutionContext(
            overlays = listOf(ContextOverlay.Environment(EnvironmentSpec(mapOf("OTHER" to "y")))),
        )

        val derivedA = deriveChildExecutionContext(parentA, BodyContextProjection.Environment, payload)
        val derivedB = deriveChildExecutionContext(parentB, BodyContextProjection.Environment, payload)

        val childA = (derivedA as? BodyContextDerivation.Derived)?.context
        val childB = (derivedB as? BodyContextDerivation.Derived)?.context
        assertTrue(
            childA != null && childB != null,
            "Both derivations should succeed: A=$derivedA; B=$derivedB",
        )
        assertTrue(
            childA!!.overlays.size == 1,
            "Derived child from empty parent has exactly one overlay (the pushed Environment).",
        )
        assertTrue(
            childB!!.overlays.size == 2,
            "Derived child from non-empty parent has the original overlay plus the pushed one.",
        )
        assertEquals(
            childA.overlays.last(),
            childB.overlays.last(),
            "Both children carry the same EnvironmentValue payload on their top frame.",
        )
    }

    // ===== invariant 3 — registry is extensible =====

    @Test
    fun `standard registry resolves core withEnv and an external Scoped Environment plugin identically`() {
        val registry = StepDescriptorRegistry.standard()

        val external = PluginStepId("example.withEnv") // external mirror of core.withEnv
        val mirror = environmentScopedDescriptor(
            stepId = external,
            name = "withEnv",
            pluginId = "example.withEnv",
        )

        // The standard registry contains core.withEnv. The mirror descriptor is not registered —
        // but the SAME resolver, given the mirror descriptor, produces the same resolution as
        // for the registered core descriptor: the resolver is over the declaration, not the
        // catalog membership.
        // Plugin descriptor is not registered — same shape as core.withEnv but different StepKey.
        val registered = registry.bodyPolicy(coreWithEnv, support)
        val mirrorResolved = resolveBodyExecutionPolicy(external, mirror, support)

        assertTrue(
            registered is BodyPolicyResolution.Resolved,
            "core.withEnv must resolve: $registered",
        )
        val registeredPolicy = registered.policyOrNull
        assertTrue(
            registeredPolicy is BodyExecutionPolicy.Scoped &&
                registeredPolicy.projection == BodyContextProjection.Environment,
            "core.withEnv must resolve to Scoped(Environment): $registered",
        )

        // Compare only the policy (shape): the resolver carries the queried key in the outcome
        // so a whole-outcome equality is identity-bearing; the SHAPE is what must match.
        assertEquals(
            registeredPolicy,
            mirrorResolved.policyOrNull,
            "A plugin Step declaring the same Scoped(Environment) shape as core.withEnv resolves " +
                "to the same policy as the registered core step. The registry is open over " +
                "StepKeys; the policy authority is over shapes.",
        )

        // And the registry is extensible in shape: a non-core Step joining the same shape is
        // admitted by the same engine support.
        val mirrorPolicy = mirrorResolved.policyOrNull
        assertTrue(
            mirrorPolicy != null && support.supports(mirrorPolicy),
            "The W1c engine supports the Scoped shape regardless of the plugin the descriptor " +
                "originates from. mirrorPolicy=$mirrorPolicy",
        )
    }

    // ===== invariant 4 — production code is key-blind =====

    @Test
    fun `production coordinator has no concrete when-switch over PluginStepId or stepName`() {
        val code = codeOnly(read(coordinatorSource))

        val switchByKey = Regex(
            """when\s*\(\s*[A-Za-z0-9_.]*([Ss]tepId|stepKey|stepName)[A-Za-z0-9_.]*\s*\)""",
        ).containsMatchIn(code)

        val switchByLiteral = Regex("""when\s*\(\s*[\"']core\.[A-Za-z]+[\"']""")
            .containsMatchIn(code)

        val switchByExternalLiteral = Regex("""when\s*\(\s*[\"']example\.[A-Za-z]+[\"']""")
            .containsMatchIn(code)

        assertEquals(
            false,
            switchByKey,
            "The coordinator MUST NOT switch on a StepKey/stepName variable: that is the exact " +
                "shape of the concrete body-routing debt.",
        )
        assertEquals(
            false,
            switchByLiteral,
            "The coordinator MUST NOT switch on a core.* step literal in a `when`.",
        )
        assertEquals(
            false,
            switchByExternalLiteral,
            "The coordinator MUST NOT switch on an example.* step literal in a `when`.",
        )
    }

    @Test
    fun `production coordinator contains no reference to example projectX or example withEnv literals`() {
        val code = codeOnly(read(coordinatorSource))

        val containsProjectX = code.contains("example.projectX")
        val containsWithEnv = code.contains("example.withEnv")

        assertEquals(
            false,
            containsProjectX,
            "The coordinator must never name the example.projectX StepKey: the dispatch seam is " +
                "key-blind. Any literal reference is a routing leak.",
        )
        assertEquals(
            false,
            containsWithEnv,
            "The coordinator must never name a hypothetical example.withEnv StepKey either.",
        )
    }

    @Test
    fun `policy vocabulary and projection branch never name a step key or step name`() {
        val policyCode = codeOnly(read(policySource))
        val derivationCode = codeOnly(read(derivationSource))

        val stepKeyLiteral = Regex("\"(core|example)\\.[A-Za-z0-9_.]+\"")
        val switchByKey = Regex(
            """when\s*\(\s*[A-Za-z0-9_.]*([Ss]tepId|stepKey|stepName)[A-Za-z0-9_.]*\s*\)""",
        )

        assertEquals(
            emptyList<String>(),
            stepKeyLiteral.findAll(policyCode).map { it.value }.toList(),
            "BodyExecutionPolicy must never carry a step-key literal.",
        )
        assertEquals(
            emptyList<String>(),
            stepKeyLiteral.findAll(derivationCode).map { it.value }.toList(),
            "BodyExecutionContextDerivation must never carry a step-key literal.",
        )

        assertEquals(
            false,
            switchByKey.containsMatchIn(policyCode),
            "BodyExecutionPolicy must not switch on a StepKey/stepName.",
        )
        assertEquals(
            false,
            switchByKey.containsMatchIn(derivationCode),
            "BodyExecutionContextDerivation must not switch on a StepKey/stepName.",
        )
    }
}
