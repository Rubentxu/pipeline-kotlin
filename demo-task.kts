#!/usr/bin/env kotlin

// Simple demo of the new Task DSL
import dev.rubentxu.pipeline.scripting.TaskDefinition

val task = TaskDefinition(
    name = "demo-task",
    description = "Demonstrates the new Task DSL",
    command = "echo 'Task DSL is working!'",
    environment = mapOf("TASK_TYPE" to "demo")
)

println("Task Name: ${task.name}")
println("Description: ${task.description}")
println("Command: ${task.command}")
println("Environment: ${task.environment}")