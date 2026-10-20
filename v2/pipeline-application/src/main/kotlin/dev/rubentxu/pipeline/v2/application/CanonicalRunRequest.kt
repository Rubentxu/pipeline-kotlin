package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.SagaCompensationBinding
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import dev.rubentxu.pipeline.v2.credentials.spi.CredentialProvider
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import java.nio.file.Path

/**
 * Where a run's durable state lives.
 *
 * This is the ONLY group of facts that differs between the in-memory world and
 * the durable (`--db`) world. Everything else — the clock, the replay policy,
 * the sandbox, the workspace, the credential seam — is decided once, the same
 * way, for both.
 *
 * Grouping it makes that claim checkable: if a fact is not in here, the two
 * worlds cannot disagree about it.
 */
internal data class CanonicalRunState(
    val journal: OperationJournal,
    val cursorStore: ReplayCursorStore,
    val eventSink: EventSink,
    val sagaBinding: SagaCompensationBinding,
)

/**
 * Everything a run was GIVEN: the ports it may use, the permissions it holds
 * and the places it writes to.
 *
 * Grouped because detekt allows ten constructor parameters and a run needs
 * seventeen facts. The grouping is by seam, not by convenience: each field
 * below answers a different question ("when is it now?", "may it reach the
 * network?", "where does it keep its control state?"), so a reviewer can read
 * one group without holding the others in mind.
 */
internal data class CanonicalRunPolicy(
    val clock: Clock,
    val effectReplayPolicy: EffectReplayPolicy,
    val controlDirRoot: Path,
    val sandboxProfile: SandboxProfile,
    val workspace: RuntimeWorkspaceTransport,
    val credentialProvider: CredentialProvider?,
    val withCredentialsExecutor: WithCredentialsExecutor?,
    val allowNetwork: Boolean,
    val secretPatternRegistry: SecretPatternRegistry,
)

/**
 * The one and only description of "run this pipeline", shared by the
 * in-memory world and the durable world.
 *
 * Both worlds build one of these and hand it to [runCanonical]. There is no
 * second call site, so the two worlds cannot drift: the difference between
 * "runs in memory" and "runs in SQL" is the [state] group, and nothing else.
 */
internal data class CanonicalRunRequest(
    val pipeline: CompiledPipeline,
    val runId: RunId,
    val state: CanonicalRunState,
    val policy: CanonicalRunPolicy,
    val composition: PreResolvedComposition,
)
