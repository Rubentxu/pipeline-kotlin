// RUN_PIPELINE_TILL: FRONTEND

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory

// Test suspend function validation

@Step(name = "suspendStep", description = "Valid suspend step")
suspend fun validSuspendStep(message: String): String {
    return "Suspend OK: $message"
}

@Step(name = "nonSuspendStep", description = "Non-suspend step - should generate warning")
fun nonSuspendStep(message: String): String {
    return "Non-suspend: $message"
}

@Step(name = "suspendWithCallbacks", description = "Suspend step with callback parameters")
suspend fun suspendStepWithCallbacks(
    message: String,
    callback: suspend () -> Unit,
    listener: (String) -> Unit
): String {
    return "Callbacks: $message"
}