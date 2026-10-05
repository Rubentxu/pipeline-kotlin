package dev.rubentxu.pipeline.v2.application.durable

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
 *
 * C1-A (2026-09-26): extracted from `CanonicalNodeDispatcher.kt` to its own file as part
 * of the H1/H2/H3 partition plan (`docs/v2/06-quality/C1_ISOLATION_2026_09_26.md`).
 * The data class is the canonical seam between the dispatcher body and the runtime
 * adapters; isolating it shrinks `CanonicalNodeDispatcher.kt` from 100 LOC to 67 LOC
 * and gives the field bundle its own focus. The FQN
 * (`dev.rubentxu.pipeline.v2.application.durable.CanonicalRuntimeContext`) is
 * unchanged, so every existing `import` and bare usage in the 62 referencing files
 * continues to bind without edits.
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
    // WU-RP-035: the body-ALREADY-BOUND continuation of a Step that declared
    // BodyExecutionOwner.HANDLER_CONTINUATION, supplied under
    // BODY_CONTINUATION_CAPABILITY to that Step's handler only.
    //
    // Distinct from [bodyInvoker] on purpose, and not a wrapper around it: the
    // continuation already carries the engine-issued BodyRef, so the handler never
    // sees an identity it could correlate, derive or outlive. Null for every other
    // Step (and for every pre-existing construction site), which is what keeps the
    // fail-closed admission honest: a handler that declares the capability never
    // runs when the engine did not bind it.
    val bodyContinuation: dev.rubentxu.pipeline.v2.domain.step.BodyContinuation? = null,
    // WU-LPR-011 secret-redaction slice: the active secret pattern registry,
    // threaded to the shell-operations adapter so the console transcript is
    // redacted chunk-boundary-safe BEFORE it reaches the observable plane.
    // Optional (null → no transcript redaction) so legacy callers/tests that
    // construct the context directly keep compiling; the production wire-up
    // in CanonicalDurableRunCoordinator always supplies it.
    val secretPatternRegistry: dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry? = null,
    // WU-LPR-062: optional project-workspace override (--workspace <dir>). When set,
    // stage workspaces resolve under <dir> instead of <controlRoot>/workspace, so real
    // project fixtures (Gradle/Maven/Node) execute against the actual project files.
    val workspaceBase: java.nio.file.Path? = null,
    // P3-C / S6.4: the open event registry composed from plugin contributors, already bound to
    // THIS run's sink and clock as a [dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter].
    //
    // It rides here rather than as a constructor parameter of
    // [CanonicalRuntimeCapabilityAccess] on purpose. That class is constructed at FOUR sites —
    // two of them prepare-time — and admission has to see exactly the keys the handler will get,
    // so a per-call parameter would have to be threaded through all four or the Step would be
    // admitted for a capability it never receives. The context is already the per-run carrier that
    // all four share, and it already carries the [eventSink] half of this pair.
    //
    // Null when no contributor declared anything (and for every pre-existing construction site),
    // so the capability stays unexposed and a Step that declares it is refused at admission
    // instead of reaching a handler with a null seam.
    val pluginEventEmitter: dev.rubentxu.pipeline.v2.events.registry.RegistryEventEmitter? = null,
)
