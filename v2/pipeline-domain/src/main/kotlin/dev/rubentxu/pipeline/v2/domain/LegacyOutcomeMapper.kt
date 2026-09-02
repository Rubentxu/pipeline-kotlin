package dev.rubentxu.pipeline.v2.domain

/**
 * Stable mapping from the legacy run-outcome strings to the canonical
 * typed [RunOutcome] (LF-0104).
 *
 * The durable walker currently returns raw strings (`"success"` /
 * `"unstable"` / `"failure"`) at its boundary. While that walker exists,
 * every crossing from the legacy boundary into typed territory MUST go
 * through this mapper — this is the mechanical definition of the M2-005
 * acceptance criterion ("failure mapping estable"): exactly one place
 * where legacy strings become typed outcomes, deterministic, and
 * fail-closed on unknown tokens.
 *
 * ## Mapping table
 *
 * | Legacy token   | Canonical outcome |
 * |----------------|-------------------|
 * | `"success"`    | [RunOutcome.Success] |
 * | `"completed"`  | [RunOutcome.Success] (historical synonym) |
 * | `"unstable"`   | [RunOutcome.Unstable] |
 * | `"failure"`    | [RunOutcome.Failure] with a [FailureKind.UNKNOWN] [PipelineFailure] — the string boundary carries no richer detail |
 * | `"failed"`     | [RunOutcome.Failure] (historical synonym) |
 * | `"aborted"`    | [RunOutcome.Aborted] |
 * | anything else  | `IllegalArgumentException` (fail-closed; an unknown token is a contract violation, never silently a success) |
 *
 * The mapper is pure and deterministic. It accepts only the exact closed
 * set above — no trimming, no case folding: legacy producers emit fixed
 * constants, and a silently-tolerant mapper would hide producer drift.
 */
object LegacyOutcomeMapper {

    private val FAILURE = PipelineFailure(
        kind = FailureKind.UNKNOWN,
        message = "legacy run outcome 'failure' — no richer failure detail is available at the string boundary",
    )

    fun toRunOutcome(legacy: String): RunOutcome = when (legacy) {
        "success", "completed" -> RunOutcome.Success
        "unstable" -> RunOutcome.Unstable
        "failure", "failed" -> RunOutcome.Failure(FAILURE)
        "aborted" -> RunOutcome.Aborted
        else -> throw IllegalArgumentException(
            "Unknown legacy run outcome token; expected one of " +
                "[success, completed, unstable, failure, failed, aborted] but got a different value"
        )
    }
}
