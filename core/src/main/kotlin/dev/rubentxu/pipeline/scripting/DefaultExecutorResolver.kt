package dev.rubentxu.pipeline.scripting

/**
 * Default implementation of ExecutorResolver that resolves components based on DSL type.
 */
class DefaultExecutorResolver : ExecutorResolver<Any> {
    
    override fun resolveConfigurationLoader(dslType: String): ConfigurationLoader<Any> {
        return when (dslType) {
            "pipeline" -> PipelineConfigurationLoader() as ConfigurationLoader<Any>
            "task" -> TaskConfigurationLoader() as ConfigurationLoader<Any>
            else -> throw IllegalArgumentException("Unknown DSL type: $dslType")
        }
    }
    
    override fun resolvePipelineExecutor(dslType: String): PipelineExecutor<Any> {
        return when (dslType) {
            "pipeline" -> PipelineDslExecutor() as PipelineExecutor<Any>
            "task" -> TaskDslExecutor() as PipelineExecutor<Any>
            else -> throw IllegalArgumentException("Unknown DSL type: $dslType")
        }
    }
    
    override fun resolveAgentManager(dslType: String): AgentManager<Any> {
        return when (dslType) {
            "pipeline" -> PipelineAgentManager() as AgentManager<Any>
            "task" -> TaskAgentManager() as AgentManager<Any>
            else -> throw IllegalArgumentException("Unknown DSL type: $dslType")
        }
    }
}