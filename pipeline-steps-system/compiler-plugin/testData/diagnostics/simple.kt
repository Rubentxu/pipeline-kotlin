// RUN_PIPELINE_TILL: FRONTEND

import dev.rubentxu.pipeline.annotations.Step

@Step(name = "simpleTest", description = "Simple test step")
fun test() {
    val s = "hello"
    println(s)
}