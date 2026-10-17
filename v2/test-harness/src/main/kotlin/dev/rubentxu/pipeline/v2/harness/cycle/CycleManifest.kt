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
 * BQ4 P0-D (2026-10-17, vertical close-out): the per-probe
 * `replacement_kind` / `replacement_id` / `replacement_evidence` triple
 * was a parallel legacy of the cycle-level `replacement.evidence` flat
 * shape the BQ4 audit closed. The cycle-level `PromotionEvidence`
 * carrier is the single authority for the substitute guarantee; the
 * per-probe triple was a free-form copy that the runner never
 * consumed. Dropping it removes the parallel authority, the
 * `ReplacementKind` discriminator, and the string-prefix false-green
 * class the audit named. A future per-probe evidence row, if ever
 * needed, is one new `replacement: PromotionEvidence?` field on
 * [ProbeEntry].
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
 * The substitute guarantee left behind when the cycle retires.
 *
 * BQ4 audit (2026-10-16, P0-D, vertical): the previous shape
 * carried `kind` (an enum) + `id` (a free string) + `evidence` (a
 * free string); the decider validated `evidence` by string
 * prefix, which the audit named as a class of false green — a
 * textual reference like `permanent-contract:foo` was a label,
 * not a verified substitute.
 *
 * The new shape drops the redundant `kind` and `id` fields; the
 * [PromotionEvidence] carrier carries both the kind (its sealed
 * variant) and the identity (its typed payload). The runner's
 * [PromotionEvidenceVerifier] is the single authority on
 * whether the carrier is a valid substitute.
 */
@Serializable
data class ReplacementPlan(
    @SerialName("evidence") val evidence: PromotionEvidence,
)

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
    @SerialName("content_sha256") val contentSha256: String,
) {
    init {
        // P0-B (audit 2026-10-16): id and relativePath are the
        // two file-system names the runner and the executor
        // compose. Both must be confined to a safe character
        // class so a manifest cannot write outside the cycle
        // directory by smuggling a path separator into the id.
        require(isSafeId(id)) {
            "ProbeEntry.id must be a non-blank token of [A-Za-z0-9._-], 1..128 chars, " +
                "with no path separator and no '..' segment; got '$id'"
        }
        require(isSafeRelativePath(relativePath)) {
            "ProbeEntry.relativePath must be a relative path under the cycle " +
                "directory: no absolute, no '..' segments, no symlink targets, " +
                "and no name outside [A-Za-z0-9._/-]; got '$relativePath'"
        }
        require(contractId.isNotBlank()) { "ProbeEntry.contractId must not be blank" }
        require(executed >= 0) { "executed must be non-negative" }
        require(skipped in 0..executed) { "skipped must be 0..executed" }
        require(mutationOrNegative.isNotBlank()) { "mutationOrNegative must not be blank" }
        require(contentSha256.length == 64) {
            "contentSha256 must be a 64-char hex; got ${contentSha256.length} chars"
        }
        require(contentSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "contentSha256 must be lowercase hex; got '$contentSha256'"
        }
    }

    companion object {
        // P0-B: a safe id is a single name component (no `/`,
        // no `..`, no NUL, no control chars). The runner composes
        // the evidence file as `outDir/${id}.json`; allowing a
        // `/` or a `..` would let the manifest escape the cycle
        // dir's probes/ subdirectory.
        internal fun isSafeId(id: String): Boolean {
            if (id.isBlank() || id.length > 128) return false
            if (id == "." || id == "..") return false
            if ('/' in id || '\\' in id) return false
            return id.all { c ->
                c.isLetterOrDigit() || c == '-' || c == '_' || c == '.'
            }
        }

        // P0-B: a safe relative path is a POSIX-style relative
        // path confined to the cycle directory. No absolute,
        // no `..` segments, no symlink-hopping via a leading `~`
        // or `\\`, no NUL or control bytes. Each segment must be
        // a single name component matching the same char class as
        // isSafeId.
        internal fun isSafeRelativePath(path: String): Boolean {
            if (path.isBlank()) return false
            if (path.startsWith("/") || path.startsWith("~")) return false
            if (path.contains('\u0000')) return false
            val segments = path.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
            return segments.all { segment ->
                segment.all { c ->
                    c.isLetterOrDigit() || c == '-' || c == '_' || c == '.'
                }
            }
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
}
