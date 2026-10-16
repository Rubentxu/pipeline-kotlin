package dev.rubentxu.pipeline.v2.harness.provenance

/**
 * A pinned commit reference. Full SHA, lowercase, 40 hex chars. The decider
 * never accepts short SHAs: a short SHA is not a stable identity.
 */
@JvmInline
value class CommitRef(val value: String) {
    init {
        require(SHA.matches(value)) {
            "CommitRef must be a 40-char lowercase hex SHA, got '${value.take(8)}…'"
        }
    }

    override fun toString(): String = value

    companion object {
        private val SHA = Regex("^[0-9a-f]{40}$")
    }
}
