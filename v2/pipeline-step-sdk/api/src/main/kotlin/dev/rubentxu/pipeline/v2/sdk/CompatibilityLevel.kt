package dev.rubentxu.pipeline.v2.sdk

/**
 * Jenkins familiarity compatibility levels (F0..F3) per
 * docs/v2/01-product/JENKINS_FAMILIARITY.md L7-21 and ADR-0005.md:13.
 *
 * S6/H: the `@JenkinsSurface` annotation that carried this as a parameter is gone, together with
 * the KSP that read it. The type survives because it is a Jenkins compatibility LEVEL — a fact
 * about the ecosystem rather than about any one Step — and it is pinned by
 * `CompatibilityLevelEnumTest`.
 * The `level` accessor returns the canonical F-number (0..3).
 */
enum class CompatibilityLevel(val level: Int) {
    NAMING(0),       // F0 — same name + general concept
    SURFACE(1),      // F1 — name + main parameters equivalent
    BEHAVIORAL(2),   // F2 — observable semantics compatible for documented cases
    MIGRATION(3),    // F3 — migrator can convert Jenkins usage automatically
}
