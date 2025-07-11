package dev.rubentxu.pipeline.scripting

class ExecutorResolver<T>(private val managers: List<AgentManager<T>>) {
    fun resolve(context: ExecutionContext): AgentManager<T> {
        return managers.firstOrNull { it.canHandle(context) }
            ?: throw IllegalArgumentException("No agent manager found for context: $context")
    }
}