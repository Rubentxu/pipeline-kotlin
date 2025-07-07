package dev.rubentxu.pipeline.cli.integration

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Helper class for executing real CLI commands using ProcessBuilder
 * Supports both direct execution and JAR-based execution with proper isolation
 */
class CliTestHelper : AutoCloseable {
    
    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 60L
        private val PROJECT_ROOT = File(System.getProperty("user.dir")).parentFile
        private val CLI_MODULE_DIR = File(PROJECT_ROOT, "pipeline-cli")
        private val BACKEND_MODULE_DIR = File(PROJECT_ROOT, "pipeline-backend")
        
        // Lock for synchronized access to JAR operations
        private val jarAccessLock = ReentrantLock()
    }
    
    // Temporary directory for this test instance
    private val tempDir: Path = Files.createTempDirectory("cli-test-")
    private val tempWorkingDir: File = tempDir.toFile()
    private var copiedJarFile: File? = null
    
    // Try to find the built JAR
    private fun findBuiltJar(): File? {
        // Check for CLI JAR (shadowJar creates pipeline-cli.jar)
        val cliJarFile = File(CLI_MODULE_DIR, "build/libs/pipeline-cli.jar")
        if (cliJarFile.exists()) {
            return cliJarFile
        }
        
        // Check for shadow JAR with -all suffix (if configured differently)
        val shadowJarFile = File(CLI_MODULE_DIR, "build/libs").listFiles { _, name ->
            name.startsWith("pipeline-cli") && name.endsWith("-all.jar")
        }?.firstOrNull()
        
        if (shadowJarFile != null) {
            return shadowJarFile
        }
        
        // Fallback to backend JAR
        val backendJarFile = File(BACKEND_MODULE_DIR, "build/libs").listFiles { _, name ->
            name.startsWith("pipeline-backend") && name.endsWith(".jar")
        }?.firstOrNull()
        
        return backendJarFile
    }
    
    init {
        // Copy test data to temporary directory for isolation
        setupTestEnvironment()
    }
    
    /**
     * Setup isolated test environment with copied resources
     */
    private fun setupTestEnvironment() {
        // Copy testData directory to temporary location
        val sourceTestData = File(CLI_MODULE_DIR, "testData")
        val targetTestData = File(tempWorkingDir, "testData")
        
        if (sourceTestData.exists()) {
            sourceTestData.copyRecursively(targetTestData, overwrite = true)
        }
        
        // Copy JAR to temporary directory for isolation
        copiedJarFile = copyJarToTempDir()
    }
    
    /**
     * Copy the CLI JAR to temporary directory to avoid file locking issues
     */
    private fun copyJarToTempDir(): File? {
        return jarAccessLock.withLock {
            val originalJar = findBuiltJar() ?: return@withLock null
            
            val copiedJar = File(tempWorkingDir, "pipeline-cli-${System.currentTimeMillis()}.jar")
            
            try {
                Files.copy(
                    originalJar.toPath(),
                    copiedJar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
                copiedJar
            } catch (e: Exception) {
                println("Warning: Failed to copy JAR to temp dir: ${e.message}")
                // Fallback to original JAR with retry logic
                originalJar
            }
        }
    }
    
    /**
     * Get the isolated JAR file for this test
     */
    private fun getIsolatedJar(): File {
        return copiedJarFile ?: throw IllegalStateException(
            "No JAR available. Run 'gradle shadowJar' first or check JAR copy operation"
        )
    }
    
    /**
     * Get the isolated working directory for this test
     */
    fun getWorkingDirectory(): File = tempWorkingDir
    
    /**
     * Cleanup temporary resources
     */
    override fun close() {
        try {
            tempDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            println("Warning: Failed to cleanup temp directory: ${e.message}")
        }
    }
    
    private fun findGradleExecutable(): String {
        // Check for gradle in PATH
        return try {
            val which = ProcessBuilder("which", "gradle")
                .redirectErrorStream(true)
                .start()
            which.waitFor(5, TimeUnit.SECONDS)
            if (which.exitValue() == 0) {
                "gradle"
            } else {
                "gradle" // fallback
            }
        } catch (e: Exception) {
            "gradle"
        }
    }
    
    /**
     * Result of CLI command execution
     */
    data class CliExecutionResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val executionTimeMs: Long
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        val isFailure: Boolean get() = exitCode != 0
    }
    
    /**
     * Execute CLI command using the isolated JAR
     */
    fun executeCliWithJar(
        command: String,
        vararg args: String,
        workingDir: File = tempWorkingDir,
        environmentVars: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): CliExecutionResult {
        val jarFile = getIsolatedJar()
        
        val fullCommand = listOf("java", "-jar", jarFile.absolutePath, command) + args
        return executeProcess(fullCommand, workingDir, environmentVars, timeoutSeconds)
    }
    
    /**
     * Execute CLI command using gradle run
     */
    fun executeCliWithGradle(
        command: String,
        vararg args: String,
        workingDir: File = tempWorkingDir,
        environmentVars: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): CliExecutionResult {
        val gradle = findGradleExecutable()
        val runArgs = listOf("run", "--args=$command ${args.joinToString(" ")}")
        val fullCommand = listOf(gradle) + runArgs
        return executeProcess(fullCommand, CLI_MODULE_DIR, environmentVars, timeoutSeconds)
    }
    
    /**
     * Execute CLI command for pipeline execution using the isolated CLI JAR
     */
    fun executeCliWithBackendJar(
        scriptPath: String,
        configPath: String? = null,
        workingDir: File = tempWorkingDir,
        environmentVars: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): CliExecutionResult {
        val jarFile = getIsolatedJar()
        
        // Convert relative paths to be relative to temp directory
        val resolvedScriptPath = if (File(scriptPath).isAbsolute) {
            scriptPath
        } else {
            File(tempWorkingDir, scriptPath).absolutePath
        }
        
        val resolvedConfigPath = configPath?.let { configPath ->
            if (File(configPath).isAbsolute) {
                configPath
            } else {
                File(tempWorkingDir, configPath).absolutePath
            }
        }
        
        // Use the CLI JAR with 'run' command for pipeline execution
        val fullCommand = mutableListOf("java", "-jar", jarFile.absolutePath, "run", resolvedScriptPath)
        if (resolvedConfigPath != null) {
            fullCommand.addAll(listOf("--config", resolvedConfigPath))
        }
        
        return executeProcess(fullCommand, workingDir, environmentVars, timeoutSeconds)
    }
    
    /**
     * Execute any process and capture result
     */
    private fun executeProcess(
        command: List<String>,
        workingDir: File,
        environmentVars: Map<String, String>,
        timeoutSeconds: Long
    ): CliExecutionResult {
        val startTime = System.currentTimeMillis()
        
        val processBuilder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)
        
        // Add environment variables
        if (environmentVars.isNotEmpty()) {
            processBuilder.environment().putAll(environmentVars)
        }
        
        val process = processBuilder.start()
        
        // Read stdout and stderr in separate threads
        val stdoutFuture = process.inputStream.bufferedReader().use { it.readText() }
        val stderrFuture = process.errorStream.bufferedReader().use { it.readText() }
        
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val endTime = System.currentTimeMillis()
        
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("Command timed out after $timeoutSeconds seconds: ${command.joinToString(" ")}")
        }
        
        return CliExecutionResult(
            exitCode = process.exitValue(),
            stdout = stdoutFuture,
            stderr = stderrFuture,
            executionTimeMs = endTime - startTime
        )
    }
    
    /**
     * Verify that the CLI is available and executable
     */
    fun verifyCliAvailability(): Boolean {
        return try {
            val result = executeCliWithGradle("version", timeoutSeconds = 10)
            result.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Build the CLI JAR if it doesn't exist
     */
    fun ensureCliJarExists(): Boolean {
        return jarAccessLock.withLock {
            val jarFile = findBuiltJar()
            if (jarFile != null && jarFile.exists()) {
                // Re-copy JAR to temp directory if needed
                copiedJarFile = copyJarToTempDir()
                return@withLock true
            }
            
            try {
                val gradle = findGradleExecutable()
                val buildResult = executeProcess(
                    listOf(gradle, "shadowJar"),
                    PROJECT_ROOT,
                    emptyMap(),
                    120 // 2 minutes timeout for build
                )
                if (buildResult.exitCode == 0) {
                    // Copy newly built JAR to temp directory
                    copiedJarFile = copyJarToTempDir()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Get information about available test data files (isolated versions)
     */
    fun getTestDataInfo(): Map<String, Boolean> {
        return mapOf(
            "CLI testData directory" to File(CLI_MODULE_DIR, "testData").exists(),
            "Backend testData directory" to File(BACKEND_MODULE_DIR, "testData").exists(),
            "CLI success.pipeline.kts" to File(CLI_MODULE_DIR, "testData/success.pipeline.kts").exists(),
            "Backend success.pipeline.kts" to File(BACKEND_MODULE_DIR, "testData/success.pipeline.kts").exists(),
            "CLI config.yaml" to File(CLI_MODULE_DIR, "testData/config.yaml").exists(),
            "Backend config.yaml" to File(BACKEND_MODULE_DIR, "testData/config.yaml").exists(),
            "Built JAR" to (findBuiltJar()?.exists() == true),
            "Isolated testData directory" to File(tempWorkingDir, "testData").exists(),
            "Isolated JAR" to (copiedJarFile?.exists() == true),
            "Temp working directory" to tempWorkingDir.exists()
        )
    }
}