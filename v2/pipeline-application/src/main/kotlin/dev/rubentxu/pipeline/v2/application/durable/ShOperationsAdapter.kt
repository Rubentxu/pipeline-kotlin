package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.ShellOperations
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.events.EventSink

/**
 * The certified engine entry-point (`ShExecution.invokeShell`) used by
 * `core.sh` handlers (LB-02 / G3 REGISTRY_PRIMARY).
 *
 * Implements [ShellOperations] by adapting the typed seam to the durable
 * runtime substrate. The adapter is the ONLY thing in this file that holds
 * an [EventSink] / `controlDirRoot` / `shOptions` reference; the handler
 * never sees those, only the typed `ShellCommand` and the typed
 * `ShellInvocationResult` back.
 *
 * Why an adapter rather than calling `ShExecution.invokeShell` directly
 * from the handler:
 *
 * - **Capability discipline**: the handler reaches it through the
 *   `SHELL_OPERATIONS_CAPABILITY` token (declare-on-contract, fail-closed
 *   admission). A direct import of `ShExecution` would bypass the
 *   capability admission gate.
 * - **Test discipline**: the [ShellOperations] interface can be substituted
 *   with a stub for unit tests of the handler without booting a real
 *   coordinator.
 * - **AGENTS.md invariant**: handlers adapt to typed seams; they never
 *   own process-execution logic.
 *
 * The adapter derives `OpId` per call from the `runId` and `stepIndex`
 * pair (sufficient for one process per step — the canonical pattern used
 * by the existing [CanonicalShellNodeDispatcher]).
 */
class ShOperationsAdapter(
    private val runIdString: String,
    private val shOptions: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions,
    private val controlDirRoot: java.nio.file.Path?,
    private val eventSink: EventSink,
) : ShellOperations {

    override suspend fun invoke(
        command: ShellCommand,
        runId: RunId,
        stepIndex: Int,
    ): ShellInvocationResult {
        // The opId format must match the canonical canonical-driver pattern
        // (`OpId.format()` yields "<runId>/<stageIndex>/<stepIndex>"); the
        // canonical dispatcher uses the same shape through CanonicalRuntimeContext.
        // LB-02 G3 does NOT need to invent a fresh scheme — it derives one
        // consistent with the canonical family.
        val opId = OpId(runIdString, 0, stepIndex)
        return ShExecution.invokeShell(
            command = command,
            opId = opId,
            runId = runIdString,
            stageIndex = 0,
            stepIndex = stepIndex,
            shOptions = shOptions,
            controlDirRoot = controlDirRoot,
            eventSink = eventSink,
        )
    }
}
