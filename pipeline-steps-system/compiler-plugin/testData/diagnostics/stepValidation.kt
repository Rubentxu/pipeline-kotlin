// RUN_PIPELINE_TILL: FRONTEND

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel

// Test comprehensive validation cases for StepModernChecker

@Step(name = "validStep", description = "A valid step function", category = StepCategory.BUILD)
suspend fun validStep(param1: String, param2: Int): String {
    return "Valid step"
}

@Step(name = "_invalidName", description = "Step with invalid name starting with underscore")
fun invalidNameStep(): String {
    return "Invalid name"
}

@Step(name = "tooManyParams", description = "Step with too many parameters")
fun stepWithTooManyParams(
    p1: String, p2: String, p3: String, p4: String, p5: String,
    p6: String, p7: String, p8: String, p9: String, p10: String,
    p11: String // This should trigger a validation warning
): String {
    return "Too many params"
}

@Step(name = "noParams", description = "Step with no parameters")
fun stepWithNoParams(): String {
    return "No params"
}