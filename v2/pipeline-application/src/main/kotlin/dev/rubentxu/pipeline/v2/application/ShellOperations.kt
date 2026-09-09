package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult

/**
 * Typed seam that a registry-routed Step handler calls to invoke a shell
 * process (LB-02 / G3).
 *
 * This interface is the *only* contract a Step handler holds for shell
 * execution. The handler MUST NOT carry any process-launching logic itself.
 * It adapts to this typed seam which is implemented
 * by the certified `ShExecution.invokeShell` engine entry-point; the
 * capability system wires that adapter in [CanonicalRuntimeCapabilityAccess].
 *
 * ## Why a typed seam and not `ShExecution.invokeShell` directly?
 *
 * AGENTS.md §STEP IMPLEMENTATION — OPERATIVE GUIDE rule 9 (handler must
 * adapt to existing certified infrastructure). The previous Step
 * (`core.echo`) does not embed event emission in its handler either: it
 * reaches the typed [dev.rubentxu.pipeline.v2.events.EventSink] capability.
 * `core.sh` follows that same shape, only it reaches this shell-execution
 * capability.
 *
 * ## Scope
 *
 * The interface intentionally hides:
 * - operation id (`opId`) — produced once per step instance by the
 *   coordinator; the seam derives a fresh `OpId` per call.
 * - control-dir root — owned by the canonical runtime context.
 * - shell options — owned by the canonical runtime context.
 * - step index — taken from the call site.
 *
 * These flow through the runtime seam and are NOT re-derivable from a
 * contract-bound handler without coupling the Step to a global.
 *
 * ## Failure semantics
 *
 * Implementations return the closed
 * [ShellInvocationResult] ADT directly. Re-classification to
 * [dev.rubentxu.pipeline.v2.domain.StepOutcome] is the responsibility of
 * the registry execution boundary (or a future typed-output seam — see
 * `docs/v2/00-context/LB02_TYPED_OUTPUT_DECISION.md`).
 */
interface ShellOperations {

    /**
     * Invokes a shell command with durable semantics.
     *
     * @param command the typed shell command to run.
     * @param runId the current run identifier.
     * @param stepIndex the within-pipeline step index (used for event sequencing).
     * @return the closed typed shell invocation result.
     */
    suspend fun invoke(
        command: ShellCommand,
        runId: RunId,
        stepIndex: Int,
    ): ShellInvocationResult
}
