// Basic test for @Step annotation code generation
import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel

@Step(
    name = "testStep",
    description = "Test step function",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun testStep(message: String): String {
    return "Test: $message"
}

fun box(): String {
    // This should compile successfully after compiler plugin transformation
    return "OK"
}