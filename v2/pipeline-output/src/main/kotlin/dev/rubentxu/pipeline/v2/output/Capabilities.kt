package dev.rubentxu.pipeline.v2.output

/**
 * M3 — capability IDs for the published Output Plane.
 *
 * Consumers negotiate by inclusion of these strings in the artifact's
 * metadata (mirrors the M2 `pipeline-runtime/Capabilities.kt` convention
 * and the M1 module convention). Absence of a capability requires
 * fallback or refusal at the consumer side (contract §6).
 *
 * The IDs are static strings; bumping the version suffix is a contract
 * change, not the implementation detail it appears to be.
 */
object Capabilities {
    /**
     * M3 — `OutputReadPort.readRangeDigested` is available; the read
     * path returns a deterministic content hash on the page.
     */
    const val OUTPUT_READ_DIGESTED_V1: String = "output.read.digested.v1"

    /**
     * M3 — `OutputPinPort.pin / release / pinsOf / isPinned` are
     * available; a consumer can hold bytes against retention.
     */
    const val OUTPUT_PIN_V1: String = "output.pin.v1"

    /**
     * M3 — the new `OutputRefusal` cases (`RetentionGap`, `Corrupt`,
     * `Unavailable`, `RangeLostRetention`) and the new `PruneAuthorisation`
     * surface are recognised by the consumer.
     */
    const val OUTPUT_REFUSAL_RETENTION_V1: String = "output.refusal.retention.v1"
}