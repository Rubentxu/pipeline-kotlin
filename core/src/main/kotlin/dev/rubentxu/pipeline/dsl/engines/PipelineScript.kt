package dev.rubentxu.pipeline.dsl.engines

import kotlin.script.experimental.annotations.KotlinScript

/**
 * Marker annotation for pipeline scripts.
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
@KotlinScript(
    fileExtension = "pipeline.kts",
    compilationConfiguration = PipelineScriptConfiguration::class
)
annotation class PipelineScript