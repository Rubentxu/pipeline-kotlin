package dev.rubentxu.pipeline.scripting

import java.nio.file.Path

class TaskDslEvaluator : ScriptEvaluator<TaskDefinition> {
    
    override fun evaluate(scriptPath: Path): Result<TaskDefinition> {
        return try {
            val scriptFile = scriptPath.toFile()
            if (!scriptFile.exists()) {
                return Result.failure(IllegalArgumentException("Script file ${scriptPath} does not exist"))
            }
            
            val content = scriptFile.readText()
            val tasks = parseTasksFromText(content)
            Result.success(TaskDefinition(tasks))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun parseTasksFromText(content: String): List<String> {
        // Simple text parsing - each line is a task
        return content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}