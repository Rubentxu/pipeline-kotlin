package dev.rubentxu.pipeline.steps.registry

import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel
import dev.rubentxu.pipeline.context.IServiceLocator
import dev.rubentxu.pipeline.context.steps.StepContextBridge
import dev.rubentxu.pipeline.context.steps.UnifiedStepContext
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.isAccessible

/**
 * Phase 4: Unified Step Registry for Enhanced @Step Functions
 * 
 * This registry manages @Step functions that use the new UnifiedStepContext
 * and provides enhanced capabilities:
 * - Automatic context injection
 * - Enhanced metadata management  
 * - Security level validation
 * - Performance monitoring
 * - Error handling and recovery
 */

/**
 * Enhanced metadata for @Step functions
 */
data class EnhancedStepMetadata(
    val name: String,
    val description: String,
    val category: StepCategory,
    val securityLevel: SecurityLevel,
    val function: KFunction<*>,
    val parameterTypes: List<Class<*>>,
    val returnType: Class<*>,
    val isSuspending: Boolean,
    val registrationTime: Long = System.currentTimeMillis()
) {
    
    /**
     * Execute step function with automatic context injection
     */
    suspend fun execute(serviceLocator: IServiceLocator, vararg args: Any?): Any? {
        return StepContextBridge.executeStepFunction(serviceLocator) {
            if (isSuspending) {
                function.callSuspend(*args)
            } else {
                function.call(*args)
            }
        }
    }
    
    /**
     * Validate that arguments match expected parameter types
     */
    fun validateArguments(args: Array<out Any?>): Boolean {
        if (args.size != parameterTypes.size) {
            return false
        }
        
        return args.zip(parameterTypes).all { (arg, expectedType) ->
            arg == null || expectedType.isAssignableFrom(arg::class.java)
        }
    }
}

/**
 * Enhanced step registry with advanced capabilities
 */
class UnifiedStepRegistry {
    
    private val steps = mutableMapOf<String, EnhancedStepMetadata>()
    private val stepsByCategory = mutableMapOf<StepCategory, MutableList<EnhancedStepMetadata>>()
    private val executionStats = mutableMapOf<String, StepExecutionStats>()
    
    /**
     * Register a @Step function
     */
    fun registerStep(function: KFunction<*>) {
        val stepAnnotation = function.findAnnotation<Step>() 
            ?: throw IllegalArgumentException("Function ${function.name} is not annotated with @Step")
        
        val metadata = EnhancedStepMetadata(
            name = if (stepAnnotation.name.isBlank()) function.name else stepAnnotation.name,
            description = stepAnnotation.description,
            category = stepAnnotation.category,
            securityLevel = stepAnnotation.securityLevel,
            function = function.also { it.isAccessible = true },
            parameterTypes = function.parameters.drop(1).map { 
                (it.type.classifier as kotlin.reflect.KClass<*>).java
            },
            returnType = (function.returnType.classifier as? kotlin.reflect.KClass<*>)?.java ?: Any::class.java,
            isSuspending = function.isSuspend
        )
        
        // Register in main registry
        steps[metadata.name] = metadata
        
        // Register by category
        stepsByCategory.computeIfAbsent(metadata.category) { mutableListOf() }.add(metadata)
        
        // Initialize execution stats
        executionStats[metadata.name] = StepExecutionStats(metadata.name)
    }
    
    /**
     * Auto-discover and register @Step functions from a class or package
     */
    fun autoRegister(clazz: Class<*>) {
        clazz.kotlin.functions
            .filter { function -> function.findAnnotation<Step>() != null }
            .forEach { function -> registerStep(function) }
    }
    
    /**
     * Execute a step by name with automatic context injection
     */
    suspend fun executeStep(
        stepName: String,
        serviceLocator: IServiceLocator,
        vararg args: Any?
    ): Any? {
        val metadata = steps[stepName] 
            ?: throw IllegalArgumentException("Step '$stepName' not found")
        
        // Validate arguments
        if (!metadata.validateArguments(args)) {
            throw IllegalArgumentException("Invalid arguments for step '$stepName'")
        }
        
        // Record execution start
        val stats = executionStats[stepName]!!
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = metadata.execute(serviceLocator, *args)
            
            // Record successful execution
            val executionTime = System.currentTimeMillis() - startTime
            stats.recordSuccess(executionTime)
            
            result
        } catch (exception: Exception) {
            // Record failed execution
            val executionTime = System.currentTimeMillis() - startTime
            stats.recordFailure(exception, executionTime)
            
            throw exception
        }
    }
    
    /**
     * Execute a step synchronously (for non-suspending functions)
     */
    fun executeStepSync(
        stepName: String,
        serviceLocator: IServiceLocator,
        vararg args: Any?
    ): Any? = runBlocking {
        executeStep(stepName, serviceLocator, *args)
    }
    
    /**
     * Get step metadata
     */
    fun getStep(stepName: String): EnhancedStepMetadata? = steps[stepName]
    
    /**
     * Get all registered steps
     */
    fun getAllSteps(): List<EnhancedStepMetadata> = steps.values.toList()
    
    /**
     * Get steps by category
     */
    fun getStepsByCategory(category: StepCategory): List<EnhancedStepMetadata> = 
        stepsByCategory[category]?.toList() ?: emptyList()
    
    /**
     * Get steps by security level
     */
    fun getStepsBySecurityLevel(securityLevel: SecurityLevel): List<EnhancedStepMetadata> = 
        steps.values.filter { it.securityLevel == securityLevel }
    
    /**
     * Get execution statistics
     */
    fun getExecutionStats(stepName: String): StepExecutionStats? = 
        executionStats[stepName]
    
    /**
     * Get all execution statistics
     */
    fun getAllExecutionStats(): Map<String, StepExecutionStats> = 
        executionStats.toMap()
    
    /**
     * Check if step exists
     */
    fun hasStep(stepName: String): Boolean = steps.containsKey(stepName)
    
    /**
     * Clear all registered steps (for testing)
     */
    fun clear() {
        steps.clear()
        stepsByCategory.clear()
        executionStats.clear()
    }
    
    /**
     * Get registry statistics
     */
    fun getRegistryStats(): RegistryStats {
        return RegistryStats(
            totalSteps = steps.size,
            stepsByCategory = stepsByCategory.mapValues { it.value.size },
            stepsBySecurityLevel = SecurityLevel.entries.associateWith { level ->
                steps.values.count { it.securityLevel == level }
            },
            totalExecutions = executionStats.values.sumOf { it.totalExecutions },
            totalFailures = executionStats.values.sumOf { it.totalFailures }
        )
    }
}

/**
 * Execution statistics for individual steps
 */
data class StepExecutionStats(
    val stepName: String,
    var totalExecutions: Long = 0,
    var totalSuccesses: Long = 0,
    var totalFailures: Long = 0,
    var totalExecutionTime: Long = 0,
    var minExecutionTime: Long = Long.MAX_VALUE,
    var maxExecutionTime: Long = 0,
    var lastExecutionTime: Long? = null,
    var lastException: Exception? = null,
    var lastExceptionTime: Long? = null
) {
    
    fun recordSuccess(executionTime: Long) {
        totalExecutions++
        totalSuccesses++
        totalExecutionTime += executionTime
        minExecutionTime = minOf(minExecutionTime, executionTime)
        maxExecutionTime = maxOf(maxExecutionTime, executionTime)
        lastExecutionTime = System.currentTimeMillis()
    }
    
    fun recordFailure(exception: Exception, executionTime: Long) {
        totalExecutions++
        totalFailures++
        totalExecutionTime += executionTime
        minExecutionTime = minOf(minExecutionTime, executionTime)
        maxExecutionTime = maxOf(maxExecutionTime, executionTime)
        lastException = exception
        lastExceptionTime = System.currentTimeMillis()
    }
    
    val averageExecutionTime: Long
        get() = if (totalExecutions > 0) totalExecutionTime / totalExecutions else 0
    
    val successRate: Double
        get() = if (totalExecutions > 0) (totalSuccesses.toDouble() / totalExecutions) * 100 else 0.0
}

/**
 * Registry-level statistics
 */
data class RegistryStats(
    val totalSteps: Int,
    val stepsByCategory: Map<StepCategory, Int>,
    val stepsBySecurityLevel: Map<SecurityLevel, Int>,
    val totalExecutions: Long,
    val totalFailures: Long
) {
    val overallSuccessRate: Double
        get() = if (totalExecutions > 0) ((totalExecutions - totalFailures).toDouble() / totalExecutions) * 100 else 0.0
}

/**
 * Global registry instance
 */
object GlobalUnifiedStepRegistry {
    private val registry = UnifiedStepRegistry()
    
    init {
        // Auto-register built-in enhanced steps - will be done separately during initialization
        // autoRegister(dev.rubentxu.pipeline.phase4.steps.EnhancedBuiltinStepsKt::class.java)
    }
    
    /**
     * Convenient access to execute steps
     */
    suspend fun execute(stepName: String, serviceLocator: IServiceLocator, vararg args: Any?): Any? {
        return registry.executeStep(stepName, serviceLocator, *args)
    }
    
    /**
     * Convenient synchronous execution
     */
    fun executeSync(stepName: String, serviceLocator: IServiceLocator, vararg args: Any?): Any? {
        return registry.executeStepSync(stepName, serviceLocator, *args)
    }
    
    /**
     * Register a step function
     */
    fun registerStep(function: KFunction<*>) = registry.registerStep(function)
    
    /**
     * Auto-register steps from class
     */
    fun autoRegister(clazz: Class<*>) = registry.autoRegister(clazz)
    
    /**
     * Get step metadata
     */
    fun getStep(stepName: String) = registry.getStep(stepName)
    
    /**
     * Check if step exists
     */
    fun hasStep(stepName: String) = registry.hasStep(stepName)
    
    /**
     * Get all steps
     */
    fun getAllSteps() = registry.getAllSteps()
    
    /**
     * Get steps by category
     */
    fun getStepsByCategory(category: StepCategory) = registry.getStepsByCategory(category)
    
    /**
     * Get steps by security level
     */
    fun getStepsBySecurityLevel(securityLevel: SecurityLevel) = registry.getStepsBySecurityLevel(securityLevel)
    
    /**
     * Get execution statistics
     */
    fun getExecutionStats(stepName: String) = registry.getExecutionStats(stepName)
    
    /**
     * Get all execution statistics
     */
    fun getAllExecutionStats() = registry.getAllExecutionStats()
    
    /**
     * Get registry statistics
     */
    fun getRegistryStats() = registry.getRegistryStats()
    
    /**
     * Clear registry (for testing)
     */
    fun clear() = registry.clear()
}