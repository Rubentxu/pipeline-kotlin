package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceException
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/**
 * The divergence detector is a PORT, and the composition keeps it that way.
 *
 * ## The guarantee
 *
 * A caller that hands [CanonicalDurableRunCoordinator] a [DivergenceDetector] gets THAT detector
 * composed into the run. The coordinator's constructor declares the interface, and so does its
 * collaborator ([DurableInvocationResolver]), so the only thing standing between the two is a
 * downcast in the coordinator's own body:
 *
 * ```kotlin
 * divergenceDetector = divergenceDetector as StrictFingerprintDivergenceDetector
 * ```
 *
 * That cast is the defect. It narrows a declared port to ONE implementation, so every detector
 * that is not [StrictFingerprintDivergenceDetector] dies with a [ClassCastException] at
 * CONSTRUCTION — before the pipeline has said a single word. The caller cannot choose a different
 * divergence policy, and cannot even find out that the choice was refused: the run never starts.
 *
 * ## The capacity this serves
 *
 * The durable engine can be composed with a divergence policy other than the strict fingerprint
 * one — a policy that compares something else, or one that a test injects. The parameter says it
 * can. The cast says it cannot, and says it by throwing.
 *
 * ## Why this instrument
 *
 * The defect is in a COMPOSITION, so the instrument is a composition: the coordinator is built
 * with a detector that is not the strict one, and the row observes that the build completes. The
 * collaborators are the production in-memory ones — the journal and the cursor store are already
 * in-memory implementations, the event sink discards, the clock is fixed — so the row touches no
 * disk, no process, no network and no clock. The control root is a path that does NOT exist: if
 * anything in this composition tried to write there, the row would say so instead of passing
 * unnoticed.
 *
 * The second row is the other end of the same port: the resolver consults the detector it was
 * given. It is green before the cut and green after it — it is here to say what the port MEANS
 * (the detector's verdict IS the gate's verdict), and to reject a fix that narrowed the
 * collaborator instead of the coordinator.
 *
 * ## What this instrument cannot see
 *
 * It does not observe, THROUGH the coordinator, which detector a real run consults: the only path
 * that consults it is a full durable run, and that is not a unit test. What it does observe is the
 * composition that decides it — the coordinator accepts any implementation, and the resolver asks
 * the one it holds.
 */
class DivergenceDetectorPortTest {

    @Test
    @DisplayName("the coordinator composes a detector that is not the strict one")
    fun theCoordinatorComposesADetectorThatIsNotTheStrictOne() {
        // Before the cut this threw ClassCastException: the coordinator narrowed the interface
        // to the concrete class before handing it to the resolver. The failure was at
        // CONSTRUCTION, so it was invisible to every caller except as a run that never starts.
        val coordinator = coordinator(AlwaysDivergentDetector())

        assertNotNull(coordinator)
    }

    @Test
    @DisplayName("the resolver consults the detector it was given")
    fun theResolverConsultsTheDetectorItWasGiven() {
        val current = operation()

        val diverged = resolver(AlwaysDivergentDetector())
            .deterministicGate(current, current, "op-1", emptySet(), ReplayPolicy.RERUN)
        assertInstanceOf(
            InvocationReconciliation.Diverged::class.java,
            diverged,
            "a detector that reports divergence must close the gate",
        )

        val permitted = resolver(PermissiveDetector())
            .deterministicGate(current, current, "op-1", emptySet(), ReplayPolicy.RERUN)
        assertNull(permitted, "a detector that reports no divergence must leave the gate open")
    }

    // --- the compositions, every collaborator of them in memory --------------

    /**
     * The coordinator, composed with the detector it is given.
     *
     * The control root points at a directory that does not exist. Nothing in this composition may
     * write to it, and a write would fail this row rather than pass unnoticed.
     */
    private fun coordinator(detector: DivergenceDetector): CanonicalDurableRunCoordinator =
        CanonicalDurableRunCoordinator(
            journal = InMemoryOperationJournal(FixedClock),
            cursorStore = InMemoryReplayCursorStore(FixedClock),
            clock = FixedClock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = NullEventSink,
            credentialScopePort = CredentialScopePort { _, _ ->
                CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("not used"))
            },
            controlDirRoot = Path.of("/nonexistent-control-root"),
            divergenceDetector = detector,
            // The coordinator resolves every step through the open registry; this row
            // never dispatches one, it only exercises the divergence gate.
            stepRegistry = CoreStepRegistryFactory.registry(),
        )

    /** The resolver, composed with the detector it is given. */
    private fun resolver(detector: DivergenceDetector): DurableInvocationResolver =
        DurableInvocationResolver(
            divergenceDetector = detector,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            journal = InMemoryOperationJournal(FixedClock),
            runningSubprocessRecovery = RunningSubprocessRecovery {
                RunningSubprocessObservation.Unavailable(UnobservableCause.NoControlRootConfigured)
            },
        )

    /** One operation, in the shape the journal holds it. */
    private fun operation(): RerunOperation {
        val input = OperationInput(
            stepId = "core.sh",
            params = emptyMap(),
            runId = "run-1",
            attempt = 1,
        )
        return RerunOperation(
            id = "run-1-s0-0",
            fingerprint = Fingerprint("a".repeat(64)),
            input = input,
            output = null,
            status = OperationStatus.RUNNING,
            attempt = 1,
        )
    }

    /** A clock that does not move, so nothing in these rows depends on the wall clock. */
    private object FixedClock : Clock {
        override fun now(): Instant = Instant.EPOCH
    }

    /** A detector that reports divergence for every operation. */
    private class AlwaysDivergentDetector : DivergenceDetector {
        override fun check(current: DurableOperation, journaled: DurableOperation?): Result<Unit> =
            Result.failure(
                DivergenceException(
                    expected = journaled?.fingerprint ?: current.fingerprint,
                    actual = current.fingerprint,
                    opId = current.id,
                    runId = current.input.runId,
                    stageIndex = 0,
                ),
            )
    }

    /** A detector that reports no divergence for every operation. */
    private class PermissiveDetector : DivergenceDetector {
        override fun check(current: DurableOperation, journaled: DurableOperation?): Result<Unit> =
            Result.success(Unit)
    }
}
