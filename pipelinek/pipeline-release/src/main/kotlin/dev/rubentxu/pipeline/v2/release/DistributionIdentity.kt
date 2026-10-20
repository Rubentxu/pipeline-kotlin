package dev.rubentxu.pipeline.v2.release

/**
 * P0.1 / P0.3 — exact product identity, as a pure decision.
 *
 * The release-evolution contract (`docs/pipelinek-release-evolution/shared/
 * 01-cross-repo-contract.md` §11) is a single equality:
 *
 * ```text
 * ProductVersion == AssetVersion == ArchiveRootVersion
 *                 == ImplementationVersion == RuntimeVersion == ManifestVersion
 * ```
 *
 * A mismatch is a build defect that blocks candidate handoff. The v0.43.0
 * incident is exactly this failure: the asset was advertised with a GA identity
 * while the bytes carried `rc1` inside.
 *
 * This is deliberately a **pure** function. It takes facts and returns a
 * decision. It does not open a ZIP, shell out, or read a JAR manifest, so it
 * is testable without a build and without a filesystem. The effectful
 * collector lives in [DistributionIdentityProbe]; the interpreter at the
 * boundary lives in the build task.
 *
 * The shape follows the AGENTS.md rule that a decision must be produced
 * before it is interpreted, and that a closed result type should express the
 * cases rather than a boolean plus a nullable string.
 */
sealed interface DistributionIdentityVerdict {

    /** Every observed surface reports the same version. */
    data class Consistent(val version: ProductVersion) : DistributionIdentityVerdict

    /**
     * At least two surfaces disagree. [conflicts] lists every offending
     * surface with the version it actually reported, so the diagnostic names
     * the mismatch instead of just asserting that one exists.
     */
    data class Divergent(
        val expected: ProductVersion,
        val conflicts: List<SurfaceConflict>,
    ) : DistributionIdentityVerdict

    /** A required surface was not observable at all. Distinct from Divergent. */
    data class Incomplete(
        val expected: ProductVersion,
        val missing: List<IdentitySurface>,
    ) : DistributionIdentityVerdict
}

data class SurfaceConflict(val surface: IdentitySurface, val reported: String)

/** The six surfaces that must agree. */
enum class IdentitySurface {
    /** The version the build declares as product identity. */
    PRODUCT_VERSION,

    /** Version encoded in the distribution asset filename. */
    ASSET,

    /** Version encoded in the archive root directory inside the ZIP. */
    ARCHIVE_ROOT,

    /** `Implementation-Version` in the primary application JAR manifest. */
    IMPLEMENTATION_VERSION,

    /** What the installed binary reports from `pipelinek version`. */
    RUNTIME_VERSION,

    /** `version` field of the distribution manifest. */
    MANIFEST_VERSION,
}

/** One observed fact: a surface and the version string it actually reported. */
data class IdentityObservation(val surface: IdentitySurface, val reported: String?)

/**
 * The observed facts of a built candidate.
 *
 * A [reported] of null means the surface could not be observed. That is
 * modelled as a missing value rather than a sentinel string so that
 * "reported the wrong thing" and "reported nothing" stay distinguishable: the
 * former is a divergence defect, the latter an incomplete build. Conflating
 * them would let a missing manifest read as a pass.
 */
data class DistributionIdentityFacts(
    val observations: List<IdentityObservation>,
) {
    fun reported(surface: IdentitySurface): String? =
        observations.firstOrNull { it.surface == surface }?.reported
}

/**
 * Decide identity consistency. Pure: no I/O, no clock, no environment.
 *
 * The expected version is derived from [IdentitySurface.PRODUCT_VERSION], the
 * build's own declaration, because that is the only surface the producer
 * controls directly. Everything else is derived from it and must match.
 */
fun evaluateDistributionIdentity(
    facts: DistributionIdentityFacts,
): DistributionIdentityVerdict {
    val expectedRaw = facts.reported(IdentitySurface.PRODUCT_VERSION)
        ?: return DistributionIdentityVerdict.Incomplete(
            expected = ProductVersion.UNKNOWN,
            missing = listOf(IdentitySurface.PRODUCT_VERSION),
        )
    val expected = ProductVersion.parseOrNull(expectedRaw)
        ?: return DistributionIdentityVerdict.Divergent(
            expected = ProductVersion.UNKNOWN,
            conflicts = listOf(
                SurfaceConflict(
                    IdentitySurface.PRODUCT_VERSION,
                    "$expectedRaw (not a final SemVer product version)",
                ),
            ),
        )

    val derived = IdentitySurface.entries.filter { it != IdentitySurface.PRODUCT_VERSION }
    val missing = derived.filter { facts.reported(it) == null }
    if (missing.isNotEmpty()) {
        return DistributionIdentityVerdict.Incomplete(expected, missing)
    }

    val conflicts = derived
        .map { it to facts.reported(it)!! }
        .filter { (_, reported) -> reported != expected.value }
        .map { (surface, reported) -> SurfaceConflict(surface, reported) }

    return if (conflicts.isEmpty()) {
        DistributionIdentityVerdict.Consistent(expected)
    } else {
        DistributionIdentityVerdict.Divergent(expected, conflicts)
    }
}

/**
 * Render a verdict as the operator-facing message. Kept separate from the
 * decision so the decision stays pure and testable, and so the wording lives
 * in one place instead of being re-invented at each call site.
 */
fun DistributionIdentityVerdict.render(): String = when (this) {
    is DistributionIdentityVerdict.Consistent ->
        "identity consistent: $version"

    is DistributionIdentityVerdict.Incomplete ->
        "identity INCOMPLETE: expected $expected but these surfaces were not observable: " +
            missing.joinToString { it.name }

    is DistributionIdentityVerdict.Divergent -> buildString {
        appendLine("identity DIVERGENT: expected $expected")
        conflicts.forEach { appendLine("  - ${it.surface.name} reported '${it.reported}'") }
        append(
            "A candidate whose surfaces disagree is a build defect and MUST NOT be handed " +
                "to the harness (cross-repo contract v2 §11).",
        )
    }
}
