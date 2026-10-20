package dev.rubentxu.pipeline.v2.sdk

/**
 * Jenkins familiarity compatibility levels (F0..F3).
 * The `@JenkinsSurface` annotation that carried this as a parameter is gone, together with
 * the KSP that read it. The type survives because it is a Jenkins compatibility LEVEL — a fact
 * about the ecosystem rather than about any one Step.
 * The `level` accessor returns the canonical F-number (0..3), and those four numbers are
 * externally visible ecosystem facts: renumbering them renames the level for every consumer.
 * This KDoc used to say the type "is pinned by `CompatibilityLevelEnumTest`". Nothing in the
 * tree has ever been named that, and the enum has no caller outside this file — it is a
 * declaration waiting for its consumer, not a pinned contract. A test here would have no
 * consumer to protect, so the numbers are stated instead of defended.
*/
enum class CompatibilityLevel(val level: Int) {
    NAMING(0),       // F0 — same name + general concept
    SURFACE(1),      // F1 — name + main parameters equivalent
    BEHAVIORAL(2),   // F2 — observable semantics compatible for documented cases
    MIGRATION(3),    // F3 — migrator can convert Jenkins usage automatically
}
