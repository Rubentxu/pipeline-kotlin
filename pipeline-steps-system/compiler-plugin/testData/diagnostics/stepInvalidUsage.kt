// Test for diagnostic when @Step annotation is used incorrectly
import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel

class TestClass {
    @Step(  // @Step should only be used on top-level functions
        name = "invalidClassStep",
        description = "Step in class",
        category = StepCategory.UTIL,
        securityLevel = SecurityLevel.RESTRICTED
    )
    fun invalidClassStep() {
        // This should generate a diagnostic message
    }
}

@Step(
    name = "validTopLevelStep",
    description = "Valid top-level step",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
fun validTopLevelStep() {
    // This should not generate diagnostics
}