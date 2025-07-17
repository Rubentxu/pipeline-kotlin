// RUN_PIPELINE_TILL: FRONTEND

import dev.rubentxu.pipeline.annotations.Step

// Test invalid step function - missing required parameters
@Step(name = "", description = "")
fun invalidStep() {
    println("This should cause diagnostic issues")
}