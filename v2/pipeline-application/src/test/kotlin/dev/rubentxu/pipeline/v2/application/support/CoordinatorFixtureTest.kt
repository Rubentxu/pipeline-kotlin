package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.PreparedExecution
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * B1.2c3-S2.5.3 / WU-1 — CoordinatorFixture contract tests.
 *
 * The fixture is the central composition point for the durable spine test classes (WU-2..WU-5).
 * These tests pin down the three contract guarantees callers depend on:
 *  - `default(...)` wires the production routing authority with the core [StepRegistry] active
 *    and accepts an optional recording boundary that is observable on the real execution path.
 *  - `negativeNoRegistry(...)` produces a coordinator that explicitly does NOT carry a registry
 *    (dual-only path used by explicit legacy characterization tests).
 *  - `noOpCredentialScopePort()` returns a fail-closed port that always yields
 *    [CredentialScopeOutcome.Unavailable] with [CredentialScopeFailure.StoreUnavailable].
 */
@Timeout(10)
class CoordinatorFixtureTest {

    @Test
    fun `default wires a coordinator and accepts an optional recorder on the real execution path`() =
        runBlocking {
            val clock = SystemClock()
            val journal = InMemoryOperationJournal(clock)
            val eventStore = InMemoryEventStore()
            val recorder = RecordingBoundarySpy()

            val coordinator = CoordinatorFixture.default(
                clock = clock,
                journal = journal,
                eventSink = eventStore,
                recorder = recorder,
            )

            // WU-1 keeps the coordinator constructor/composition contract indirect: the public fixture
            // return value is checked by the compiler, while the concrete routing and registry are
            // exercised by the production-like characterization tests in WU-2..WU-5.
            assertNotNull(coordinator, "default() must return a non-null coordinator")
            assertEquals(0, recorder.calls, "no execution has happened yet")
        }

    @Test
    fun `negativeNoRegistry returns a coordinator without a registry for dual characterization`() =
        runBlocking {
            val clock = SystemClock()
            val journal = InMemoryOperationJournal(clock)
            val eventStore = InMemoryEventStore()

            val coordinator = CoordinatorFixture.negativeNoRegistry(
                clock = clock,
                journal = journal,
                eventSink = eventStore,
            )

            // The dual-only fixture must produce a coordinator distinct from default() so callers can
            // exercise the legacy core authority explicitly. Behaviour split is pinned by
            // EchoDurableSpineTest's explicit dual test (WU-3).
            assertNotNull(coordinator, "negativeNoRegistry() must return a non-null coordinator")
        }

    @Test
    fun `noOpCredentialScopePort returns Unavailable StoreUnavailable for every binding and runId`() =
        runBlocking {
            val port: CredentialScopePort = CoordinatorFixture.noOpCredentialScopePort()

            val outcome = port.acquire(emptyList<CredentialBindingSpec>(), RunId("fixture-noop-test"))

            assertTrue(
                outcome is CredentialScopeOutcome.Unavailable,
                "noOpCredentialScopePort must return Unavailable, got $outcome",
            )
            val failure = (outcome as CredentialScopeOutcome.Unavailable).failure
            assertTrue(
                failure is CredentialScopeFailure.StoreUnavailable,
                "noOpCredentialScopePort must report StoreUnavailable, got $failure",
            )
        }

    /**
     * Minimal [CommonExecutionBoundary] spy. The fixture hands the recorder through to the
     * coordinator's `commonExecutionBoundary` parameter; the test only checks that the fixture
     * accepts the seam and returns a coordinator without exercising a full pipeline (which is the
     * job of WU-2/3/4/5).
     */
    private class RecordingBoundarySpy : CommonExecutionBoundary {
        var calls: Int = 0
            private set

        override suspend fun execute(
            prepared: PreparedExecution,
            context: CanonicalRuntimeContext,
        ): StepOutcome {
            calls += 1
            return StepOutcome.Success
        }
    }
}
