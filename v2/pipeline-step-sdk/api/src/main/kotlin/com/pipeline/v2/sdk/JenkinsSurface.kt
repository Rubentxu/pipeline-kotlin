package com.pipeline.v2.sdk

/**
 * Maps a V2 step function to its canonical Jenkins surface (step name +
 * originating plugin + familiarity level).
 *
 * Per ADR-0005.md:13 and docs/v2/01-product/JENKINS_FAMILIARITY.md L23-34.
 * Targets FUNCTION, BINARY retention (consistent with @Step at Step.kt:11-12).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class JenkinsSurface(
    val step: String,
    val plugin: String,
    val compatibility: CompatibilityLevel,
)
