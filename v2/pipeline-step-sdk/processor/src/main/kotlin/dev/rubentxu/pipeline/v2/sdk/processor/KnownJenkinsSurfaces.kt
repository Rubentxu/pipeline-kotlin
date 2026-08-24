package dev.rubentxu.pipeline.v2.sdk.processor

import dev.rubentxu.pipeline.v2.sdk.CompatibilityLevel

/**
 * Hardcoded `name → JenkinsSurfaceMeta` map for the R3 KSP enum/array limitation.
 *
 * Background:
 *   - @JenkinsSurface.compatibility is a `CompatibilityLevel` enum parameter.
 *   - KSP 2.3.11 cannot reliably extract enum/array values from Kotlin 2.4.10
 *     annotation arguments in this configuration (same limitation as
 *     @Step's `execution: ExecutionLocation`, `effects: Array<Effect>`,
 *     `replay: ReplayPolicy` documented in StepDescriptorGenerator.kt:58-69).
 *   - The M2-R2 workaround is a `name → metadata` map; M2-R3 extends the
 *     same pattern with `name → jenkinsSurface`.
 *
 * Future: when KSP supports enum/array extraction (KSP upgrade, M3+),
 * migrate to reflective @JenkinsSurface extraction and delete this map.
 */
object KnownJenkinsSurfaces {

    data class JenkinsSurfaceMeta(
        val step: String,
        val plugin: String,
        val compatibility: CompatibilityLevel,
    )

    private val MAP: Map<String, JenkinsSurfaceMeta> = mapOf(
        "echo"   to JenkinsSurfaceMeta("echo",   "workflow-durable-task-step", CompatibilityLevel.MIGRATION),
        "sh"     to JenkinsSurfaceMeta("sh",     "workflow-durable-task-step", CompatibilityLevel.MIGRATION),
        "error"  to JenkinsSurfaceMeta("error",  "workflow-step",               CompatibilityLevel.MIGRATION),
        "sleep"  to JenkinsSurfaceMeta("sleep",  "workflow-durable-task-step", CompatibilityLevel.MIGRATION),
    )

    fun forName(name: String): JenkinsSurfaceMeta? = MAP[name]

    /** Returns the canonical "<step>|<plugin>|F<n>" triple (or "" if unknown). */
    fun tripleFor(name: String): String =
        MAP[name]?.let { "${it.step}|${it.plugin}|F${it.compatibility.level}" } ?: ""
}
