package dev.rubentxu.pipeline.scripting

/**
 * Simple data class representing a task definition for the Task DSL.
 * 
 * @property name The name of the task
 * @property description A description of what the task does
 * @property command The command to execute
 * @property environment Environment variables for the task
 */
data class TaskDefinition(
    val name: String,
    val description: String = "",
    val command: String,
    val environment: Map<String, String> = emptyMap()
)