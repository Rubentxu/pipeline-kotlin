// WITH_STDLIB

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory

@Step(
    name = "stepWithoutContext",
    description = "Test step without explicit PipelineContext - should be injected automatically",
    category = StepCategory.UTIL
)
suspend fun stepWithoutContext(message: String): String {
    // Este test verifica que el plugin inyecta automáticamente el PipelineContext
    // incluso cuando no está explícitamente declarado en la función original
    return "OK: $message"
}

fun box(): String {
    return "OK"
}