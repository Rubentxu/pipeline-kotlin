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
)
