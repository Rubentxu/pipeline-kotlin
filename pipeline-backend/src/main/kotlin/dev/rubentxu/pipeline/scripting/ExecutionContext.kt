package dev.rubentxu.pipeline.scripting

data class ExecutionContext(
    val environment: Map<String, String>,
    val dslType: String
)