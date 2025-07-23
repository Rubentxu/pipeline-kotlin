package dev.rubentxu.pipeline.security

import dev.rubentxu.pipeline.dsl.DslExecutionContext
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Process-level sandbox implementation for maximum security isolation
 * 
 * Executes scripts in separate JVM processes with strict resource controls
 * and limited file system access.
 */
class ProcessLevelSandbox(
    private val logger: ILogger
) : ScriptExecutionSandbox {
    
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val tempDirectories = ConcurrentHashMap<String, File>()
    
    override fun <T> executeInSandbox(
        scriptContent: String,
        scriptName: String,
        executionContext: DslExecutionContext,
        compilationConfig: ScriptCompilationConfiguration,
        evaluationConfig: ScriptEvaluationConfiguration
    ): SandboxExecutionResult<T> {
        
        val isolationId = generateIsolationId(scriptName)
        logger.info("Creating process-level sandbox for script: $scriptName (ID: $isolationId)")
        
        return try {
            val tempDir = createSandboxedDirectory(isolationId)
            tempDirectories[isolationId] = tempDir
            
            val scriptFile = prepareScriptFile(tempDir, scriptContent, scriptName)
            val process = launchSandboxedProcess(scriptFile, executionContext, isolationId)
            
            activeProcesses[isolationId] = process
            
            val result = monitorAndWaitForProcess<T>(process, executionContext, isolationId)
            
            SandboxExecutionResult.Success(
                result = result,
                isolationId = isolationId,
                resourceUsage = collectProcessResourceUsage(process),
                executionTime = System.currentTimeMillis() // TODO: Measure actual execution time
            )
            
        } catch (e: Exception) {
            logger.error("Process sandbox execution failed for script $scriptName: ${e.message}")
            SandboxExecutionResult.Failure(
                error = e,
                isolationId = isolationId,
                reason = "Process execution failed: ${e.message}"
            )
        } finally {
            cleanupProcess(isolationId)
        }
    }
    
    override fun terminateExecution(isolationId: String): Boolean {
        return try {
            val process = activeProcesses[isolationId]
            if (process != null && process.isAlive) {
                logger.info("Terminating process for isolate: $isolationId")
                
                // Try graceful termination first
                process.destroy()
                
                // Force kill if necessary
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    logger.warn("Force killed process for isolate: $isolationId")
                }
                
                activeProcesses.remove(isolationId)
                true
            } else {
                logger.warn("No active process found for isolate: $isolationId")
                false
            }
        } catch (e: Exception) {
            logger.error("Failed to terminate process for isolate $isolationId: ${e.message}")
            false
        }
    }
    
    override fun getResourceUsage(isolationId: String): SandboxResourceUsage? {
        val process = activeProcesses[isolationId] ?: return null
        return collectProcessResourceUsage(process)
    }
    
    override fun cleanup() {
        logger.info("Shutting down process sandbox - cleaning up ${activeProcesses.size} active processes")
        
        activeProcesses.keys.forEach { isolationId ->
            terminateExecution(isolationId)
        }
        
        // Clean up temporary directories
        tempDirectories.values.forEach { tempDir ->
            try {
                tempDir.deleteRecursively()
            } catch (e: Exception) {
                logger.warn("Failed to delete temp directory ${tempDir.absolutePath}: ${e.message}")
            }
        }
        tempDirectories.clear()
        
        logger.info("Process sandbox cleanup complete")
    }
    
    private fun createSandboxedDirectory(isolationId: String): File {
        val tempDir = Files.createTempDirectory("pipeline-sandbox-$isolationId").toFile()
        
        // Set restrictive permissions on Unix-like systems
        try {
            if (System.getProperty("os.name").lowercase().contains("nix") || 
                System.getProperty("os.name").lowercase().contains("mac")) {
                
                val permissions = PosixFilePermissions.fromString("rwx------") // Only owner access
                Files.setPosixFilePermissions(tempDir.toPath(), permissions)
            }
        } catch (e: Exception) {
            logger.warn("Could not set restrictive permissions on temp directory: ${e.message}")
        }
        
        logger.debug("Created sandboxed directory: ${tempDir.absolutePath}")
        return tempDir
    }
    
    private fun prepareScriptFile(tempDir: File, scriptContent: String, scriptName: String): File {
        val scriptFile = File(tempDir, "$scriptName.kts")
        
        // Wrap the script with security monitoring
        val wrappedScript = """
            @file:Repository("https://repo1.maven.org/maven2/")
            @file:DependsOn("org.jetbrains.kotlin:kotlin-scripting-jvm:2.0.0")
            
            import java.io.File
            import java.lang.management.ManagementFactory
            
            // Security monitoring wrapper
            class SecurityMonitor {
                private val startTime = System.currentTimeMillis()
                private val startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                
                fun checkResourceLimits() {
                    val currentMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                    val memoryUsed = currentMemory - startMemory
                    
                    if (memoryUsed > 512 * 1024 * 1024) { // 512MB limit
                        throw SecurityException("Memory limit exceeded: ${'$'}{memoryUsed / 1024 / 1024}MB")
                    }
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    if (executionTime > 30_000) { // 30 second limit
                        throw SecurityException("Execution time limit exceeded: ${'$'}{executionTime}ms")
                    }
                }
            }
            
            val securityMonitor = SecurityMonitor()
            
            try {
                // Original script content
                $scriptContent
                
                securityMonitor.checkResourceLimits()
                
            } catch (e: SecurityException) {
                System.err.println("SECURITY_VIOLATION: ${'$'}{e.message}")
                System.exit(1)
            } catch (e: Exception) {
                System.err.println("SCRIPT_ERROR: ${'$'}{e.message}")
                System.exit(2)
            }
        """.trimIndent()
        
        scriptFile.writeText(wrappedScript)
        
        // Set read-only permissions
        scriptFile.setReadOnly()
        
        logger.debug("Prepared script file: ${scriptFile.absolutePath}")
        return scriptFile
    }
    
    private fun launchSandboxedProcess(
        scriptFile: File,
        executionContext: DslExecutionContext,
        isolationId: String
    ): Process {
        
        val javaHome = System.getProperty("java.home")

        
        val command = mutableListOf<String>().apply {
            add("$javaHome/bin/java")
            
            // JVM security and resource options
            add("-Djava.security.manager=default")
            add("-Djava.security.policy=all.policy") // Would need custom policy file
            add("-Dfile.encoding=UTF-8")
            
            // Memory limits
            executionContext.resourceLimits?.maxMemoryMb?.let { mb ->
                add("-Xmx${mb}m")
                add("-Xms${mb/4}m") // Start with 1/4 of max memory
            } ?: run {
                add("-Xmx512m") // Default limit
                add("-Xms128m")
            }
            
            // Classpath for Kotlin scripting
            add("-cp")
            add(buildKotlinScriptClasspath())
            
            // Kotlin script runner
            add("kotlin.script.experimental.jvm.runner.MainKt")
            add(scriptFile.absolutePath)
        }
        
        val processBuilder = ProcessBuilder(command).apply {
            directory(executionContext.workingDirectory)
            
            // Set environment variables with restrictions
            environment().clear() // Start with clean environment
            environment().putAll(filterEnvironmentVariables(executionContext.environmentVariables))
            
            // Redirect error stream for monitoring
            redirectErrorStream(true)
        }
        
        logger.info("Launching sandboxed process for isolate $isolationId with command: ${command.joinToString(" ")}")
        
        return try {
            processBuilder.start()
        } catch (e: IOException) {
            throw SecurityException("Failed to launch sandboxed process: ${e.message}", e)
        }
    }
    
    private fun <T> monitorAndWaitForProcess(
        process: Process,
        executionContext: DslExecutionContext,
        isolationId: String
    ): T {
        
        val timeout = executionContext.resourceLimits?.maxWallTimeMs ?: 60_000L // Default 1 minute
        
        return try {
            val completed = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                throw SecurityViolationException(
                    "Process execution timed out after ${timeout}ms",
                    SecurityViolationType.EXECUTION_TIMEOUT,
                    isolationId
                )
            }
            
            val exitCode = process.exitValue()
            val output = process.inputStream.bufferedReader().readText()
            
            when (exitCode) {
                0 -> {
                    logger.info("Process completed successfully for isolate $isolationId")
                    @Suppress("UNCHECKED_CAST")
                    output.trim() as T // Return output as result
                }
                1 -> {
                    throw SecurityViolationException(
                        "Security violation detected in script execution",
                        SecurityViolationType.MALICIOUS_CODE_DETECTED,
                        isolationId
                    )
                }
                2 -> {
                    throw Exception("Script execution error: $output")
                }
                else -> {
                    throw Exception("Process exited with unexpected code $exitCode: $output")
                }
            }
            
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw SecurityException("Process execution was interrupted", e)
        }
    }
    
    private fun collectProcessResourceUsage(process: Process): SandboxResourceUsage {
        // Note: ProcessHandle.Info provides limited resource information
        // For production use, you'd want to integrate with system monitoring tools
        
        val processHandle = process.toHandle()
        val info = processHandle.info()
        
        return SandboxResourceUsage(
            memoryUsedBytes = 0, // Would need platform-specific implementation
            cpuTimeMs = info.totalCpuDuration().map { it.toMillis() }.orElse(0),
            wallTimeMs = info.startInstant().map { 
                System.currentTimeMillis() - it.toEpochMilli() 
            }.orElse(0),
            threadsCreated = 1, // Process-level execution = 1 main thread
            filesAccessed = emptyList(), // Would need file system monitoring
            networkConnections = emptyList() // Would need network monitoring
        )
    }
    
    private fun filterEnvironmentVariables(variables: Map<String, String>): Map<String, String> {
        val allowedPrefixes = setOf("PIPELINE_", "USER_", "CUSTOM_")
        val blockedVars = setOf("PATH", "JAVA_HOME", "CLASSPATH", "LD_LIBRARY_PATH")
        
        return variables.filterKeys { key ->
            val upperKey = key.uppercase()
            allowedPrefixes.any { upperKey.startsWith(it) } && !blockedVars.contains(upperKey)
        }
    }
    
    private fun findKotlinCompiler(): String {
        // Try to find Kotlin compiler in common locations
        val kotlinHome = System.getenv("KOTLIN_HOME")
        if (kotlinHome != null) {
            val kotlinc = File(kotlinHome, "bin/kotlinc")
            if (kotlinc.exists()) return kotlinc.absolutePath
        }
        
        // Fallback to system PATH
        return "kotlinc"
    }
    
    private fun buildKotlinScriptClasspath(): String {
        // Build classpath for Kotlin scripting runtime
        val kotlinStdlib = System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .find { it.contains("kotlin-stdlib") }
        
        return kotlinStdlib ?: System.getProperty("java.class.path")
    }
    
    private fun generateIsolationId(scriptName: String): String {
        return "process-${scriptName.replace("[^a-zA-Z0-9]".toRegex(), "-")}-${System.currentTimeMillis()}"
    }
    
    private fun cleanupProcess(isolationId: String) {
        activeProcesses.remove(isolationId)
        tempDirectories.remove(isolationId)?.let { tempDir ->
            try {
                tempDir.deleteRecursively()
                logger.debug("Cleaned up temp directory for isolate: $isolationId")
            } catch (e: Exception) {
                logger.warn("Error cleaning up temp directory for $isolationId: ${e.message}")
            }
        }
    }
}