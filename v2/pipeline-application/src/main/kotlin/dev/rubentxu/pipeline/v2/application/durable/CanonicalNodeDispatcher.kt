package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand

/**
 * Stub dispatcher preserved for binary compatibility with existing call sites (tests and
 * production paths that pass `CanonicalNodeDispatcher()` to the coordinator). The class
 * retained its name because it is part of the durable infrastructure surface; the body
 * it used to dispatch is now exclusively in [CanonicalCoreStepCommand] subtypes that are
 * Themselves deleted (LEGACY_REMOVED, /)
 * Every legacy canonical core Step subtype is gone
 * (CanonicalCoreStepCommand.Load / WaitUntil / Pwd / IsUnix / EmitEvent / Milestone /
 * DeleteDir / CleanWs / ArchiveArtifacts / Error are all LEGACY_REMOVED). The remaining
 * subtypes are produced only by structural / block commands and the registry, so this
 * dispatcher has no remaining concrete dispatch work. Its presence in test fixtures is
 * preserved so existing wiring does not break bit-equivalent; if a future subtype is
 * reintroduced, that subtype MUST route through the registry, not through this class.
 * Counter-preserved proof: [LEGACY_PLUGIN_IDS] is empty, every production StepKey routes
 * through the registry family in [StructuralFamilyResolver], and the
 * [CanonicalCoreStepDecoder] decoder falls through to a typed rejection for any unknown
 * legacy envelope.
 * The `CanonicalRuntimeContext` data class that previously lived here
 * has been extracted to `CanonicalRuntimeContext.kt` (same package). The extraction
 * Follows the// partition plan
 * and is bit-equivalent at the FQN level: 62 referencing files continue to compile
 * without import edits because the import path
 * `dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext` is unchanged.
*/
class CanonicalNodeDispatcher {
    // No fields. The dispatch surface has been emptied by the burn-down
    //   EmitEventDispatcher removed at /
    //   MilestoneDispatcher removed at /
    //   DeleteDirDispatcher removed at /
    //   CleanWsDispatcher removed at / 
    //   PwdDispatcher removed at /
    //   IsUnixDispatcher removed at /
    //   ArchiveArtifactsDispatcher removed at / 
    //   LoadDispatcher removed at / 
    //   WaitUntilDispatcher removed at / 
    // The dispatch function is removed because CanonicalCoreStepCommand has no surviving
    // subtypes (its sealed subtypes were deleted with their dispatchers). The class is
    // preserved only to keep existing constructor calls compiling bit-equivalent.

    /**
     * Signature-preserving fallback for callers that historically invoked the legacy dispatcher.
     * The legacy core canonical command set is fully retired. Every
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
