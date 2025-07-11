package dev.rubentxu.pipeline.scripting

/**
 * Generic interface for resolving the appropriate executor for a DSL type.
 * 
 * @param T The type of definition
 */
interface ExecutorResolver<T> {
    /**
     * Resolves the appropriate configuration loader for the DSL type.
     * 
     * @param dslType The DSL type
     * @return The configuration loader
     */
    fun resolveConfigurationLoader(dslType: String): ConfigurationLoader<Any>
    
    /**
     * Resolves the appropriate pipeline executor for the DSL type.
     * 
     * @param dslType The DSL type
     * @return The pipeline executor
     */
    fun resolvePipelineExecutor(dslType: String): PipelineExecutor<T>
    
    /**
     * Resolves the appropriate agent manager for the DSL type.
     * 
     * @param dslType The DSL type
     * @return The agent manager
     */
    fun resolveAgentManager(dslType: String): AgentManager<T>
}