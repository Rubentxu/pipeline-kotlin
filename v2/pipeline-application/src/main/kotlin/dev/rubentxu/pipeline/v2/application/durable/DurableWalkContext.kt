package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.EventSink
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.application.BranchReconciler
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.StepReconcilerL1
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.executor.WithCredentialsExecutor
import java.nio.file.Path

/**
 * Context passed through a durable walk (step execution loop).
 *
 * Collapses the 5 shared parameters that flow through every step execution
 * into a single data class, making the `executeDurableStep` signature
 * more readable and refactorable.
 *
 * @property clock The clock used for timestamps and deadline computation.
 * @property opJournal The operation journal for durable recording.
 * @property cursorStore The replay cursor store for checkpoint tracking.
 * @property branchReconciler The branch reconciler for kill+resume recovery.
 * @property eventSink The event sink for emitting domain events.
 * @property stepReconcilerL1 The L1 step reconciler for durable shell classification.
 *                          Used during resume to classify RUNNING rows as COMPLETE/REATTACH/LOST/TIMED_OUT.
 * @property controlDirRoot The root directory for all control directories.
 *                         Set from --db parent in Main.kt.
 * @property workspaceResolver Resolves per-stage workspace directories.
 *                           Created from --workspace-root CLI flag (defaults to --db parent).
 * @property shOptions Default shell execution options (workspaceRoot, captureStdout, timeoutMs, env).
 *                   Threaded from stage/step configuration. Step-level env overrides stage-level.
 *
 * Designed to be constructed once per pipeline run and passed through
 * all step executions. Immutable after construction.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
data class DurableWalkContext(
    val clock: Clock,
    val opJournal: OperationJournal,
    val cursorStore: ReplayCursorStore,
    val branchReconciler: BranchReconciler,
    val eventSink: EventSink,
    /**
     * L1 step reconciler for durable shell classification.
     *
     * Used during resume to classify RUNNING rows:
     * - COMPLETE: result.txt exists + heartbeat fresh → use cached exit code
     * - REATTACH: result.txt missing + heartbeat fresh → re-run with same fingerprint
     * - TIMED_OUT: timeout.flag present → watchdog killed the process (ML-R2)
     * - LOST: result.txt missing + heartbeat stale → fail-closed per UAT-REC-002
     */
    val stepReconcilerL1: StepReconcilerL1? = null,
    /**
     * Root directory for control directories.
     *
     * Set from --db parent in Main.kt. Each step gets a subdirectory:
     * $controlDirRoot/$opId/
     *
     * Layout:
     * ```
     * controlDirRoot/
     *   runId-s0-0/
     *     script.sh
     *     jenkins-log.txt
     *     result.txt
     *     ...
     *   runId-s0-1/
     *     ...
     * ```
     */
    val controlDirRoot: Path? = null,
    /**
     * Resolves per-stage workspace directories.
     *
     * Created from --workspace-root CLI flag (defaults to --db parent).
     * Each stage gets: `<controlDirRoot>/workspace/<stageName>-<stageIndex>/`
     *
     * @see WorkspaceResolver
     */
    val workspaceResolver: WorkspaceResolver? = null,
    /**
     * Default shell execution options for this pipeline run.
     *
     * These options are derived from stage/step configuration and include:
     * - workspaceRoot: per-stage workspace directory
     * - captureStdout: whether to capture stdout to output.txt
     * - timeoutMs: execution timeout (null = no timeout)
     * - env: environment variables to inject
     * - sandbox: sandbox profile (NONE, LOCAL, OS)
     *
     * Step-level options override stage-level options via merge in PipelineOrchestrator.
     */
    val shOptions: ShOptions? = null,
    /**
     * Sandbox profile for this pipeline run (ML-R3).
     *
     * NONE: no sandbox (backward compatible)
     * LOCAL: apply deny-list + PATH normalization
     * OS: container-based sandbox (not implemented in L3 — throws at CLI)
     */
    val sandboxProfile: SandboxProfile = SandboxProfile.NONE,
    /**
     * Secret store for credential resolution in withCredentials blocks (T11).
     *
     * When non-null, credentials can be resolved and injected into step execution.
     * When null, withCredentials blocks execute inner steps without credential injection.
     */
    val secretStore: SecretStore? = null,
    /**
     * Port-driven executor for credential binding in withCredentials blocks (H0).
     *
     * When non-null, credentials are bound via this executor which delegates to SPI ports.
     * When null, withCredentials blocks use the inline legacy path.
     */
    val withCredentialsExecutor: WithCredentialsExecutor? = null,
)
