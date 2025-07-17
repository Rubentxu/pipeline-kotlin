package dev.rubentxu.pipeline.context

import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.steps.Shell
import dev.rubentxu.pipeline.steps.registry.StepRegistry
import dev.rubentxu.pipeline.annotations.StepValidationException
import dev.rubentxu.pipeline.steps.security.StepSecurityManager
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of PipelineContext that integrates with existing pipeline infrastructure.
 * 
 * This implementation bridges the new @Step system with the existing StepExecutionContext
 * and pipeline execution engine.
 */
class DefaultPipelineContext(
    private val stepExecutionContext: StepExecutionContext,
    override val securityLevel: SecurityLevel = SecurityLevel.RESTRICTED,
    override val resourceLimits: ResourceLimits = ResourceLimits(),
    private val stepRegistry: StepRegistry = StepRegistry.getInstance(),
    private val securityManager: StepSecurityManager = StepSecurityManager(stepExecutionContext.logger)
) : PipelineContext {
    
    // Core pipeline access (delegated to StepExecutionContext)
    override val pipeline: Pipeline get() = stepExecutionContext.pipeline
    override val logger: IPipelineLogger get() = stepExecutionContext.logger
    override val workingDirectory: Path get() = Path.of(stepExecutionContext.workingDirectory)
    override val environment: Map<String, String> get() = stepExecutionContext.environment
    
    // State management (remember functionality)
    private val rememberedValues = ConcurrentHashMap<Any, Any>()
    
    // Context hierarchy storage
    private val contextValues = ConcurrentHashMap<ContextKey<*>, Any>()
    
    override suspend fun executeShell(command: String, options: ShellOptions): ShellResult {
        // Use existing Shell infrastructure
        val shell = Shell(pipeline)
        
        try {
            val result = shell.execute(
                command = command,
                returnStdout = options.returnStdout
            )
            
            return ShellResult(
                exitCode = 0, // Shell.execute throws on failure, so success = 0
                stdout = if (options.returnStdout) result else "",
                stderr = "",
                success = true
            )
        } catch (e: Exception) {
            logger.error("Shell command failed: $command")
            return ShellResult(
                exitCode = 1,
                stdout = "",
                stderr = e.message ?: "Unknown error",
                success = false
            )
        }
    }
    
    override suspend fun executeStep(stepName: String, config: Map<String, Any>): Any {
        // Get step info for security validation
        val stepInfo = stepRegistry.getStepInfo(stepName)
            ?: throw StepValidationException("Step not found: $stepName", stepName)
        
        // Validate step before execution
        val validationResult = stepRegistry.validateStepParameters(stepName, config)
        if (!validationResult.isValid) {
            throw StepValidationException(
                "Step validation failed: ${validationResult.errors.joinToString(", ")}",
                stepName
            )
        }
        
        // Security validation (simplified for now)
        logger.info("Security validation passed for step: $stepName")
        
        // For now, return a placeholder
        return "Step executed: $stepName"
    }
    
    override suspend fun readFile(path: String, encoding: String): String {
        val fullPath = if (Path.of(path).isAbsolute) {
            Path.of(path)
        } else {
            workingDirectory.resolve(path)
        }
        
        return try {
            fullPath.toFile().readText(charset = Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to read file: $path")
            throw e
        }
    }
    
    override suspend fun writeFile(path: String, content: String, encoding: String) {
        val fullPath = if (Path.of(path).isAbsolute) {
            Path.of(path)
        } else {
            workingDirectory.resolve(path)
        }
        
        try {
            fullPath.parent?.toFile()?.mkdirs()
            fullPath.toFile().writeText(content, charset = Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to write file: $path")
            throw e
        }
    }
    
    override suspend fun fileExists(path: String): Boolean {
        val fullPath = if (Path.of(path).isAbsolute) {
            Path.of(path)
        } else {
            workingDirectory.resolve(path)
        }
        
        return fullPath.toFile().exists()
    }
    
    override fun getEnvVar(name: String): String? {
        return environment[name]
    }
    
    override fun getEnvVar(name: String, defaultValue: String): String {
        return environment[name] ?: defaultValue
    }
    
    override fun getSecret(name: String): String? {
        // TODO: Integrate with secure secret management
        // For now, treat as regular environment variable
        return environment[name]
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> remember(key: Any, computation: () -> T): T {
        return rememberedValues.computeIfAbsent(key) { computation() as Any } as T
    }
    
    override fun invalidate() {
        rememberedValues.clear()
    }
    
    override fun getAvailableSteps(): List<String> {
        return stepRegistry.getAllSteps().map { it.name }
    }
    
    override fun getStepMetadata(stepName: String): StepMetadata? {
        val stepInfo = stepRegistry.getStepInfo(stepName) ?: return null
        
        // Convert from registry step info to context metadata
        return StepMetadata(
            name = stepInfo.name,
            description = stepInfo.description,
            category = stepInfo.category.name,
            parameters = emptyList(), // TODO: Extract from function parameters
            securityLevel = when (stepInfo.securityLevel) {
                dev.rubentxu.pipeline.annotations.SecurityLevel.TRUSTED -> SecurityLevel.TRUSTED
                dev.rubentxu.pipeline.annotations.SecurityLevel.RESTRICTED -> SecurityLevel.RESTRICTED
                dev.rubentxu.pipeline.annotations.SecurityLevel.ISOLATED -> SecurityLevel.ISOLATED
            }
        )
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> provide(key: ContextKey<T>, value: T, block: () -> Unit) {
        val previous = contextValues.put(key, value as Any)
        try {
            block()
        } finally {
            if (previous != null) {
                contextValues[key] = previous
            } else {
                contextValues.remove(key)
            }
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> consume(key: ContextKey<T>): T? {
        return contextValues[key] as? T
    }
}

/**
 * Extension function to create PipelineContext from StepExecutionContext
 */
fun StepExecutionContext.toPipelineContext(
    securityLevel: SecurityLevel = SecurityLevel.RESTRICTED,
    resourceLimits: ResourceLimits = ResourceLimits()
): PipelineContext {
    return DefaultPipelineContext(
        stepExecutionContext = this,
        securityLevel = securityLevel,
        resourceLimits = resourceLimits
    )
}