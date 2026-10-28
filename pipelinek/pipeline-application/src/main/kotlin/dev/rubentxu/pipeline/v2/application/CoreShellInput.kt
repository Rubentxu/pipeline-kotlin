package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ShellCommand

/**
 * Typed input for `core.sh`, registry-routed.
 *
 * This is the ONE interior the `core.sh` wire format normalizes to:
 * [CoreShellWireCodec] reads every shipped spelling into it, and its writers
 * reproduce the bytes of the dialect they belong to.
 *
 * The durable subset of shell arguments is `{ script, encoding?, label?, returnMode }`.
 * Per-call runtime args (`shOptions`, `opId`, `stageIndex`, `stepIndex`) are filled
 * from capability-resolved seams at execute-time; they MUST NOT be encoded into the
 * durable payload (no secrets in `OperationInput`).
 *
 * [isScriptBlock] is the compile-time artifact the declarative dialect declares: it
 * records whether the compiled command was a script block. The runtime handler does
 * NOT read it — it is kept because the reader must not drop a field the format
 * declares, and the writer must be able to reproduce the bytes it read.
 *
 * The handler body is a deterministic stub
 * returning `ShellInvocationResult.UnitValue`. The real sh execution path
 * Is wired in REGISTRY_PRIMARY by injecting the `SHELL_OPERATIONS`
 * capability adapter.
*/
data class CoreShellInput(
    val command: ShellCommand,
    val stepIndex: Int = 0,
    val isScriptBlock: Boolean = false,
)
