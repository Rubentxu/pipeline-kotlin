package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * C1-B smoke test (2026-09-26): exercises the [CoordinatorCaps] data class on its own
 * to confirm shape, defaults and equality semantics. The data class is a pure
 * structural adapter: no behaviour, no I/O, no clock side-effects. This test isolates
 * the bundle so a future change that touches default values or adds a field surfaces
 * as a single explicit diff.
 *
 * The integration test (constructor accepts CoordinatorCaps and routes through the
 * same body as the 21-arg ctor) lives in the legacy [CanonicalDurableRunCoordinatorTest]
 * suite; this file is the unit boundary.
 */
@Timeout(10)
class CoordinatorCapsTest {

    private val clock: Clock = SystemClock()
    private val dispatcher: CanonicalNodeDispatcher = CanonicalNodeDispatcher()
    private val journal = InMemoryOperationJournal(clock)
    private val cursorStore = InMemoryReplayCursorStore(clock)
    private val eventSink: EventSink = InMemoryEventStore()
    private val credentialScopePort: CredentialScopePort = CredentialScopePort { _, _ ->
        // no-op test double; the C1-B smoke test never invokes the port
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("C1-B smoke test does not acquire credentials"),
        )
    }

    private fun minimalCaps() = CoordinatorCaps(
        dispatcher = dispatcher,
        journal = journal,
        cursorStore = cursorStore,
        clock = clock,
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        eventSink = eventSink,
        credentialScopePort = credentialScopePort,
    )

    @Test
    fun `minimal bundle constructs with all-default optional fields`() {
        val caps = minimalCaps()
        assertEquals(null, caps.controlDirRoot)
        assertEquals(null, caps.workspaceBase)
        assertEquals(null, caps.secretPatternRegistry)
        assertEquals(null, caps.stepMetadataResolver)
        assertEquals(null, caps.invocationExecutor)
        assertEquals(null, caps.commonExecutionBoundary)
        assertEquals(null, caps.stepRegistry)
        assertEquals(null, caps.retryControlJournal)
        assertEquals(null, caps.waitUntilControlJournal)
        assertEquals(null, caps.artifactIndex)
        assertEquals(null, caps.injectedBodyPolicyResolver)
    }

    @Test
    fun `defaults mirror the legacy 21-arg constructor defaults`() {
        // The legacy ctor hard-codes these factories. We mirror them exactly so the
        // bundle is interchangeable. This test freezes that contract; if any default
        // diverges the test fails loudly.
        val caps = minimalCaps()
        // divergenceDetector: StrictFingerprintDivergenceDetector() — non-null instance
        assertEquals(
            "StrictFingerprintDivergenceDetector",
            caps.divergenceDetector::class.simpleName,
            "divergenceDetector default must remain StrictFingerprintDivergenceDetector",
        )
        // shOptions: ShOptions.EMPTY
        assertEquals(dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions.EMPTY, caps.shOptions)
        // milestoneStateStore: fresh MilestoneStateStore()
        assertEquals(MilestoneStateStore::class, caps.milestoneStateStore::class)
        // bodyInvokerAdapter: CanonicalBodyInvokerAdapter()
        assertEquals(CanonicalBodyInvokerAdapter::class, caps.bodyInvokerAdapter::class)
    }

    @Test
    fun `coordinator accepts the consolidated capability bundle`() {
        val coordinator = CanonicalDurableRunCoordinator(minimalCaps())
        assertNotNull(coordinator)
    }

    @Test
    fun `data class equality is structural`() {
        val a = minimalCaps()
        val b = a.copy()
        val c = a.copy(workspaceBase = java.nio.file.Path.of("/tmp/ws"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
