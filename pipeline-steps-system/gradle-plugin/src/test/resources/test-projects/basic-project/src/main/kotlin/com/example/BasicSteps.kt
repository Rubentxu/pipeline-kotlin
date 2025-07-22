package com.example

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel

@Step(
    name = "basicStep",
    description = "A basic step for testing",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun basicStep(input: String): String {
    return "Basic step processed: $input"
}

@Step(
    name = "buildStep",
    description = "A build step example", 
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun buildStep(
    projectName: String,
    version: String = "1.0.0",
    clean: Boolean = false
): Map<String, Any> {
    return mapOf(
        "project" to projectName,
        "version" to version,
        "clean" to clean,
        "status" to "built"
    )
}