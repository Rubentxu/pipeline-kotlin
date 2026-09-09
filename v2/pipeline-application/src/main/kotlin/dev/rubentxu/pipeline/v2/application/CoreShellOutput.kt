package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult

/**
 * Typed output for `core.sh`, registry-routed (LB-02 / G1).
 *
 * The Output codec encodes:
 *   - the [result] (closed ADT: UnitValue | Stdout | Status | Failed | Interrupted)
 *   - captured stdout (for journal-level replay observation)
 *   - durationMs
 *
 * G1 (registry seam proof): the encoded wire shape reuses the existing
 * scripted-runtime JsonObjectBuilder so G2 corpus migration is mechanically
 * equivalent to rewriting a legacy test from `LegacyCore` to `Registry`
 * with byte-identical fingerprints.
 *
 * @see docs/v2/00-context/LB02_CORE_SH_CONTRACT_DRAFT.md
 */
data class CoreShellOutput(
    val result: ShellInvocationResult,
    val capturedStdout: String,
    val durationMs: Long,
)
