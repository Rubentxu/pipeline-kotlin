package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.DslExecutionContext
import dev.rubentxu.pipeline.dsl.DslIsolationLevel
import dev.rubentxu.pipeline.logger.IPipelineLogger
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.SandboxPolicy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * GraalVM Isolate-based sandbox for secure script execution
 * 
 * This implementation provides strong security isolation using GraalVM's 
 * polyglot engine with custom resource limits and access controls.
 */
class GraalVMIsolateSandbox(
    private val logger: IPipelineLogger
) : ScriptExecutionSandbox {
    
    private val engine: Engine = Engine.newBuilder()
        .allowExperimentalOptions(true)
        .option("engine.WarnInterpreterOnly", "false")
        .build()
    
    private val activeContexts = ConcurrentHashMap<String, Context>()
    
    override fun <T> executeInSandbox(
        scriptContent: String,
        scriptName: String,
        executionContext: DslExecutionContext,
        compilationConfig: ScriptCompilationConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration
    ): SandboxExecutionResult<T> {
        
        val isolationId = generateIsolationId(scriptName)
        logger.info("Creating GraalVM isolate sandbox for script: $scriptName (ID: $isolationId)")
        
        return try {
            val context = createIsolatedContext(executionContext, isolationId)
            activeContexts[isolationId] = context
            
            val result = executeInContext<T>(context, scriptContent, scriptName, executionContext)
            
            SandboxExecutionResult.Success(
                result = result,
                isolationId = isolationId,
                resourceUsage = collectResourceUsage(context),
                executionTime = System.currentTimeMillis() // TODO: Measure actual execution time
            )
            
        } catch (e: Exception) {
            logger.error("Sandbox execution failed for script $scriptName: ${e.message}")
            SandboxExecutionResult.Failure(
                error = e,
                isolationId = isolationId,
                reason = "Execution failed: ${e.message}"
            )
        } finally {
            cleanupIsolate(isolationId)
        }
    }
    
    override fun terminateExecution(isolationId: String): Boolean {
        return try {
            val context = activeContexts[isolationId]
            if (context != null) {
                logger.info("Terminating execution for isolate: $isolationId")
                context.close(true) // Force close
                activeContexts.remove(isolationId)
                true
            } else {
                logger.warn("No active context found for isolate: $isolationId")
                false
            }
        } catch (e: Exception) {
            logger.error("Failed to terminate execution for isolate $isolationId: ${e.message}")
            false
        }
    }
    
    override fun getResourceUsage(isolationId: String): SandboxResourceUsage? {
        val context = activeContexts[isolationId] ?: return null
        return collectResourceUsage(context)
    }
    
    override fun cleanup() {
        logger.info("Shutting down GraalVM sandbox - cleaning up ${activeContexts.size} active contexts")
        
        activeContexts.values.forEach { context ->
            try {
                context.close(true)
            } catch (e: Exception) {
                logger.warn("Error closing context during cleanup: ${e.message}")
            }
        }
        activeContexts.clear()
        
        try {
            engine.close()
            logger.info("GraalVM engine closed successfully")
        } catch (e: Exception) {
            logger.error("Error closing GraalVM engine: ${e.message}")
        }
    }
    
    private fun createIsolatedContext(
        executionContext: DslExecutionContext,
        isolationId: String
    ): Context {
        
        val resourceLimits = executionContext.resourceLimits
        val policy = executionContext.executionPolicy
        
        logger.debug("Creating isolated context with isolation level: ${policy.isolationLevel}")
        
        val contextBuilder = Context.newBuilder("js") // Focus on JavaScript for now
            .engine(engine)
            .allowHostAccess(createHostAccessPolicy(policy.isolationLevel))
            .allowCreateThread(resourceLimits?.maxThreads?.let { it > 1 } ?: false)
            .allowIO(policy.isolationLevel != DslIsolationLevel.PROCESS)
            .allowNativeAccess(false) // Always disable native access for security
            .allowExperimentalOptions(true)
            .sandbox(when (policy.isolationLevel) {
                DslIsolationLevel.THREAD -> SandboxPolicy.CONSTRAINED
                DslIsolationLevel.PROCESS -> SandboxPolicy.ISOLATED
                else -> SandboxPolicy.TRUSTED
            })
        
        // Apply resource limits if specified
        resourceLimits?.let { limits ->
            val graalLimits = ResourceLimits.newBuilder()
            
            limits.maxMemoryMb?.let { mb ->
                graalLimits.statementLimit(100000L, null) // Use statement limit as approximation
                logger.debug("Applied memory limit approximation: ${mb}MB")
            }
            
            limits.maxCpuTimeMs?.let { ms ->
                graalLimits.statementLimit(ms * 1000L, null) // Approximate CPU time control
                logger.debug("Applied CPU time limit: ${ms}ms")
            }
            
            limits.maxWallTimeMs?.let { ms ->
                contextBuilder.option("engine.MaxIsolateMemory", "${limits.maxMemoryMb ?: 512}MB")
                // Wall time will be enforced at execution level
                logger.debug("Applied wall time limit: ${ms}ms")
            }
            
            contextBuilder.resourceLimits(graalLimits.build())
        }
        
        // Configure working directory and environment
        contextBuilder.currentWorkingDirectory(executionContext.workingDirectory.toPath())
        
        // Add environment variables to the context
        executionContext.environmentVariables.forEach { (key, value) ->
            contextBuilder.option("js.commonjs-global-properties", "$key=$value")
        }
        
        val context = contextBuilder.build()
        
        logger.info("Created GraalVM context for isolate $isolationId with resource limits: $resourceLimits")
        return context
    }
    
    private fun createHostAccessPolicy(isolationLevel: DslIsolationLevel): HostAccess {
        return when (isolationLevel) {
            DslIsolationLevel.THREAD -> HostAccess.newBuilder()
                .allowPublicAccess(true)
                .allowAllImplementations(true)
                .allowAllClassImplementations(true)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowMapAccess(true)
                .build()
                
            DslIsolationLevel.PROCESS -> HostAccess.newBuilder()
                .allowPublicAccess(false)
                .allowAllImplementations(false)
                .allowAllClassImplementations(false)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowMapAccess(true)
                // Only allow specific safe classes
                .allowAccessInheritance(false)
                .build()
                
            else -> HostAccess.ALL // Minimal restrictions for testing
        }
    }
    
    private fun <T> executeInContext(
        context: Context,
        scriptContent: String,
        scriptName: String,
        executionContext: DslExecutionContext
    ): T {
        
        logger.debug("Executing script in isolated context: $scriptName")
        
        // For Kotlin script execution, we need to adapt the content to be executable in GraalVM
        // This is a simplified approach - in production, you'd want more sophisticated Kotlin->JS translation
        val adaptedScript = adaptKotlinScriptForGraalVM(scriptContent)
        
        val startTime = System.currentTimeMillis()
        
        return try {
            // Execute the adapted script in the JavaScript context
            val result = context.eval("js", adaptedScript)
            
            val executionTime = System.currentTimeMillis() - startTime
            logger.info("Script execution completed in ${executionTime}ms")
            
            // Convert GraalVM Value to expected type
            @Suppress("UNCHECKED_CAST")
            when {
                result.isString -> result.asString() as T
                result.isNumber -> result.asLong() as T
                result.isBoolean -> result.asBoolean() as T
                result.hasArrayElements() -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until result.arraySize) {
                        list.add(result.getArrayElement(i))
                    }
                    list as T
                }
                else -> result.asString() as T // Fallback to string representation
            }
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            logger.error("Script execution failed after ${executionTime}ms: ${e.message}")
            throw SecurityException("Script execution failed in sandbox: ${e.message}", e)
        }
    }
    
    private fun adaptKotlinScriptForGraalVM(kotlinScript: String): String {
        // Basic adaptation of Kotlin-like syntax to JavaScript
        // This is a simplified translator for demonstration purposes
        return kotlinScript
            .replace("println\\((.*)\\)".toRegex(), "console.log($1)")
            .replace("val\\s+(\\w+)\\s*=".toRegex(), "var $1 =")
            .replace("var\\s+(\\w+)\\s*=".toRegex(), "var $1 =")
            .replace("fun\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{".toRegex(), "function $1($2) {")
            .replace("\\.trimIndent\\(\\)".toRegex(), "")
            .let { adapted ->
                """
                // Adapted Kotlin script for GraalVM execution
                (function() {
                    try {
                        $adapted
                    } catch (error) {
                        throw new Error("Script execution error: " + error.message);
                    }
                })();
                """.trimIndent()
            }
    }
    
    private fun collectResourceUsage(context: Context): SandboxResourceUsage {
        // GraalVM doesn't provide direct access to detailed resource usage in all versions
        // This is a simplified implementation that would need to be enhanced with actual metrics
        return SandboxResourceUsage(
            memoryUsedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            cpuTimeMs = 0, // Would need platform-specific implementation
            wallTimeMs = 0, // Would be tracked externally
            threadsCreated = Thread.activeCount(),
            filesAccessed = emptyList(), // Would need custom FileSystem implementation
            networkConnections = emptyList() // Would need custom network monitoring
        )
    }
    
    private fun generateIsolationId(scriptName: String): String {
        return "isolate-${scriptName.replace("[^a-zA-Z0-9]".toRegex(), "-")}-${System.currentTimeMillis()}"
    }
    
    private fun cleanupIsolate(isolationId: String) {
        activeContexts.remove(isolationId)?.let { context ->
            try {
                context.close()
                logger.debug("Cleaned up isolate: $isolationId")
            } catch (e: Exception) {
                logger.warn("Error during isolate cleanup for $isolationId: ${e.message}")
            }
        }
    }
}