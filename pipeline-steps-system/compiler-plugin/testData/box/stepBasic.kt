// WITH_STDLIB

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel

@Step(
    name = "testStep",
    description = "Test step for verification",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun testStep(message: String): String {
    return "OK: $message"
}

fun box(): String {
    return "OK"
}