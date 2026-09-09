package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreEchoStep
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.InMemoryStepRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * S2.5.7 / B1.2c3: freezes the pure family-routing decision of [FamilyRouter.decide].
 *
 * No I/O, no coroutines, no clock: every assertion inspects only the returned ADT. The cases
 * together are the only behavior [FamilyRouter] exposes; the test guards the contract that the
 * future `ExecutionBoundaryFactory.build(...)` (WU-3) will rely on.
 */
@Timeout(10)
class FamilyRouterTest {

    private fun registryOwningKey(): dev.rubentxu.pipeline.v2.domain.step.StepRegistry =
        InMemoryStepRegistry().also(CoreEchoStep::registerInto)

    /**
     * `CanonicalNodeDispatcher` is a concrete class; `FamilyRouter.decide` only uses it to
     * synthesize a fallback invocation executor when none is supplied. The fallback is never
     * reached in these tests (the tests pass `invocationExecutor = null` only to assert the
     * boundary selection, not the dispatch result), so an unconfigured instance is acceptable.
     */
    private val dispatcher: CanonicalNodeDispatcher = CanonicalNodeDispatcher()

    @Test
    fun `no registry returns LegacyOnly`() {
        val decision = FamilyRouter.decide(
            dispatcher = dispatcher,
            invocationExecutor = null,
            stepRegistry = null,
        )
        assertEquals(FamilyRoutingDecision.LegacyOnly, decision)
    }

    @Test
    fun `registry present without a step key returns LegacyOnly`() {
        val decision = FamilyRouter.decide(
            dispatcher = dispatcher,
            invocationExecutor = null,
            stepRegistry = registryOwningKey(),
            stepKey = null,
        )
        assertEquals(FamilyRoutingDecision.LegacyOnly, decision)
    }

    @Test
    fun `registry present and stepKey owned by registry returns SeamedRouting with two boundaries`() {
        val decision = FamilyRouter.decide(
            dispatcher = dispatcher,
            invocationExecutor = null,
            stepRegistry = registryOwningKey(),
            stepKey = CoreEchoStep.KEY,
        )
        // The seamed case is the structural decision: the factory must be able to apply
        // SeamedExecutionRouter.route(legacy, registry) without inspecting plugin steps.
        val seamed = decision as? FamilyRoutingDecision.SeamedRouting
        assertTrue(seamed != null, "expected SeamedRouting but was $decision")
        // Both boundaries must be present and distinct so the factory can wire them behind
        // the seamed router without re-deriving them. CommonExecutionBoundary is a fun
        // interface, so every CommonExecutionBoundary instance is a structural boundary;
        // what matters is that the two fields are non-null and refer to distinct instances
        // (the legacy one closes over the dispatcher, the registry one comes from
        // RegistryExecutionBoundary.adapt()).
        val s = seamed!!
        requireNotNull(s.legacy)
        requireNotNull(s.registry)
        // The legacy boundary (built by LegacyExecutionAdapter.adapt) and the registry
        // boundary (built by RegistryExecutionBoundary.adapt) must be distinct objects;
        // the factory MUST apply SeamedExecutionRouter.route(legacy, registry) to combine
        // them rather than calling either in isolation.
        assertTrue(
            s.legacy !== s.registry,
            "legacy and registry boundaries must be distinct objects so the seamed router can compose them",
        )
    }

    @Test
    fun `registry present and stepKey NOT owned by registry returns LegacyOnly`() {
        val decision = FamilyRouter.decide(
            dispatcher = dispatcher,
            invocationExecutor = null,
            stepRegistry = registryOwningKey(),
            stepKey = UNOWNED_KEY,
        )
        assertEquals(FamilyRoutingDecision.LegacyOnly, decision)
    }

    private companion object {
        // A key the in-memory registry does not own. FamilyRouter must not route seamed for it.
        private val UNOWNED_KEY: PluginStepId = PluginStepId("test.unowned")
    }
}
