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
 * The adapter binds the canonical [OpId] of the invocation (handed in from
 * the [CanonicalRuntimeContext] at construction time, so it already carries
 * the stage/step indices AND any branch/bodyPath identity — e.g. the
 * `-bp2-b0:branch-...` form for parallel-branch steps). Deriving a fresh
 * `OpId(runId, 0, stepIndex)` per call would collapse two concurrent parallel
 * branches onto the SAME control dir (`{controlRoot}/{opId}`), interleaving
 * their `script.sh`/`result.txt` writes — the corruption observed in
 * B13/E-EM-11 WL-P2/P3. The control dir is derived from `opId.format()` in
 * [ShExecution.invokeShell], so the opId IS the durable process identity.
 */
class ShOperationsAdapter(
    private val runIdString: String,
    private val opId: OpId,
    private val shOptions: dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions,
    private val controlDirRoot: java.nio.file.Path?,
    private val eventSink: EventSink,
) : ShellOperations {

    override suspend fun invoke(
        command: ShellCommand,
        runId: RunId,
        stepIndex: Int,
    ): ShellInvocationResult {
        return ShExecution.invokeShell(
            command = command,
            opId = this.opId,
            runId = runIdString,
            stageIndex = 0,
            stepIndex = stepIndex,
            shOptions = shOptions,
            controlDirRoot = controlDirRoot,
            eventSink = eventSink,
        )
    }
}
