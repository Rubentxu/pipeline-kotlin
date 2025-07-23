package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.dsl.engines.GenericKotlinDslEngine
import dev.rubentxu.pipeline.dsl.engines.GenericKotlinDslEngineBuilder
import dev.rubentxu.pipeline.dsl.engines.PipelineDslEngine
import dev.rubentxu.pipeline.events.EventBus
import dev.rubentxu.pipeline.events.DefaultEventBus
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.config.IPipelineConfig
import dev.rubentxu.pipeline.plugins.PluginManager
import dev.rubentxu.pipeline.security.SandboxManager
import dev.rubentxu.pipeline.security.SandboxExecutionResult
import dev.rubentxu.pipeline.security.SecurityViolationException
import dev.rubentxu.pipeline.execution.ResourceLimitEnforcer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Central manager for all DSL operations in the pipeline system.
 * This class serves as the main entry point for executing different types of DSLs,
 * managing their engines, and providing unified execution capabilities.
 */
class DslManager(
    private val pipelineConfig: IPipelineConfig,
    private val pluginManager: PluginManager? = null,
    private val eventBus: EventBus = DefaultEventBus(),
    private val logger: ILogger = PipelineLogger.getLogger(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    
    private val engineRegistry = DefaultDslEngineRegistry(logger)
    private val activeExecutions = ConcurrentHashMap<String, DslExecution<*>>()
    private val executionStats = ConcurrentHashMap<String, DslExecutionStats>()
    val sandboxManager = SandboxManager(logger)
    private val resourceLimitEnforcer = ResourceLimitEnforcer(logger)
    
    init {
        // Register built-in engines
        registerBuiltInEngines()
        
        // Initialize plugin-based engines if plugin manager is available
        pluginManager?.let { initializePluginEngines(it) }
    }
    
    /**
     * Executes a DSL script file with automatic engine detection.
     */
    suspend fun <TResult : Any> executeFile(
        scriptFile: File,
        compilationContext: DslCompilationContext? = null,
        executionContext: DslExecutionContext? = null
    ): DslExecutionResult<TResult> {
        
        val engine = getEngineForFile<TResult>(scriptFile)
            ?: return DslExecutionResult.Failure(
                DslError("NO_ENGINE", "No suitable DSL engine found for file: ${scriptFile.name}")
            )
        
        return executeWithEngine(
            engine = engine,
            scriptFile = scriptFile,
            compilationContext = compilationContext ?: engine.createDefaultCompilationContext(),
            executionContext = executionContext ?: engine.createDefaultExecutionContext()
        )
    }
    
    /**
     * Executes DSL script content with a specific engine.
     */
    suspend fun <TResult : Any> executeContent(
        scriptContent: String,
        engineId: String,
        scriptName: String = "inline-script",
        compilationContext: DslCompilationContext? = null,
        executionContext: DslExecutionContext? = null
    ): DslExecutionResult<TResult> {
        
        val engine = engineRegistry.getEngine<TResult>(engineId)
            ?: return DslExecutionResult.Failure(
                DslError("ENGINE_NOT_FOUND", "DSL engine not found: $engineId")
            )
        
        return executeContentWithEngine(
            engine = engine,
            scriptContent = scriptContent,
            scriptName = scriptName,
            compilationContext = compilationContext ?: engine.createDefaultCompilationContext(),
            executionContext = executionContext ?: engine.createDefaultExecutionContext()
        )
    }
    
    /**
     * Executes DSL script content in a secure sandbox environment.
     * This method provides enhanced security by isolating script execution.
     */
    suspend fun <TResult : Any> executeContentSecurely(
        scriptContent: String,
        engineId: String,
        scriptName: String = "secure-script",
        compilationContext: DslCompilationContext? = null,
        executionContext: DslExecutionContext? = null
    ): DslExecutionResult<TResult> {
        
        val engine = engineRegistry.getEngine<TResult>(engineId)
            ?: return DslExecutionResult.Failure(
                DslError("ENGINE_NOT_FOUND", "DSL engine not found: $engineId")
            )
        
        val finalExecutionContext = executionContext ?: engine.createDefaultExecutionContext()
        val finalCompilationContext = compilationContext ?: engine.createDefaultCompilationContext()
        
        // Validate security policy before execution
        val securityValidation = sandboxManager.validateSecurityPolicy(finalExecutionContext)
        if (!securityValidation.isValid) {
            return DslExecutionResult.Failure(
                DslError("SECURITY_POLICY_VIOLATION", "Security policy validation failed: ${securityValidation.issues.joinToString(", ")}")
            )
        }
        
        logger.info("Executing script securely: $scriptName with engine: $engineId")
        
        return try {
            val sandboxResult = sandboxManager.executeSecurely<TResult>(
                scriptContent = scriptContent,
                scriptName = scriptName,
                executionContext = finalExecutionContext,
                compilationConfig = finalCompilationContext.toKotlinScriptConfig(),
                evaluationConfig = finalExecutionContext.toKotlinScriptEvaluationConfig()
            )
            
            when (sandboxResult) {
                is SandboxExecutionResult.Success -> {
                    logger.info("Secure execution completed successfully for script: $scriptName")
                    logger.debug("Resource usage: ${sandboxResult.resourceUsage.toHumanReadable()}")
                    
                    DslExecutionResult.Success(
                        result = sandboxResult.result,
                        metadata = DslExecutionMetadata(
                            executionTimeMs = sandboxResult.executionTime,
                            memoryUsedMb = sandboxResult.resourceUsage.memoryUsedBytes / 1024 / 1024,
                            threadsUsed = sandboxResult.resourceUsage.threadsCreated,
                            eventsPublished = 0
                        )
                    )
                }
                
                is SandboxExecutionResult.Failure -> {
                    logger.error("Secure execution failed for script $scriptName: ${sandboxResult.reason}")
                    
                    if (sandboxResult.error is SecurityViolationException) {
                        DslExecutionResult.Failure(
                            DslError("SECURITY_VIOLATION", "Security violation: ${sandboxResult.reason}")
                        )
                    } else {
                        DslExecutionResult.Failure(
                            DslError("EXECUTION_FAILED", sandboxResult.reason)
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            logger.error("Unexpected error during secure execution of script $scriptName: ${e.message}")
            DslExecutionResult.Failure(
                DslError("UNEXPECTED_ERROR", "Unexpected error during secure execution: ${e.message}")
            )
        }
    }
    
    /**
     * Executes a DSL script file in a secure sandbox environment.
     */
    suspend fun <TResult : Any> executeFileSecurely(
        scriptFile: File,
        compilationContext: DslCompilationContext? = null,
        executionContext: DslExecutionContext? = null
    ): DslExecutionResult<TResult> {
        
        val engine = getEngineForFile<TResult>(scriptFile)
            ?: return DslExecutionResult.Failure(
                DslError("NO_ENGINE", "No suitable DSL engine found for file: ${scriptFile.name}")
            )
        
        return try {
            val scriptContent = scriptFile.readText()
            executeContentSecurely<TResult>(
                scriptContent = scriptContent,
                engineId = engine.getEngineInfo().engineId,
                scriptName = scriptFile.name,
                compilationContext = compilationContext,
                executionContext = executionContext
            )
        } catch (e: Exception) {
            logger.error("Failed to read script file ${scriptFile.absolutePath}: ${e.message}")
            DslExecutionResult.Failure(
                DslError("FILE_READ_ERROR", "Failed to read script file: ${e.message}")
            )
        }
    }
    
    /**
     * Terminates execution of a specific script in the sandbox.
     */
    fun terminateSecureExecution(scriptName: String): Boolean {
        return sandboxManager.terminateExecution(scriptName)
    }
    
    /**
     * Gets resource usage for an active secure execution.
     */
    fun getSecureExecutionResourceUsage(scriptName: String) = sandboxManager.getResourceUsage(scriptName)
    
    /**
     * Gets status of all active secure executions.
     */
    fun getActiveSecureExecutions() = sandboxManager.getActiveExecutions()
    
    /**
     * Executes DSL script content with strict resource limits enforcement.
     */
    suspend fun <TResult : Any> executeContentWithResourceLimits(
        scriptContent: String,
        engineId: String,
        scriptName: String = "resource-limited-script",
        compilationContext: DslCompilationContext? = null,
        executionContext: DslExecutionContext? = null
    ): dev.rubentxu.pipeline.execution.ResourceLimitedResult<DslExecutionResult<TResult>> {
        
        val engine = engineRegistry.getEngine<TResult>(engineId)
            ?: return dev.rubentxu.pipeline.execution.ResourceLimitedResult.Failure(
                dev.rubentxu.pipeline.execution.ResourceLimitViolation(
                    type = dev.rubentxu.pipeline.execution.ResourceLimitType.EXECUTION_ERROR,
                    message = "DSL engine not found: $engineId",
                    actualValue = 0,
                    limitValue = 0
                )
            )
        
        val compContext = compilationContext ?: engine.createDefaultCompilationContext()
        val execContext = executionContext ?: engine.createDefaultExecutionContext()
        
        return resourceLimitEnforcer.enforceResourceLimits(
            executionId = scriptName,
            limits = execContext.resourceLimits
        ) {
            executeContentWithEngine(
                engine = engine,
                scriptContent = scriptContent,
                scriptName = scriptName,
                compilationContext = compContext,
                executionContext = execContext
            )
        }
    }
    
    /**
     * Terminates an execution due to resource limit violations.
     */
    fun terminateResourceLimitedExecution(executionId: String): Boolean {
        return resourceLimitEnforcer.terminateExecution(executionId)
    }
    
    /**
     * Gets resource usage for an active execution.
     */
    fun getResourceUsage(executionId: String): dev.rubentxu.pipeline.execution.ResourceUsageStats? {
        return resourceLimitEnforcer.getResourceUsage(executionId)
    }
    
    /**
     * Gets all active executions with their resource usage.
     */
    fun getAllActiveResourceLimitedExecutions(): Map<String, dev.rubentxu.pipeline.execution.ResourceUsageStats> {
        return resourceLimitEnforcer.getAllActiveExecutions()
    }
    
    /**
     * Validates DSL script content without executing it.
     */
    suspend fun validateContent(
        scriptContent: String,
        engineId: String,
        compilationContext: DslCompilationContext? = null
    ): DslValidationResult {
        
        val engine = engineRegistry.getEngine<Any>(engineId)
            ?: return DslValidationResult.Invalid(
                listOf(DslError("ENGINE_NOT_FOUND", "DSL engine not found: $engineId"))
            )
        
        val context = compilationContext ?: engine.createDefaultCompilationContext()
        return engine.validate(scriptContent, context)
    }
    
    /**
     * Validates a DSL script file.
     */
    suspend fun validateFile(
        scriptFile: File,
        compilationContext: DslCompilationContext? = null
    ): DslValidationResult {
        
        val engine = getEngineForFile<Any>(scriptFile)
            ?: return DslValidationResult.Invalid(
                listOf(DslError("NO_ENGINE", "No suitable DSL engine found for file: ${scriptFile.name}"))
            )
        
        return try {
            val scriptContent = scriptFile.readText()
            val context = compilationContext ?: engine.createDefaultCompilationContext()
            engine.validate(scriptContent, context)
        } catch (e: Exception) {
            DslValidationResult.Invalid(
                listOf(DslError("FILE_READ_ERROR", "Failed to read file: ${e.message}", cause = e))
            )
        }
    }
    
    /**
     * Validates DSL script content with enhanced error reporting and security checks.
     */
    suspend fun validateContentWithEnhancedReporting(
        scriptContent: String,
        engineId: String,
        scriptName: String = "script.kts",
        compilationContext: DslCompilationContext? = null,
        executionContext: DslExecutionContext? = null
    ): dev.rubentxu.pipeline.dsl.validation.DslValidationReport {
        val engine = engineRegistry.getEngine<Any>(engineId)
        if (engine == null) {
            return dev.rubentxu.pipeline.dsl.validation.DslValidationReport(
                scriptName = scriptName,
                isValid = false,
                issues = listOf(
                    dev.rubentxu.pipeline.dsl.validation.DslValidationIssue(
                        code = "ENGINE_NOT_FOUND",
                        message = "DSL engine '$engineId' not found",
                        severity = dev.rubentxu.pipeline.dsl.validation.DslValidationSeverity.ERROR
                    )
                ),
                warnings = emptyList(),
                validationTimeMs = 0,
                recommendations = emptyList()
            )
        }
        
        val compContext = compilationContext ?: engine.createDefaultCompilationContext()
        val execContext = executionContext ?: engine.createDefaultExecutionContext()
        
        // Use enhanced validation - use the extension method
        val validator = dev.rubentxu.pipeline.dsl.validation.DslValidator(sandboxManager, logger)
        return validator.validateScript(scriptContent, scriptName, compContext, execContext)
    }
    
    /**
     * Validates a DSL script file with enhanced error reporting.
     */
    suspend fun validateFileWithEnhancedReporting(
        scriptFile: File,
        compilationContext: DslCompilationContext? = null,
        executionContext: DslExecutionContext? = null
    ): dev.rubentxu.pipeline.dsl.validation.DslValidationReport {
        val engine = getEngineForFile<Any>(scriptFile)
        if (engine == null) {
            return dev.rubentxu.pipeline.dsl.validation.DslValidationReport(
                scriptName = scriptFile.name,
                isValid = false,
                issues = listOf(
                    dev.rubentxu.pipeline.dsl.validation.DslValidationIssue(
                        code = "NO_ENGINE",
                        message = "No suitable DSL engine found for file: ${scriptFile.name}",
                        severity = dev.rubentxu.pipeline.dsl.validation.DslValidationSeverity.ERROR
                    )
                ),
                warnings = emptyList(),
                validationTimeMs = 0,
                recommendations = emptyList()
            )
        }
        
        val scriptContent = try {
            scriptFile.readText()
        } catch (e: Exception) {
            return dev.rubentxu.pipeline.dsl.validation.DslValidationReport(
                scriptName = scriptFile.name,
                isValid = false,
                issues = listOf(
                    dev.rubentxu.pipeline.dsl.validation.DslValidationIssue(
                        code = "FILE_READ_ERROR",
                        message = "Failed to read script file: ${e.message}",
                        severity = dev.rubentxu.pipeline.dsl.validation.DslValidationSeverity.ERROR
                    )
                ),
                warnings = emptyList(),
                validationTimeMs = 0,
                recommendations = emptyList()
            )
        }
        
        return validateContentWithEnhancedReporting(
            scriptContent = scriptContent,
            engineId = engine.engineId,
            scriptName = scriptFile.name,
            compilationContext = compilationContext,
            executionContext = executionContext
        )
    }
    
    /**
     * Registers a new DSL engine.
     */
    fun <TResult : Any> registerEngine(engine: DslEngine<TResult>) {
        engineRegistry.registerEngine(engine)
        logger.info("Registered DSL engine: ${engine.engineId}")
    }
    
    /**
     * Unregisters a DSL engine.
     */
    fun unregisterEngine(engineId: String) {
        engineRegistry.unregisterEngine(engineId)
        logger.info("Unregistered DSL engine: $engineId")
    }
    
    /**
     * Gets information about all registered engines.
     */
    fun getEngineInfo(): List<DslEngineInfo> {
        return engineRegistry.getAllEngines().map { it.getEngineInfo() }
    }
    
    /**
     * Gets information about a specific engine.
     */
    fun getEngineInfo(engineId: String): DslEngineInfo? {
        return engineRegistry.getEngine<Any>(engineId)?.getEngineInfo()
    }
    
    /**
     * Gets engines that support a specific capability.
     */
    fun getEnginesWithCapability(capability: DslCapability): List<DslEngineInfo> {
        return engineRegistry.getEnginesWithCapability(capability).map { it.getEngineInfo() }
    }
    
    /**
     * Gets a specific DSL engine by ID.
     */
    fun <TResult : Any> getEngine(engineId: String): DslEngine<TResult>? {
        return engineRegistry.getEngine(engineId)
    }
    
    /**
     * Cancels an active execution.
     */
    suspend fun cancelExecution(executionId: String): Boolean {
        val execution = activeExecutions[executionId]
        return if (execution != null) {
            execution.cancel()
            activeExecutions.remove(executionId)
            logger.info("Cancelled DSL execution: $executionId")
            true
        } else {
            logger.warn("Attempted to cancel non-existent execution: $executionId")
            false
        }
    }
    
    /**
     * Gets statistics for all executions.
     */
    fun getExecutionStats(): Map<String, DslExecutionStats> {
        return executionStats.toMap()
    }
    
    /**
     * Gets statistics for a specific execution.
     */
    fun getExecutionStats(executionId: String): DslExecutionStats? {
        return executionStats[executionId]
    }
    
    /**
     * Gets currently active executions.
     */
    fun getActiveExecutions(): List<String> {
        return activeExecutions.keys.toList()
    }
    
    /**
     * Streams DSL execution events.
     */
    fun executionEvents(): Flow<dev.rubentxu.pipeline.events.DslEvent> = flow {
        eventBus.subscribe(dev.rubentxu.pipeline.events.DslEvent::class).collect { event ->
            emit(event)
        }
    }
    
    /**
     * Creates a new DSL engine from a third-party library.
     */
    fun createThirdPartyEngine(
        engineId: String,
        engineName: String,
        supportedExtensions: Set<String>,
        scriptDefinitionClass: kotlin.reflect.KClass<*>,
        configure: GenericKotlinDslEngineBuilder.() -> Unit = {}
    ): GenericKotlinDslEngine {
        
        return GenericKotlinDslEngineBuilder()
            .engineId(engineId)
            .engineName(engineName)
            .supportedExtensions(supportedExtensions)
            .scriptDefinitionClass(scriptDefinitionClass)
            .logger(logger)
            .apply(configure)
            .build()
    }
    
    /**
     * Generates a comprehensive report of the DSL manager state.
     */
    fun generateReport(): DslManagerReport {
        val registryStats = (engineRegistry as DefaultDslEngineRegistry).getRegistryStats()
        
        return DslManagerReport(
            registeredEngines = registryStats.totalEngines,
            supportedExtensions = registryStats.supportedExtensions,
            availableCapabilities = registryStats.availableCapabilities,
            activeExecutions = activeExecutions.size,
            totalExecutions = executionStats.size,
            engines = registryStats.engines.map { summary ->
                EngineReport(
                    engineId = summary.engineId,
                    engineName = summary.engineName,
                    version = summary.version,
                    supportedExtensions = summary.supportedExtensions,
                    capabilities = summary.capabilities,
                    executionCount = executionStats.values.count { it.dslType == summary.engineId }
                )
            }
        )
    }
    
    /**
     * Shuts down the DSL manager and cleans up resources.
     */
    suspend fun shutdown() {
        logger.info("Shutting down DSL manager...")
        
        // Cancel all active executions
        val activeExecutionIds = activeExecutions.keys.toList()
        activeExecutionIds.forEach { cancelExecution(it) }
        
        // Shutdown sandbox manager
        sandboxManager.shutdown()
        
        // Shutdown resource limit enforcer
        resourceLimitEnforcer.shutdown()
        
        // Close event bus
        eventBus.close()
        
        // Cancel the coroutine scope
        scope.cancel()
        
        logger.info("DSL manager shutdown complete")
    }
    
    private fun registerBuiltInEngines() {
        // Register the pipeline DSL engine
        val pipelineEngine = PipelineDslEngine(pipelineConfig, logger)
        engineRegistry.registerEngine(pipelineEngine)
        
        // Register Jenkins compatibility DSL engine
        // val jenkinsEngine = dev.rubentxu.pipeline.jenkins.JenkinsDslEngine(pipelineConfig, logger)
        // engineRegistry.registerEngine(jenkinsEngine)
        
        logger.info("Registered built-in DSL engines")
    }
    
    private fun initializePluginEngines(pluginManager: PluginManager) {
        // TODO: Scan plugins for DSL engines and register them
        // This would be implemented when the plugin system is enhanced
        // to support DSL engine plugins
        
        logger.debug("Plugin-based DSL engines not yet implemented")
    }
    
    private fun <TResult : Any> getEngineForFile(scriptFile: File): DslEngine<TResult>? {
        val extension = ".${scriptFile.extension}"
        return engineRegistry.getEngineForExtension(extension)
    }
    
    private suspend fun <TResult : Any> executeWithEngine(
        engine: DslEngine<TResult>,
        scriptFile: File,
        compilationContext: DslCompilationContext,
        executionContext: DslExecutionContext
    ): DslExecutionResult<TResult> {
        
        val executionId = UUID.randomUUID().toString()
        val execution = DslExecution(executionId, engine, scriptFile.name)
        
        try {
            activeExecutions[executionId] = execution
            
            // Publish execution started event
            val startEvent = dev.rubentxu.pipeline.events.DslExecutionStarted(
                pipelineId = executionId,
                correlationId = executionId,
                dslType = engine.engineId,
                executionId = executionId,
                scriptName = scriptFile.name,
                context = mapOf("file" to scriptFile.absolutePath)
            )
            eventBus.publish(startEvent)
            
            // Execute the script
            val result = engine.compileAndExecute(scriptFile, compilationContext, executionContext)
            
            // Update statistics
            when (result) {
                is DslExecutionResult.Success -> {
                    val stats = DslExecutionStats(
                        dslType = engine.engineId,
                        executionId = executionId,
                        startTime = startEvent.timestamp,
                        endTime = java.time.Instant.now(),
                        executionTimeMs = result.metadata.executionTimeMs,
                        eventsPublished = 1
                    )
                    executionStats[executionId] = stats
                    
                    // Publish completion event
                    val completedEvent = dev.rubentxu.pipeline.events.DslExecutionCompleted(
                        pipelineId = executionId,
                        correlationId = executionId,
                        dslType = engine.engineId,
                        executionId = executionId,
                        result = result.result,
                        executionTimeMs = result.metadata.executionTimeMs
                    )
                    eventBus.publish(completedEvent)
                }
                
                is DslExecutionResult.Failure -> {
                    val stats = DslExecutionStats(
                        dslType = engine.engineId,
                        executionId = executionId,
                        startTime = startEvent.timestamp,
                        endTime = java.time.Instant.now(),
                        executionTimeMs = result.metadata?.executionTimeMs ?: 0,
                        errorsCount = 1
                    )
                    executionStats[executionId] = stats
                    
                    // Publish failure event
                    val failedEvent = dev.rubentxu.pipeline.events.DslExecutionFailed(
                        pipelineId = executionId,
                        correlationId = executionId,
                        dslType = engine.engineId,
                        executionId = executionId,
                        error = result.error.message,
                        cause = result.error.cause?.message
                    )
                    eventBus.publish(failedEvent)
                }
            }
            
            return result
            
        } finally {
            activeExecutions.remove(executionId)
        }
    }
    
    private suspend fun <TResult : Any> executeContentWithEngine(
        engine: DslEngine<TResult>,
        scriptContent: String,
        scriptName: String,
        compilationContext: DslCompilationContext,
        executionContext: DslExecutionContext
    ): DslExecutionResult<TResult> {
        
        val executionId = UUID.randomUUID().toString()
        val execution = DslExecution(executionId, engine, scriptName)
        
        try {
            activeExecutions[executionId] = execution
            
            // Publish execution started event
            val startEvent = dev.rubentxu.pipeline.events.DslExecutionStarted(
                pipelineId = executionId,
                correlationId = executionId,
                dslType = engine.engineId,
                executionId = executionId,
                scriptName = scriptName,
                context = mapOf("contentLength" to scriptContent.length)
            )
            eventBus.publish(startEvent)
            
            // Compile and execute the script
            val compilationResult = engine.compile(scriptContent, scriptName, compilationContext)
            
            when (compilationResult) {
                is DslCompilationResult.Success -> {
                    val executionResult = engine.execute(compilationResult.compiledScript, executionContext)
                    
                    // Update statistics and publish events (similar to executeWithEngine)
                    when (executionResult) {
                        is DslExecutionResult.Success -> {
                            val stats = DslExecutionStats(
                                dslType = engine.engineId,
                                executionId = executionId,
                                startTime = startEvent.timestamp,
                                endTime = java.time.Instant.now(),
                                compilationTimeMs = compilationResult.metadata.compilationTimeMs,
                                executionTimeMs = executionResult.metadata.executionTimeMs,
                                eventsPublished = 1
                            )
                            executionStats[executionId] = stats
                            
                            val completedEvent = dev.rubentxu.pipeline.events.DslExecutionCompleted(
                                pipelineId = executionId,
                                correlationId = executionId,
                                dslType = engine.engineId,
                                executionId = executionId,
                                result = executionResult.result,
                                executionTimeMs = executionResult.metadata.executionTimeMs
                            )
                            eventBus.publish(completedEvent)
                        }
                        
                        is DslExecutionResult.Failure -> {
                            val stats = DslExecutionStats(
                                dslType = engine.engineId,
                                executionId = executionId,
                                startTime = startEvent.timestamp,
                                endTime = java.time.Instant.now(),
                                compilationTimeMs = compilationResult.metadata.compilationTimeMs,
                                executionTimeMs = executionResult.metadata?.executionTimeMs ?: 0,
                                errorsCount = 1
                            )
                            executionStats[executionId] = stats
                            
                            val failedEvent = dev.rubentxu.pipeline.events.DslExecutionFailed(
                                pipelineId = executionId,
                                correlationId = executionId,
                                dslType = engine.engineId,
                                executionId = executionId,
                                error = executionResult.error.message,
                                cause = executionResult.error.cause?.message
                            )
                            eventBus.publish(failedEvent)
                        }
                    }
                    
                    return executionResult
                }
                
                is DslCompilationResult.Failure -> {
                    val error = compilationResult.errors.firstOrNull()
                        ?: DslError("COMPILATION_ERROR", "Compilation failed")
                    
                    val stats = DslExecutionStats(
                        dslType = engine.engineId,
                        executionId = executionId,
                        startTime = startEvent.timestamp,
                        endTime = java.time.Instant.now(),
                        errorsCount = 1
                    )
                    executionStats[executionId] = stats
                    
                    val failedEvent = dev.rubentxu.pipeline.events.DslExecutionFailed(
                                pipelineId = executionId,
                                correlationId = executionId,
                        dslType = engine.engineId,
                        executionId = executionId,
                        error = error.message,
                        cause = error.cause?.message
                    )
                    eventBus.publish(failedEvent)
                    
                    return DslExecutionResult.Failure(error)
                }
            }
            
        } finally {
            activeExecutions.remove(executionId)
        }
    }
}

/**
 * Represents an active DSL execution.
 */
private data class DslExecution<TResult : Any>(
    val executionId: String,
    val engine: DslEngine<TResult>,
    val scriptName: String,
    private var job: Job? = null
) {
    fun cancel() {
        job?.cancel()
    }
}

/**
 * Report containing information about the DSL manager state.
 */
data class DslManagerReport(
    val registeredEngines: Int,
    val supportedExtensions: Set<String>,
    val availableCapabilities: Set<DslCapability>,
    val activeExecutions: Int,
    val totalExecutions: Int,
    val engines: List<EngineReport>
) {
    fun getFormattedReport(): String {
        return buildString {
            appendLine("DSL Manager Report")
            appendLine("==================")
            appendLine("Registered Engines: $registeredEngines")
            appendLine("Supported Extensions: ${supportedExtensions.joinToString(", ")}")
            appendLine("Available Capabilities: ${availableCapabilities.joinToString(", ")}")
            appendLine("Active Executions: $activeExecutions")
            appendLine("Total Executions: $totalExecutions")
            appendLine()
            
            if (engines.isNotEmpty()) {
                appendLine("Engine Details:")
                engines.forEach { engine ->
                    appendLine("- ${engine.engineName} (${engine.engineId}) v${engine.version}")
                    appendLine("  Extensions: ${engine.supportedExtensions.joinToString(", ")}")
                    appendLine("  Capabilities: ${engine.capabilities.joinToString(", ")}")
                    appendLine("  Executions: ${engine.executionCount}")
                }
            }
        }
    }
}

/**
 * Report about a specific engine.
 */
data class EngineReport(
    val engineId: String,
    val engineName: String,
    val version: String,
    val supportedExtensions: Set<String>,
    val capabilities: Set<DslCapability>,
    val executionCount: Int
)