package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Path

/**
 * Runtime dependencies required by the canonical core step dispatcher.
 *
 * [bodyInvoker] is the B11 / W2 seam: a per-run [CanonicalBodyInvokerAdapter] that re-enters
 * the canonical body machinery (`invokeBodyChildren`) for block-step handlers that declare
 * `BODY_INVOKER_CAPABILITY`. Defaulted to `null` so every pre-existing constructor site
 * (legacy dispatch, registry-aware dispatch, scripted runners, and ~25 unit tests)
 * compiles bit-equivalent. When `null` the capability bridge does NOT register
 * `BODY_INVOKER_CAPABILITY` — admission fails closed for any handler that declares it,
 * which is the correct behaviour: only runs that explicitly wire the adapter expose
 * the body-reentry seam.
 */
data class CanonicalRuntimeContext(
    val opId: OpId,
    val runId: String,
    val stageName: String,
    val stageIndex: Int,
    val stepIndex: Int,
    val shOptions: ShOptions,
    val controlDirRoot: Path?,
    val eventSink: EventSink,
    val bodyInvoker: CanonicalBodyInvokerAdapter? = null,
    // WU-LPR-011 secret-redaction slice: the active secret pattern registry,
    // threaded to the shell-operations adapter so the console transcript is
    // redacted chunk-boundary-safe BEFORE it reaches the observable plane.
    // Optional (null → no transcript redaction) so legacy callers/tests that
    // construct the context directly keep compiling; the production wire-up
    // in CanonicalDurableRunCoordinator always supplies it.
    val secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
)

/**
 * Stub dispatcher preserved for binary compatibility with existing call sites (tests and
 * production paths that pass `CanonicalNodeDispatcher()` to the coordinator). The class
 * retained its name because it is part of the durable infrastructure surface; the body
 * it used to dispatch is now exclusively in [CanonicalCoreStepCommand] subtypes that are
 * themselves deleted (LEGACY_REMOVED, WU-LPR-301 / G5).
 *
 * WU-LPR-301 / G5 (2026-09-18): every legacy canonical core Step subtype is gone
 * (CanonicalCoreStepCommand.Load / WaitUntil / Pwd / IsUnix / EmitEvent / Milestone /
 * DeleteDir / CleanWs / ArchiveArtifacts / Error are all LEGACY_REMOVED). The remaining
 * subtypes are produced only by structural / block commands and the registry, so this
 * dispatcher has no remaining concrete dispatch work. Its presence in test fixtures is
 * preserved so existing wiring does not break bit-equivalent; if a future subtype is
 * reintroduced, that subtype MUST route through the registry, not through this class.
 *
 * Counter-preserved proof: [LEGACY_PLUGIN_IDS] is empty, every production StepKey routes
 * through the registry family in [StructuralFamilyResolver], and the
 * [CanonicalCoreStepDecoder] decoder falls through to a typed rejection for any unknown
 * legacy envelope.
 */
class CanonicalNodeDispatcher {
    // No fields. The dispatch surface has been emptied by the WU-LPR-301 burn-down:
    //   - emitEventDispatcher removed at S2-A4 / G5
    //   - milestoneDispatcher  removed at S2-A9 / G5
    //   - deleteDirDispatcher  removed at S2-A7 / G5
    //   - cleanWsDispatcher    removed at S2-A10 / G5 (2026-09-13)
    //   - pwdDispatcher        removed at S2-A6 / G5
    //   - isUnixDispatcher     removed at S2-A5 / G5
    //   - archiveArtifactsDispatcher removed at S2-B10 / G5 (2026-09-13)
    //   - loadDispatcher       removed at WU-LPR-301 / G5 (2026-09-18)
    //   - waitUntilDispatcher  removed at WU-LPR-301 / G5 (2026-09-18)
    //
    // The dispatch() function is removed because CanonicalCoreStepCommand has no surviving
    // subtypes (its sealed subtypes were deleted with their dispatchers). The class is
    // preserved only to keep existing constructor calls compiling bit-equivalent.

    /**
     * Signature-preserving fallback for callers that historically invoked the legacy dispatcher.
     *
     * WU-LPR-301 / G5 (2026-09-18): the legacy core canonical command set is fully retired. Every
     * production StepKey routes through the registry family in [StructuralFamilyResolver] (see
     * `LEGACY_PLUGIN_IDS == emptySet()`), and the canonical decoder rejects unknown legacy
     * envelopes typed. There is therefore no value of [command] for which this method could
     * dispatch a real Step. The signature is kept so the three historical callsites in
     * `ExecutionBoundaryFactory.kt` and `FamilyRouter.kt` continue to compile bit-equivalent;
     * the method is unreachable in production (the registry boundary always wins when a
     * StepRegistry is wired) and fails closed with a typed [UnsupportedOperationException] if a
     * caller does reach it.
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun dispatch(command: CanonicalCoreStepCommand, context: CanonicalRuntimeContext): Nothing {
        throw UnsupportedOperationException(
            "CanonicalNodeDispatcher.dispatch is retired at WU-LPR-301 / G5 (2026-09-18): " +
                "every legacy canonical core Step subtype is LEGACY_REMOVED and the registry is the " +
                "sole execution authority. Got command pluginId='${command.pluginId}'."
        )
    }
}
