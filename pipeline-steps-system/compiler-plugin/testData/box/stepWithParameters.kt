// Test for @Step annotation with multiple parameters
import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel

@Step(
    name = "multiParamStep",
    description = "Step with multiple parameters",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun multiParamStep(
    param1: String,
    param2: Int = 42,
    param3: Boolean = true
): String {
    return "Params: $param1, $param2, $param3"
}

fun box(): String {
    // This should compile successfully after compiler plugin transformation
    return "OK"
}