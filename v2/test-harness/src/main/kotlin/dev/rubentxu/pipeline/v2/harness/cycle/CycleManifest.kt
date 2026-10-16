package dev.rubentxu.pipeline.v2.harness.cycle

/**
 * A pure projection of a [CycleState] and the [CycleEvent]s that produced
 * it. The manifest is what `retirement_guard.py` consumes.
 *
 * The manifest has no I/O. The caller composes the events, the decider
 * produces the state, the manifest records the result.
 */
data class CycleManifest(
    val cycleId: String,
    val sourceSha: String,
    val probes: List<ProbeEntry>,
    val state: CycleState,
) {
    init {
        require(cycleId.isNotBlank()) { "cycleId must not be blank" }
        require(sourceSha.length == 40) { "sourceSha must be a 40-char SHA-1 hex" }
    }
}

data class ProbeEntry(
    val id: String,
    val contractId: String,
    val relativePath: String,
    val kind: Kind,
    val executed: Int,
    val skipped: Int,
    val verdict: Verdict,
    val mutationOrNegative: String,
    val replacementKind: ReplacementKind,
    val replacementId: String,
    val replacementEvidence: String,
    val contentSha256: String,
) {
    init {
        require(id.isNotBlank()) { "ProbeEntry.id must not be blank" }
        require(contractId.isNotBlank()) { "ProbeEntry.contractId must not be blank" }
        require(relativePath.isNotBlank()) { "ProbeEntry.relativePath must not be blank" }
        require(executed >= 0) { "executed must be non-negative" }
        require(skipped in 0..executed) { "skipped must be 0..executed" }
        require(mutationOrNegative.isNotBlank()) { "mutationOrNegative must not be blank" }
        require(replacementId.isNotBlank()) { "replacementId must not be blank" }
        require(replacementEvidence.isNotBlank()) { "replacementEvidence must not be blank" }
        require(contentSha256.length == 64) { "contentSha256 must be a SHA-256 hex digest" }
    }

    enum class Kind { UAT, E2E, AAT }
    enum class Verdict { PASS, FAIL }
    enum class ReplacementKind { PERMANENT_CONTRACT, EXTERNAL_CERTIFIER }
}
