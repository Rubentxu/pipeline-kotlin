import dev.rubentxu.pipeline.scripting.TaskDefinition

TaskDefinition(
    name = "example-task",
    description = "This is an example task",
    command = "echo 'Hello World'",
    environment = mapOf("GREETING" to "Hello")
)