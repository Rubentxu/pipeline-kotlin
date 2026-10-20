package dev.rubentxu.pipeline.v2.application.scripted

import dev.rubentxu.pipeline.v2.application.CoreShellInput
import dev.rubentxu.pipeline.v2.application.CoreShellStep
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult

/**
 * S4-A1 — the scripted shell reaches the durable engine through the REGISTRY,
 * or it does not run at all.
 *
 * ## Why this type exists
 *
 * The eager [ScriptedScope.sh] surface reaches durable execution through the
 * [ScriptedOperationRuntime] seam. That seam HAD a second implementation —
 * the `JournaledScriptedOperationRuntime` of old, deleted in S4-F1-B — which
 * journaled a hardcoded `scripted.core.sh` namespace and then called
 * `ShExecution.invokeShell` directly. It never consulted `StepRegistry`, so it
 * never ran `RegistryExecutionPreparation`, and therefore never admitted the
 * `SHELL_OPERATIONS_CAPABILITY` that `core.sh` declares. A declarative `sh` in
 * the same product does check it. That asymmetry was a security-boundary defect,
 * and the second implementation is now gone rather than merely unreachable.
 *
 * ## Why it is not reachable today, and why that is not a reason to keep it
 *
 * S4-A1 measured the scripted `sh` chain end to end and found the privileged
 * path BUILT but UNREACHABLE: the lowering does not rewrite `ScriptedCallKind.Shell`,
 * so the bare `sh(...)` survives into the generated Kotlin with no receiver and
 * the host rejects the script at compile time. So the bypass is latent, not
 * live.
 *
 * That is worse than a live bug in one specific way: it is one small fix away
 * from going live. Give the compiled path a working call-site provider and the
 * surviving bare `sh` binds to [ScriptedScope.sh], which routes straight into
 * whatever [ScriptedOperationRuntime] is wired here. If that is the privileged
 * runtime, a compile error has been silently converted into an unadmitted
 * subprocess. Removing the privileged implementation is what makes fixing the
 * lowering safe.
 *
 * ## What this type is
 *
 * The whole remaining implementation of that seam: it names `core.sh` and
 * nothing else, and delegates identity, journaling, capability admission and
 * output decoding to [ScriptedRegistryInvoker.invokeTyped]. There is no second
 * codec, no second journal writer, and no path to the shell that does not
 * declare and admit its capability.
 */
internal class RegistryScriptedShellRuntime(
    private val invoker: ScriptedRegistryInvoker,
) : ScriptedOperationRuntime {

    /**
     * S4-D2: the decoded `CoreShellOutput` carries BOTH the value the program receives and the
     * canonical outcome. Narrowing to `.result` alone is what discarded the outcome and let an
     * `Unstable` scripted shell report `Success`; both halves now cross the port.
     *
     * No classifier is reimplemented here: `typed.outcome` is `outcomeOf(CoreShellOutput)`, the
     * same projection the canonical boundary applied when the handler ran.
     */
    override suspend fun invoke(operation: ScriptedOperation): ScriptedOperationResult {
        val typed = invoker.invokeTyped(
            identity = ScriptedScopeIdentity(
                runId = operation.runId,
                entryPointId = operation.entryPointId,
                dynamicScopePath = operation.dynamicScopePath,
                definitionDigest = operation.definitionDigest,
            ),
            callSiteId = operation.callSiteId,
            invocationOrdinal = operation.invocationOrdinal,
            definition = CoreShellStep.definition,
            input = CoreShellInput(command = operation.command),
        )
        return ScriptedOperationResult(value = typed.value.result, outcome = typed.outcome)
    }
}
