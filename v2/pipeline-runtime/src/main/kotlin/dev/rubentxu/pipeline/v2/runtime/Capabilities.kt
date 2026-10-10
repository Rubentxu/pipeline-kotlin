package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.Capability

/**
 * Static capability identifiers published by PipelineK.
 *
 * ## Reconciliation (v0.51.0-rc1)
 *
 * The M3 capability IDs (`output.read.digested.v1`, `output.pin.v1`,
 * `output.refusal.retention.v1`) previously lived in a sibling
 * `Capabilities` object in `:pipeline-output` (introduced by the M3 sub-block
 * in Wave 1 of M3-Impl). This release consolidates them with the existing
 * five (M1 + M2) into the single canonical `:pipeline-runtime/Capabilities`
 * companion so the contract-test enumeration reads from one place. The
 * `:pipeline-output/Capabilities.kt` object is removed in the same commit;
 * the IDs and their semantics are unchanged.
 *
 * ## Negotiation discipline (cross-repo contract §6)
 *
 * Consumers negotiate by inclusion of these strings in the artifact's
 * `WorkerHello` / manifest metadata. Absence of a capability requires
 * fallback or refusal at the consumer side — never a false LIVE.
 *
 * ## Status table (this release)
 *
 * The eight capabilities and their publication state in `v0.51.0-rc1`:
 *
 * | Capability ID                 | Status in v0.51.0-rc1                            | First published     |
 * |-------------------------------|--------------------------------------------------|---------------------|
 * | `output.follow.v1`            | **PUBLICADA** (carry-forward from `v0.49.0-rc1`)  | `v0.49.0-rc1`       |
 * | `events.follow.v1`            | **PUBLICADA** (carry-forward from `v0.49.0-rc1`)  | `v0.49.0-rc1`       |
 * | `runtime.inspect.v1`          | **EXPERIMENTAL** (carry-forward from `v0.50.0`)  | `v0.50.0-rc1`       |
 * | `runtime.cancel.v1`           | **EXPERIMENTAL** (carry-forward from `v0.50.0`)  | `v0.50.0-rc1`       |
 * | `runtime.recover.v1`          | **EXPERIMENTAL** (carry-forward from `v0.50.0`)  | `v0.50.0-rc1`       |
 * | `output.read.digested.v1`     | **EXPERIMENTAL** (new in this release)           | `v0.51.0-rc1`       |
 * | `output.pin.v1`               | **EXPERIMENTAL** (new in this release)           | `v0.51.0-rc1`       |
 * | `output.refusal.retention.v1` | **EXPERIMENTAL** (new in this release)           | `v0.51.0-rc1`       |
 *
 * EXPERIMENTAL → PUBLICADA happens after the candidate is CERTIFIED by
 * `pipelinek-release-harness` (per cross-repo contract §6 and the M1/M2
 * release-receipt precedent); until then, adopting consumers must treat
 * them as negociables but not yet stable. Prior releases remain under
 * "ausencia => fallback o refusal" unchanged.
 */
object Capabilities {
    // CRIC-M1 — PUBLICADA in v0.49.0-rc1; carried forward as PUBLICADA in v0.50.0-rc1 and v0.51.0-rc1.
    val OUTPUT_FOLLOW_V1: Capability = Capability("output.follow.v1")
    val EVENTS_FOLLOW_V1: Capability = Capability("events.follow.v1")

    // CRIC-M2 — EXPERIMENTAL since v0.50.0-rc1; promoted to PUBLICADA after the candidate is CERTIFIED.
    val RUNTIME_INSPECT_V1: Capability = Capability("runtime.inspect.v1")
    val RUNTIME_CANCEL_V1: Capability = Capability("runtime.cancel.v1")
    val RUNTIME_RECOVER_V1: Capability = Capability("runtime.recover.v1")

    // CRIC-M3 — EXPERIMENTAL since v0.51.0-rc1 (this release); promoted to PUBLICADA after the candidate is CERTIFIED.
    val OUTPUT_READ_DIGESTED_V1: Capability = Capability("output.read.digested.v1")
    val OUTPUT_PIN_V1: Capability = Capability("output.pin.v1")
    val OUTPUT_REFUSAL_RETENTION_V1: Capability = Capability("output.refusal.retention.v1")
}
