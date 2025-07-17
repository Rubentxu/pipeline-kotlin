// WITH_STDLIB

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel

@Step(
    name = "stepWithContext",
    description = "Test step for context scenarios",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun stepWithContext(message: String): String {
    // Este test verifica que el plugin maneja adecuadamente las funciones @Step
    return "OK: $message with context"
}

fun box(): String {
    return "OK"
}