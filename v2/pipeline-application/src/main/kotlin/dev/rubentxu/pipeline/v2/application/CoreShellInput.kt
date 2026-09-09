package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ShellCommand
import dev.rubentxu.pipeline.v2.domain.ShellReturnMode

/**
 * Typed input for `core.sh`, registry-routed (LB-02 / G1).
 *
 * The Input codec encodes ONLY the durable subset of shell arguments:
 *   `{ script, encoding?, label?, returnMode }`. Per-call runtime args
 *   (`shOptions`, `opId`, `stageIndex`, `stepIndex`) are filled from
 *   capability-resolved seams at execute-time; they MUST NOT be encoded
 *   into the durable payload (no secrets in `OperationInput`).
 *
 * G1 (registry seam proof): the handler body is a deterministic stub
 * returning `ShellInvocationResult.UnitValue`. The real sh execution path
 * is wired in G3 REGISTRY_PRIMARY by injecting the `SHELL_OPERATIONS`
 * capability adapter.
 *
 * @see docs/v2/00-context/LB02_CORE_SH_CONTRACT_DRAFT.md
 */
data class CoreShellInput(
    val command: ShellCommand,
    val stepIndex: Int = 0,
)
