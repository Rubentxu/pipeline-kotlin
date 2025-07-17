// Test for diagnostic when @Step annotation is missing required parameters
import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory

@Step  // Missing required parameters - should generate diagnostic
fun invalidStep() {
    // This should generate a diagnostic message
}

fun validStep() {
    // This function without @Step should not generate diagnostics
}