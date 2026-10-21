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
 * This group decides **whether the run survives the process**: the journal,
 * the cursor store, the event sink and the Saga seam. The in-memory world
 * binds in-memory implementations of all four; the durable (`--db`) world binds
 * the SQLite ones.
 *
 * ## What it does NOT decide
 *
 * Nothing here says the two worlds are otherwise identical, because they are
 * not, and pretending otherwise would be a lie in a KDoc. Two facts of
 * [CanonicalRunPolicy] differ too, and both on purpose:
 *
 * - `credentialProvider`: the in-memory world passes **no** provider (H5-B),
 *   so a Step that reads a credential gets `NotConfigured` — the honest answer
 *   for a run with nowhere to look;
 * - `controlDirRoot`: necessarily different, because the two worlds keep their
 *   control state in different places.
 *
 * And the two worlds do not even have the same set of execution routes: the
 * scripted-frontend form is resolved only in the durable world. That
 * asymmetry predates this file and is the honest reason to be careful with
 * the phrase "the same pipeline in both worlds".
 *
 * What this grouping buys is narrower and true: **the difference is now in
 * one reviewable place instead of spread over eighteen arguments at two call
 * sites.** Before, any of the eighteen could differ between the worlds and
 * nothing said which.
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
 * The description of "run this pipeline" that both worlds hand to
 * [runCanonical].
 *
 * [runCanonical] has exactly two call sites — one per world — and both of them
 * build one of these. There is no third, and there is no other way to reach
 * the canonical run, so the two worlds can only differ in the VALUES they put
 * here. Before this type existed they could differ in any of eighteen
 * arguments, at either of two call sites, and nothing said which.
 *
 * What the values may differ in is written down where it is decided, not here:
 * the [state] group and the two fields of [policy] that differ on purpose.
 */
internal data class CanonicalRunRequest(
    val pipeline: CompiledPipeline,
    val runId: RunId,
    val state: CanonicalRunState,
    val policy: CanonicalRunPolicy,
    val composition: PreResolvedComposition,
)
