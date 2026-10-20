package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.Serializable

/**
 * P0.1 — a product version is a final SemVer triple. It must NOT carry a
 * candidate suffix: under the release-evolution contract
 * (`docs/pipelinek-release-evolution/shared/01-cross-repo-contract.md` §4, §11)
 * candidate state lives *outside* the binary identity. `0.44.0-rc1` is
 * therefore not a valid [ProductVersion]; the candidate is
 * `(ProductVersion=0.44.0, CandidateId=sha256:…)`.
 *
 * The v0.43.0 incident is the reason this type exists. That artifact was
 * internally consistent at `0.43.0-rc1` and was later presented under a GA
 * identity; a gate that only compared the build's own surfaces would have been
 * green on exactly the bytes that caused the incident. Rejecting a
 * candidate-suffixed *product* identity is what removes the second identity
 * there was to launder.
 *
 * A value class rather than a bare [String] so an invalid version cannot be
 * constructed at all: [parseOrNull] is the only way in, and it returns null for
 * a candidate suffix.
 */
@Serializable
@JvmInline
value class ProductVersion private constructor(val value: String) {

    val major: Int get() = value.substringBefore('.').toInt()
    val minor: Int get() = value.substringAfter('.').substringBefore('.').toInt()
    val patch: Int get() = value.substringAfterLast('.').toInt()

    override fun toString(): String = value

    companion object {
        private val FINAL_SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

        /** Placeholder used only when there is no usable expected version. */
        val UNKNOWN: ProductVersion = ProductVersion("0.0.0")

        /**
         * Parse a final SemVer product version, or null when the input is a
         * candidate suffix. Returning null rather than throwing keeps this
         * usable as a predicate; callers that require a version use
         * [parseOrThrow].
         */
        fun parseOrNull(raw: String): ProductVersion? {
            val trimmed = raw.trim()
            if (!FINAL_SEMVER.matches(trimmed)) return null
            return ProductVersion(trimmed)
        }

        fun parseOrThrow(raw: String): ProductVersion = parseOrNull(raw)
            ?: throw IllegalArgumentException(
                "ProductVersion must be final SemVer MAJOR.MINOR.PATCH without a candidate " +
                    "suffix, got '$raw'. Candidate state belongs to the candidate descriptor, " +
                    "not to the binary identity (cross-repo contract v2 §4, §11).",
            )
    }
}
