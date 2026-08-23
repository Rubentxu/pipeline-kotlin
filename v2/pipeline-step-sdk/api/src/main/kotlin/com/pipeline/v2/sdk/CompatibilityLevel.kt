package com.pipeline.v2.sdk

/**
 * Jenkins familiarity compatibility levels (F0..F3) per
 * docs/v2/01-product/JENKINS_FAMILIARITY.md L7-21 and ADR-0005.md:13.
 *
 * Used by `@JenkinsSurface(compatibility = ...)` annotation parameter.
 * The `level` accessor returns the canonical F-number (0..3).
 */
enum class CompatibilityLevel(val level: Int) {
    NAMING(0),       // F0 — same name + general concept
    SURFACE(1),      // F1 — name + main parameters equivalent
    BEHAVIORAL(2),   // F2 — observable semantics compatible for documented cases
    MIGRATION(3),    // F3 — migrator can convert Jenkins usage automatically
}
