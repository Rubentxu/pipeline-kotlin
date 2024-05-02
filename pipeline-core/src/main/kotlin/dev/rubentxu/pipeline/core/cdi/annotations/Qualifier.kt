package dev.rubentxu.pipeline.core.cdi.annotations

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
annotation class Qualifier(
    val value: String = ""
)