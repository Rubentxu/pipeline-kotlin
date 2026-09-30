package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.MilestoneStateStore
import dev.rubentxu.pipeline.v2.application.StepMetadataResolver
import dev.rubentxu.pipeline.v2.application.durable.CanonicalBodyInvokerAdapter
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.step.BodyPolicyResolver
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.domain.step.artifact.ArtifactIndexCapability
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/**
 * Bundle of named dependencies that [CanonicalDurableRunCoordinator] consumes.
 *
 * C1-B (2026-09-26): the canonical coordinator's primary constructor has accumulated
 * 22 named parameters over the burn-down sequence (canonical dispatcher, journal,
 * cursor store, clock, replay policy, event sink, credential scope, control root,
 * workspace base, shell options, secret registry, divergence detector, metadata
 * resolver, invocation executor, common execution boundary, step registry, retry
 * control journal, wait-until control journal, milestone state store, artifact index,
 * injected body policy resolver, body invoker adapter). Direct injection of every
 * parameter at every call site is fragile: each new ADR slice that adds an optional
 * field touches the entire test surface. This data class centralises the dependency
 * bundle so the coordinator can be constructed via a single typed argument while
 * preserving the legacy 21-arg constructor for binary compatibility.
 *
 * The legacy `CanonicalDurableRunCoordinator(...)` constructor remains the public
 * surface; the caps bundle is exposed through an additional `constructor(caps)`
 * secondary that maps every field. The two ctors route through the same private
 * body, so behaviour is bit-equivalent.
 *
 * Default values mirror the legacy ctor defaults EXACTLY (including the
 * `StrictFingerprintDivergenceDetector`, `ShOptions.EMPTY`, `MilestoneStateStore()`,
 * `CanonicalBodyInvokerAdapter()` factories) so a caller that always passed named
 * arguments can adopt the bundle by deleting only the parameter names, not values.
 */
data class CoordinatorCaps(
    val dispatcher: CanonicalNodeDispatcher,
    val journal: OperationJournal,
    val cursorStore: ReplayCursorStore,
    val clock: Clock,
    val effectReplayPolicy: EffectReplayPolicy,
    val eventSink: EventSink,
    val credentialScopePort: CredentialScopePort,
    val controlDirRoot: Path? = null,
    val workspaceBase: Path? = null,
    val shOptions: ShOptions = ShOptions.EMPTY,
    val secretPatternRegistry: SecretPatternRegistry? = null,
    val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector(),
    val stepMetadataResolver: StepMetadataResolver? = null,
    val invocationExecutor: CanonicalInvocationExecutor? = null,
    val commonExecutionBoundary: CommonExecutionBoundary? = null,
    val stepRegistry: StepRegistry? = null,
    val retryControlJournal: FileBasedRetryControlJournal? = null,
    val waitUntilControlJournal: WaitUntilControlJournal? = null,
    val milestoneStateStore: MilestoneStateStore = MilestoneStateStore(),
    val artifactIndex: ArtifactIndexCapability? = null,
    val injectedBodyPolicyResolver: BodyPolicyResolver? = null,
    val bodyInvokerAdapter: CanonicalBodyInvokerAdapter = CanonicalBodyInvokerAdapter(),

    /** S1-B: optional open DirectiveRegistry (stage directive admission seam). */
    val directiveRegistry: dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry? = null,

    /**
     * S2-A: the facts a gate is evaluated against, read at the effect boundary
     * from the stage that carries the gate.
     */
    val gateContext: (
        stageEnvironment: dev.rubentxu.pipeline.v2.domain.EnvironmentSpec,
    ) -> dev.rubentxu.pipeline.v2.domain.directive.GateContext =
        { dev.rubentxu.pipeline.v2.domain.directive.GateContext.EMPTY },
)
