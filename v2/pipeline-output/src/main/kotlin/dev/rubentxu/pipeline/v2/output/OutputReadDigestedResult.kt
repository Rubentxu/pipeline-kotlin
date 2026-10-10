package dev.rubentxu.pipeline.v2.output

/**
 * M3 — the result of [OutputReadPort.readRangeDigested].
 *
 * Closed ADT: a read either returns a digested page OR a typed refusal.
 * The refusal case reuses [OutputRefusal] (no new parallel hierarchy).
 *
 * The two-case envelope mirrors the M1 [OutputReadResult] shape: a
 * closed sealed interface with a page case and a refusal case. The
 * `Digested` case is additive on top of the existing `OutputReadResult.Page`
 * — it carries the same [OutputPage] AND the digest of its bytes.
 */
sealed interface OutputReadDigestedResult {

    /**
     * The bytes were read; [digest] is the SHA-256 (or whatever
     * [OutputDigest.DEFAULT_ALGORITHM] names) of the committed bytes
     * in `[from, from + page.bytes.size)`.
     */
    data class Digested(
        val page: OutputPage,
        val digest: OutputDigest,
    ) : OutputReadDigestedResult

    /**
     * The read could not be served; [reason] is a closed [OutputRefusal]
     * case (the cases from §4 — `RetentionGap`, `Corrupt`, `Unavailable`,
     * `RangeLostRetention` — are the M3 additions).
     */
    data class Refused(val reason: OutputRefusal) : OutputReadDigestedResult
}