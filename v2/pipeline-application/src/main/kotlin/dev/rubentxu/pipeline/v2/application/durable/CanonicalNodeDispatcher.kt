package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
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
    // LFC-2E3-P / P2: canonical step-output resolution, exposed to a consumer handler that DECLARES
    // the `step.output.resolver` capability. Null means "this invocation has no run-scoped output
    // registry", in which case the capability is simply absent and admission fails closed for any
    // Step that requires it — never a silent no-op.
    val stepOutputResolver: dev.rubentxu.pipeline.v2.domain.step.StepOutputResolver? = null,
)

/** Dispatches the supported canonical core nodes through their durable runtime paths. */
class CanonicalNodeDispatcher {
    // WU-G5B (2026-09-17): cleanWsDispatcher removed (LEGACY_REMOVED) — core.cleanWs executes
    // exclusively through CoreCleanWsStep via the registry.
    // S2-A5 / CORE-LOAD-REJECTED (2026-09-17): loadDispatcher removed (REJECTED) — core.load
    // is REJECTED: the legacy path was a silent no-op (SPIKE-018 §1.4) plus a latent
    // contract defect (SPIKE-018 §1.2). The Step has zero real-world fixtures in the
    // compatibility corpus. See docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md.
    // WU-G5B (2026-09-17): waitUntilDispatcher removed (LEGACY_REMOVED) — core.waitUntil
    // executes exclusively through the canonical RepeatUntil machinery
    // (BlockStepNode(BodyExecutionPolicy.RepeatUntil) → dispatchRepeatUntilBody).
    // S2-B10 / G5 (2026-09-13): archiveArtifactsDispatcher removed (LEGACY_REMOVED) —
    // core.archiveArtifacts executes exclusively through CoreArchiveArtifactsStep via the registry.

    suspend fun dispatch(command: CanonicalCoreStepCommand, context: CanonicalRuntimeContext): StepOutcome =
        when (command) {
            // S2-A4 / G5: EmitEvent when-branch removed (LEGACY_REMOVED).
            // S2-A9 / G5: Milestone when-branch removed (LEGACY_REMOVED) — core.milestone executes
            // exclusively through CoreMilestoneStep via the registry.
            // S2-A7 / G5: DeleteDir when-branch removed (LEGACY_REMOVED).
            // S2-A10 / G5 (2026-09-13): CleanWs when-branch removed (LEGACY_REMOVED) —
            // core.cleanWs executes exclusively through CoreCleanWsStep via the registry.
            // S2-A5 / CORE-LOAD-REJECTED (2026-09-17): Load when-branch removed (REJECTED).
            // core.load is REJECTED; the legacy dispatcher is unreachable in production and
            // the canonical decoder no longer recognises the key.
            // S2-A6 / G5: Pwd when-branch removed (LEGACY_REMOVED).
            // S2-A5 / G5: IsUnix when-branch removed (LEGACY_REMOVED).
            // WU-G5B (2026-09-17): WaitUntil when-branch removed (LEGACY_REMOVED).
            // core.waitUntil executes exclusively through the canonical RepeatUntil
            // machinery (BodyExecutionPolicy.RepeatUntil → dispatchRepeatUntilBody in
            // CanonicalDurableRunCoordinator). The DSL `waitUntil { body }` lowers to
            // StepSpec.WaitUntilBlock → BlockStepNode(BodyExecutionPolicy.RepeatUntil) and
            // never reaches this dispatcher.
            // S2-B10 / G5 (2026-09-13): ArchiveArtifacts when-branch removed (LEGACY_REMOVED) —
            // core.archiveArtifacts executes exclusively through CoreArchiveArtifactsStep via the
            // registry. The `when` stays EXHAUSTIVE over the surviving sealed subtypes: a
            // reintroduced legacy subtype is now a compile error, not a silent fall-through.
            // CORE-LOAD-REJECTED (2026-09-17): with core.load REJECTED, all sealed subtypes
            // are LEGACY_REMOVED. The dispatcher now contains zero arms and a typed rejection;
            // any future legacy subtype reintroduced is a compile error.
            else -> throw IllegalArgumentException(
                "Unsupported core plugin step for dispatch: ${command::class.simpleName}",
            )
        }

    // S2-A4 / G5: emitEventContext() removed with the legacy dispatcher (LEGACY_REMOVED).
    // S2-A9 / G5: milestoneContext() removed with the legacy dispatcher (LEGACY_REMOVED).
    // S2-A7 / G5: deleteDirContext() removed with the legacy dispatcher (LEGACY_REMOVED).
    // S2-A10 / G5 (2026-09-13): cleanWsContext() removed with the legacy dispatcher (LEGACY_REMOVED).
    // S2-B10 / G5 (2026-09-13): archiveArtifactsContext() removed with the legacy dispatcher
    // (LEGACY_REMOVED). Its `workspaceRoot = shOptions.workspaceRoot` absolute-path anchor was
    // the frozen-glob defect root cause (frozen delta D1).
    // WU-G5B (2026-09-17): waitUntilContext() removed with the legacy dispatcher (LEGACY_REMOVED).
    // S2-A5 / CORE-LOAD-REJECTED (2026-09-17): loadContext() removed with the rejected dispatcher.
    // The sealed hierarchy is now empty; no per-RuntimeContext derivation helpers remain.

}
