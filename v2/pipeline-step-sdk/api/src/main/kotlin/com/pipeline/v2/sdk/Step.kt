package com.pipeline.v2.sdk

/**
 * Marks a function as a V2 pipeline step. KSP scans @Step-annotated functions
 * in the consuming module and emits GeneratedStepDescriptors.kt at compile time.
 *
 * Retention is BINARY so descriptors survive into compiled JARs but stay
 * invisible to source-level tooling (no false positives from generic
 * Kotlin annotations).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Step(
    val id: String,
    val name: String,
    val execution: ExecutionLocation = ExecutionLocation.WORKER,
    val effects: Array<Effect> = [Effect.READ_ONLY],
    val replay: ReplayPolicy = ReplayPolicy.MEMOIZED,
)
