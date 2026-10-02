package dev.rubentxu.pipeline.v2.application.support

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.CommonExecutionBoundary
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions

/**
 * Central coordinator fixture for the durable spine test classes
 * (B1.2c3-S2.5.3 / WU-1).
 *
 * Provides:
 *  - [default] — production-like coordinator: core registry wired, composite metadata resolver,
 *    real seamed routing, optional recording boundary for characterization tests.
 *  - [negativeNoRegistry] — dual-only fixture with `stepRegistry = null` and
 *    `stepMetadataResolver = null` so the coordinator falls back to the legacy core authority.
 *    Used exclusively by explicit dual-characterization tests.
 *  - [noOpCredentialScopePort] — fail-closed credential port stub returning
 *    [CredentialScopeOutcome.Unavailable] with [CredentialScopeFailure.StoreUnavailable].
 *
 * This fixture is read-only with respect to production: it composes existing constructors and does
 * NOT introduce any new field, seam, or adapter. The fixture is the single migration target for
 * subsequent WUs (WU-2..WU-5) which will replace per-test coordinator constructions with calls into
 * [default] / [negativeNoRegistry].
 */
object CoordinatorFixture {

    /**
     * Production-like coordinator with the core [StepRegistry] wired and the seamed routing
     * authority active. `controlDirRoot = null`, `shOptions = ShOptions.EMPTY`,
     * `divergenceDetector = StrictFingerprintDivergenceDetector()`.
     *
     * @param recorder optional [CommonExecutionBoundary] (e.g. `RecordingBoundary`) for tests that
     *   need to observe calls on the real execution path; defaults to `null` (production routing).
     */
    fun default(
        clock: Clock = SystemClock(),
        journal: OperationJournal,
        eventSink: EventSink,
        recorder: CommonExecutionBoundary? = null,
    ): CanonicalDurableRunCoordinator = CanonicalDurableRunCoordinator(
        dispatcher = CanonicalNodeDispatcher(),
        journal = journal,
        cursorStore = InMemoryReplayCursorStore(clock),
        clock = clock,
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        eventSink = eventSink,
        credentialScopePort = noOpCredentialScopePort(),
        controlDirRoot = null,
        shOptions = ShOptions.EMPTY,
        divergenceDetector = StrictFingerprintDivergenceDetector(),
        commonExecutionBoundary = recorder,
        stepRegistry = CoreStepRegistryFactory.registry(),
    )

    /**
     * Custom-registry overload: same production defaults as [default] but the [stepRegistry] is
     * injected by the caller. Used by tests that need per-test counters / definitions
     * (e.g. `RegistryDurableSpineTest` DREG-1..5).
     */
    fun default(
        clock: Clock = SystemClock(),
        journal: OperationJournal,
        eventSink: EventSink,
        stepRegistry: StepRegistry,
    ): CanonicalDurableRunCoordinator = CanonicalDurableRunCoordinator(
        dispatcher = CanonicalNodeDispatcher(),
        journal = journal,
        cursorStore = InMemoryReplayCursorStore(clock),
        clock = clock,
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        eventSink = eventSink,
        credentialScopePort = noOpCredentialScopePort(),
        controlDirRoot = null,
        shOptions = ShOptions.EMPTY,
        divergenceDetector = StrictFingerprintDivergenceDetector(),
        stepRegistry = stepRegistry,
    )

    /**
     * OFFICIAL_PLUGIN fixture: production defaults, plus the two seams a plugin Step needs
     * that `ShOptions.EMPTY` cannot express.
     *
     * WU-093 H7 added it because "the plugin could not run" and "the plugin did not run"
     * must be distinguishable in a test. With [shOptions] left at `EMPTY` — whose
     * `networkEgress` is [dev.rubentxu.pipeline.v2.domain.step.DenyAll] — `http.request`
     * is refused at admission, so a replay test asserting "the handler did not run"
     * would pass for the wrong reason: it could not tell a replay abort from a
     * capability denial. Handing the coordinator a real transport and an allowing gate is
     * what makes zero sends mean the replay law stopped it, and nothing else.
     *
     * It also refuses to be a second coordinator construction: the parameters are the
     * same ones `CompositionRoot` supplies in production, so a test and the CLI agree
     * about which capabilities exist.
     */
    fun default(
        clock: Clock = SystemClock(),
        journal: OperationJournal,
        eventSink: EventSink,
        stepRegistry: StepRegistry,
        shOptions: ShOptions,
        capabilityContributor: dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor,
    ): CanonicalDurableRunCoordinator = CanonicalDurableRunCoordinator(
        dispatcher = CanonicalNodeDispatcher(),
        journal = journal,
        cursorStore = InMemoryReplayCursorStore(clock),
        clock = clock,
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        eventSink = eventSink,
        credentialScopePort = noOpCredentialScopePort(),
        controlDirRoot = null,
        shOptions = shOptions,
        divergenceDetector = StrictFingerprintDivergenceDetector(),
        commonExecutionBoundary = null,
        stepRegistry = stepRegistry,
        capabilityContributor = capabilityContributor,
    )

    /**
     * Dual-only fixture: NO core registry, NO injected metadata resolver. The coordinator falls
     * back to the legacy core metadata authority and the legacy adapter over the dispatcher.
     * Reserved for explicit dual-characterization tests that exercise the legacy path on purpose.
     */
    fun negativeNoRegistry(
        clock: Clock = SystemClock(),
        journal: OperationJournal,
        eventSink: EventSink,
    ): CanonicalDurableRunCoordinator = CanonicalDurableRunCoordinator(
        dispatcher = CanonicalNodeDispatcher(),
        journal = journal,
        cursorStore = InMemoryReplayCursorStore(clock),
        clock = clock,
        effectReplayPolicy = DefaultEffectReplayPolicy(),
        eventSink = eventSink,
        credentialScopePort = noOpCredentialScopePort(),
        controlDirRoot = null,
        shOptions = ShOptions.EMPTY,
        divergenceDetector = StrictFingerprintDivergenceDetector(),
        stepRegistry = null,
    )

    /**
     * Fail-closed credential scope port. Coordinator tests that do not exercise `withCredentials`
     * must never dispatch a credential body; this stub returns
     * [CredentialScopeOutcome.Unavailable] with [CredentialScopeFailure.StoreUnavailable] so the
     * coordinator folds any accidental dispatch into an `INFRASTRUCTURE` Failure rather than
     * silently passing.
     */
    fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable(
                "No credential store in this coordinator unit test",
            ),
        )
    }
}
