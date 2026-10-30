package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalRunPolicy
import dev.rubentxu.pipeline.v2.application.CanonicalRunState
import dev.rubentxu.pipeline.v2.application.PluginComposition
import dev.rubentxu.pipeline.v2.application.RuntimeWorkspaceTransport
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceException
import dev.rubentxu.pipeline.v2.domain.durable.DurableOperation
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.domain.directive.GateContext
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceOwnership
import dev.rubentxu.pipeline.v2.events.NullEventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.InMemorySagaObligationStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
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
 * collaborator ([DurableInvocationResolver]), so the only thing that could stand between the two
 * is a downcast in the coordinator's own body:
 *
 * ```kotlin
 * divergenceDetector = divergenceDetector as StrictFingerprintDivergenceDetector
 * ```
 *
 * That cast WAS the defect. It narrowed a declared port to ONE implementation, so every detector
 * that is not [dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector] died
 * with a [ClassCastException] at CONSTRUCTION — before the pipeline had said a single word. The
 * caller could not choose a different divergence policy, and could not even find out that the
 * choice was refused: the run never started.
 *
 * ## The capacity this serves
 *
 * The durable engine can be composed with a divergence policy other than the strict fingerprint
 * one — a policy that compares something else, or one that a test injects. The parameter says it
 * can. A cast would say it cannot, and would say it by throwing.
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
 * The composition is now written in the coordinator's CURRENT shape — the three groups (state,
 * policy, composition) plus the ports it is given — so this row also says that the grouping
 * accepts a detector that is not the strict one, and that a caller who says nothing about the
 * divergence policy still gets a complete composition.
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
            state = state(),
            policy = policy(),
            composition = PluginComposition.resolve(pluginClassLoader = null),
            credentialScopePort = CredentialScopePort { _, _ ->
                CredentialScopeOutcome.Unavailable(CredentialScopeFailure.StoreUnavailable("not used"))
            },
            divergenceDetector = detector,
            // This row never dispatches a stage, so the gate's facts are empty. The gate
            // DECISION is pure and lives in the domain; the engine interprets it.
            gateContext = { GateContext.EMPTY },
            // Both control journals are absent, so the run would keep the inline retry loop
            // and the inline polling. Their file-backed counterparts create their directory in
            // their constructor, so binding them here would write to disk at composition time.
            retryControlJournal = null,
            waitUntilControlJournal = null,
            // The scripted seam is not bound: the scripted frontend runs on its own runner.
            scriptedStageExecution = null,
        )

    /** The run's durable spine: the journal, the cursor store, the sink and the Saga seam. */
    private fun state(): CanonicalRunState = CanonicalRunState(
        journal = InMemoryOperationJournal(FixedClock),
        cursorStore = InMemoryReplayCursorStore(FixedClock),
        eventSink = NullEventSink,
        outputWakeups = null,
        sagaBinding = SagaCompensationBinding(InMemorySagaObligationStore(), LEASE_EPOCH),
    )

    /** What the run was given: the clock, the replay policy, the control root and the rest. */
    private fun policy(): CanonicalRunPolicy = CanonicalRunPolicy(
        clock = FixedClock,
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        controlDirRoot = Path.of("/nonexistent-control-root"),
        sandboxProfile = SandboxProfile.NONE,
        // No workspace base: the run would resolve the per-stage layout under the control root,
        // and nothing in this composition creates one.
        workspace = RuntimeWorkspaceTransport(base = null, ownership = WorkspaceOwnership.PIPELINEK),
        credentialProvider = null,
        withCredentialsExecutor = null,
        allowNetwork = false,
        secretPatternRegistry = SecretPatternRegistry(),
    )

    /** The resolver, composed with the detector it is given. */
    private fun resolver(detector: DivergenceDetector): DurableInvocationResolver =
        DurableInvocationResolver(
            divergenceDetector = detector,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            records = DurableInvocationRecord(InMemoryOperationJournal(FixedClock)),
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

    private companion object {
        const val LEASE_EPOCH = 1L
    }
}
