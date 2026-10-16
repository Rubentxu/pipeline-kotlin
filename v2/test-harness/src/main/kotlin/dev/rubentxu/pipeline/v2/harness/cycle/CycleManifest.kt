package dev.rubentxu.pipeline.v2.harness.cycle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A pure projection of a [CycleState] and the [CycleEvent]s that produced
 * it. The manifest is what `retirement_guard.py` consumes.
 *
 * The manifest has no I/O. The caller composes the events, the decider
 * produces the state, the manifest records the result.
 *
 * BQ4 audit (2026-10-16, vertical): the spec at
 * `docs/v2/03-specifications/EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md` lists
 * `cycleId`, `sourceSHA`, `tasks`, `probes`, `contracts`, `expectedHF`,
 * `replacement` and isolation policy as required manifest fields. The
 * previous shape (RC3 close-out) carried only `cycleId`, `sourceSha`,
 * `probes` and `state`; the new fields are added here so the runner can
 * refuse unsupported fidelities, validate path containment, and produce
 * a manifest that round-trips with `retirement_guard.py`.
 *
 * The wire format uses snake_case keys (the `retirement_guard.py`
 * consumer expects exactly `cycle_id`, `source_sha`, `expected_hf`,
 * `probes[*].relative_path`, `probes[*].content_sha256`, ...). Kotlin
 * field names stay camelCase; the `@SerialName` annotations on every
 * property enforce the round-trip.
 */
@Serializable
data class CycleManifest(
    @SerialName("cycle_id") val cycleId: String,
    @SerialName("source_sha") val sourceSha: String,
    @SerialName("probes") val probes: List<ProbeEntry> = emptyList(),
    @SerialName("contracts") val contracts: List<ContractRef> = emptyList(),
    @SerialName("expected_hf") val expectedHF: String,
    @SerialName("replacement") val replacement: ReplacementPlan,
    @SerialName("isolation_policy") val isolationPolicy: String = "cycleDirOnly",
) {
    init {
        require(cycleId.isNotBlank()) { "cycleId must not be blank" }
        require(sourceSha.length == 40) {
            "sourceSha must be a 40-char SHA-1 hex; got ${sourceSha.length} chars"
        }
        require(expectedHF in ALLOWED_FIDELITIES) {
            "expectedHF must be one of $ALLOWED_FIDELITIES; got '$expectedHF'"
        }
        require(isolationPolicy == "cycleDirOnly") {
            "the only supported isolationPolicy is 'cycleDirOnly'; got '$isolationPolicy'"
        }
        val probeIds = probes.map { it.id }
        require(probeIds.toSet().size == probeIds.size) {
            "probe ids must be unique; duplicates: $probeIds"
        }
        val contractIds = contracts.map { it.id }
        require(contractIds.toSet().size == contractIds.size) {
            "contract ids must be unique; duplicates: $contractIds"
        }
    }

    companion object {
        /**
         * The fidelities this vertical supports. HF2..HF6 are explicitly
         * refused by the runner before any I/O; the
         * [dev.rubentxu.pipeline.v2.harness.cycle.ProbeExecutor] returns
         * `UnsupportedFidelity` for any `expectedHF` not in this set.
         */
        val ALLOWED_FIDELITIES: Set<String> = setOf("HF0", "HF1")
    }
}

/**
 * One contract the cycle depends on. The runner consults this to
 * decide which probes can run at the requested fidelity.
 */
@Serializable
data class ContractRef(
    @SerialName("id") val id: String,
    @SerialName("capability") val capability: String,
    @SerialName("module") val module: String,
) {
    init {
        require(id.isNotBlank()) { "ContractRef.id must not be blank" }
        require(capability.isNotBlank()) { "ContractRef.capability must not be blank" }
        require(module.isNotBlank()) { "ContractRef.module must not be blank" }
    }
}

/**
 * The substitute guarantee left behind when the cycle retires. Either
 * a permanent contract (in-repo test/fitness/property) or an external
 * certifier (the harness) that will produce a `proofDigest` later.
 */
@Serializable
data class ReplacementPlan(
    @SerialName("kind") val kind: ReplacementKind,
    @SerialName("id") val id: String,
    @SerialName("evidence") val evidence: String,
) {
    init {
        require(id.isNotBlank()) { "ReplacementPlan.id must not be blank" }
        require(evidence.isNotBlank()) { "ReplacementPlan.evidence must not be blank" }
    }

    @Serializable
    enum class ReplacementKind {
        @SerialName("PERMANENT_CONTRACT") PERMANENT_CONTRACT,
        @SerialName("EXTERNAL_CERTIFIER") EXTERNAL_CERTIFIER,
    }
}

@Serializable
data class ProbeEntry(
    @SerialName("id") val id: String,
    @SerialName("contract_id") val contractId: String,
    @SerialName("relative_path") val relativePath: String,
    @SerialName("kind") val kind: Kind,
    @SerialName("executed") val executed: Int,
    @SerialName("skipped") val skipped: Int,
    @SerialName("verdict") val verdict: Verdict,
    @SerialName("mutation_or_negative") val mutationOrNegative: String,
    @SerialName("replacement_kind") val replacementKind: ReplacementKind,
    @SerialName("replacement_id") val replacementId: String,
    @SerialName("replacement_evidence") val replacementEvidence: String,
    @SerialName("content_sha256") val contentSha256: String,
) {
    init {
        require(id.isNotBlank()) { "ProbeEntry.id must not be blank" }
        require(contractId.isNotBlank()) { "ProbeEntry.contractId must not be blank" }
        require(relativePath.isNotBlank()) { "ProbeEntry.relativePath must not be blank" }
        require(!relativePath.startsWith("/")) {
            "ProbeEntry.relativePath must be relative, not absolute: '$relativePath'"
        }
        require(".." !in relativePath.split('/')) {
            "ProbeEntry.relativePath must not contain '..' segments: '$relativePath'"
        }
        require(executed >= 0) { "executed must be non-negative" }
        require(skipped in 0..executed) { "skipped must be 0..executed" }
        require(mutationOrNegative.isNotBlank()) { "mutationOrNegative must not be blank" }
        require(replacementId.isNotBlank()) { "replacementId must not be blank" }
        require(replacementEvidence.isNotBlank()) { "replacementEvidence must not be blank" }
        require(contentSha256.length == 64) {
            "contentSha256 must be a 64-char hex; got ${contentSha256.length} chars"
        }
        require(contentSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "contentSha256 must be lowercase hex; got '$contentSha256'"
        }
    }

    @Serializable
    enum class Kind {
        @SerialName("UAT") UAT,
        @SerialName("E2E") E2E,
        @SerialName("AAT") AAT,
    }

    @Serializable
    enum class Verdict {
        @SerialName("PASS") PASS,
        @SerialName("FAIL") FAIL,
    }

    @Serializable
    enum class ReplacementKind {
        @SerialName("PERMANENT_CONTRACT") PERMANENT_CONTRACT,
        @SerialName("EXTERNAL_CERTIFIER") EXTERNAL_CERTIFIER,
    }
}
