package dev.rubentxu.pipeline.core.cdi.annotations

import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority


@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PipelineComponent(
    val name: String = "",

    val priority: ConfigurationPriority = ConfigurationPriority.LOW

)