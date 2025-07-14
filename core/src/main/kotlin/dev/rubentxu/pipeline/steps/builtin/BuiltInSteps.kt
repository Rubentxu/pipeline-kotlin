package dev.rubentxu.pipeline.steps.builtin

import dev.rubentxu.pipeline.context.PipelineContext
import dev.rubentxu.pipeline.context.ShellOptions
import dev.rubentxu.pipeline.steps.annotations.Step
import dev.rubentxu.pipeline.steps.annotations.StepCategory
import dev.rubentxu.pipeline.steps.annotations.SecurityLevel
import dev.rubentxu.pipeline.model.scm.Scm

/**
 * Built-in @Step functions with DSL v2 syntax.
 * 
 * These functions use automatic PipelineContext injection via the K2 compiler plugin.
 * The context parameter is injected as the first parameter automatically,
 * eliminating the need for manual LocalPipelineContext.current calls.
 */

/**
 * Executes a shell command within the pipeline context.
 * 
 * @param command The shell command to execute
 * @param returnStdout Whether to return the stdout output
 * @return The command output if returnStdout is true, empty string otherwise
 */
@Step(
    name = "sh",
    description = "Executes a shell command within the pipeline context",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun sh(context: PipelineContext, command: String, returnStdout: Boolean = false): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(command.isNotBlank()) { "Command cannot be blank" }
    
    context.logger.info("+ sh $command")
    
    val result = context.executeShell(
        command = command,
        options = ShellOptions(returnStdout = returnStdout)
    )
    
    if (!result.success) {
        throw RuntimeException("Shell command failed with exit code ${result.exitCode}: ${result.stderr}")
    }
    
    return if (returnStdout) {
        result.stdout
    } else {
        context.logger.info(result.stdout)
        ""
    }
}

/**
 * Prints a message to the pipeline logs.
 * 
 * @param message The message to log
 */
@Step(
    name = "echo",
    description = "Prints a message to the pipeline logs",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
fun echo(context: PipelineContext, message: String) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(message.isNotBlank()) { "Message cannot be blank" }
    
    context.logger.info("+ echo")
    context.logger.info(message)
}

/**
 * Checks out source code from an SCM repository.
 * 
 * @param scm The SCM configuration
 * @return The checkout result
 */
@Step(
    name = "checkout",
    description = "Checks out source code from an SCM repository",
    category = StepCategory.SCM,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun checkout(context: PipelineContext, scm: Scm): String {
    // Context is automatically injected by the K2 compiler plugin
    
    // TODO: Integrate with existing CheckoutStep
    // For now, delegate to the existing implementation
    context.logger.info("Checking out SCM: ${scm}")
    
    // This will need to be implemented to work with the existing CheckoutStep
    return "mock-commit-id"
}

/**
 * Reads file content within the pipeline working directory.
 * 
 * @param file The file path relative to working directory
 * @return The file content as string
 */
@Step(
    name = "readFile",
    description = "Reads file content within the pipeline working directory",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun readFile(context: PipelineContext, file: String): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(file.isNotBlank()) { "File path cannot be blank" }
    
    return context.readFile(file)
}

/**
 * Writes content to a file within the pipeline working directory.
 * 
 * @param file The file path relative to working directory
 * @param text The content to write
 */
@Step(
    name = "writeFile",
    description = "Writes content to a file within the pipeline working directory",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun writeFile(context: PipelineContext, file: String, text: String) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(file.isNotBlank()) { "File path cannot be blank" }
    
    context.writeFile(file, text)
}

/**
 * Checks if a file exists within the pipeline working directory.
 * 
 * @param file The file path relative to working directory
 * @return True if file exists and is readable
 */
@Step(
    name = "fileExists",
    description = "Checks if a file exists within the pipeline working directory",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun fileExists(context: PipelineContext, file: String): Boolean {
    // Context is automatically injected by the K2 compiler plugin
    
    require(file.isNotBlank()) { "File path cannot be blank" }
    
    return context.fileExists(file)
}

/**
 * Retries a block of code with exponential backoff.
 * 
 * @param maxRetries Maximum number of retry attempts
 * @param block The code block to retry (must be a @Step function)
 * @return The result of the successful execution
 */
@Step(
    name = "retry",
    description = "Retries a block of code with exponential backoff",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun retry(context: PipelineContext, maxRetries: Int, block: suspend () -> Any): Any {
    // Context is automatically injected by the K2 compiler plugin
    
    require(maxRetries > 0) { "Max retries must be positive" }
    
    var currentRetry = 0
    var lastError: Throwable? = null
    var backoffMs = 1000L

    while (currentRetry < maxRetries) {
        try {
            return block()
        } catch (e: Throwable) {
            lastError = e
            currentRetry++
            if (currentRetry >= maxRetries) {
                break
            }
            context.logger.info("Attempt $currentRetry/$maxRetries failed, retrying in ${backoffMs}ms...")
            kotlinx.coroutines.delay(backoffMs)
            backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(30000L) // Cap at 30 seconds
        }
    }
    throw Exception("Operation failed after $maxRetries attempts.", lastError)
}

/**
 * Delays execution for the specified time.
 * 
 * @param timeMillis Delay time in milliseconds
 */
@Step(
    name = "sleep",
    description = "Delays execution for the specified time",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun sleep(context: PipelineContext, timeMillis: Long) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(timeMillis >= 0) { "Delay time must be non-negative" }
    
    context.logger.info("+ sleep ${timeMillis}ms")
    kotlinx.coroutines.delay(timeMillis)
}

/**
 * Executes a shell script from a file.
 * 
 * @param scriptPath Path to the script file relative to workspace
 * @param args Arguments to pass to the script
 * @param returnStdout Whether to capture and return stdout
 * @return Script output if returnStdout is true
 */
@Step(
    name = "script",
    description = "Execute shell script file with arguments",
    category = StepCategory.BUILD,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun script(context: PipelineContext, scriptPath: String, args: List<String> = emptyList(), returnStdout: Boolean = false): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(scriptPath.isNotBlank()) { "Script path cannot be blank" }
    
    val fullPath = context.workingDirectory.resolve(scriptPath)
    
    if (!fullPath.toFile().exists()) {
        throw IllegalArgumentException("Script file not found: $scriptPath")
    }
    
    if (!fullPath.toFile().canExecute()) {
        // Make script executable
        sh(context, "chmod +x ${fullPath.toAbsolutePath()}")
    }
    
    val command = if (args.isEmpty()) {
        fullPath.toAbsolutePath().toString()
    } else {
        "${fullPath.toAbsolutePath()} ${args.joinToString(" ")}"
    }
    
    return sh(context, command, returnStdout)
}

/**
 * Creates a directory and all parent directories if they don't exist.
 * 
 * @param path Directory path relative to workspace
 */
@Step(
    name = "mkdir",
    description = "Create directory and parent directories",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun mkdir(context: PipelineContext, path: String) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(path.isNotBlank()) { "Path cannot be blank" }
    
    context.logger.info("+ mkdir: $path")
    
    val fullPath = context.workingDirectory.resolve(path)
    java.nio.file.Files.createDirectories(fullPath)
}

/**
 * Copies a file or directory to another location.
 * 
 * @param source Source path relative to workspace
 * @param destination Destination path relative to workspace
 * @param recursive Whether to copy directories recursively (default: true)
 */
@Step(
    name = "copyFile",
    description = "Copy file or directory to another location",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun copyFile(context: PipelineContext, source: String, destination: String, recursive: Boolean = true) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(source.isNotBlank()) { "Source path cannot be blank" }
    require(destination.isNotBlank()) { "Destination path cannot be blank" }
    
    context.logger.info("+ copyFile: $source -> $destination")
    
    val sourcePath = context.workingDirectory.resolve(source)
    val destPath = context.workingDirectory.resolve(destination)
    
    if (!sourcePath.toFile().exists()) {
        throw IllegalArgumentException("Source not found: $source")
    }
    
    if (sourcePath.toFile().isDirectory) {
        if (recursive) {
            sourcePath.toFile().copyRecursively(destPath.toFile(), overwrite = true)
        } else {
            throw IllegalArgumentException("Source is directory but recursive=false: $source")
        }
    } else {
        // Create parent directories if they don't exist
        destPath.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.copy(sourcePath, destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Deletes a file or directory.
 * 
 * @param path Path to delete relative to workspace
 * @param recursive Whether to delete directories recursively (default: true)
 */
@Step(
    name = "deleteFile",
    description = "Delete file or directory",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun deleteFile(context: PipelineContext, path: String, recursive: Boolean = true) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(path.isNotBlank()) { "Path cannot be blank" }
    
    context.logger.info("+ deleteFile: $path")
    
    val fullPath = context.workingDirectory.resolve(path)
    
    if (!fullPath.toFile().exists()) {
        context.logger.warn("File not found (skipping): $path")
        return
    }
    
    if (fullPath.toFile().isDirectory) {
        if (recursive) {
            fullPath.toFile().deleteRecursively()
        } else {
            if (fullPath.toFile().listFiles()?.isNotEmpty() == true) {
                throw IllegalArgumentException("Directory not empty (use recursive=true): $path")
            }
            fullPath.toFile().delete()
        }
    } else {
        fullPath.toFile().delete()
    }
}

/**
 * Gets the current timestamp in various formats.
 * 
 * @param format Timestamp format (ISO, EPOCH, CUSTOM) (default: ISO)
 * @param pattern Custom pattern for CUSTOM format (default: yyyy-MM-dd HH:mm:ss)
 * @return Formatted timestamp string
 */
@Step(
    name = "timestamp",
    description = "Get current timestamp in specified format",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
fun timestamp(format: String = "ISO", pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
    val now = java.time.LocalDateTime.now()
    
    return when (format.uppercase()) {
        "ISO" -> now.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        "EPOCH" -> (System.currentTimeMillis() / 1000).toString()
        "CUSTOM" -> now.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
        else -> now.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }
}

/**
 * Generates a random UUID.
 * 
 * @return UUID string
 */
@Step(
    name = "generateUUID",
    description = "Generate a random UUID",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
fun generateUUID(): String {
    return java.util.UUID.randomUUID().toString()
}

/**
 * Gets an environment variable value.
 * 
 * @param name Variable name
 * @param defaultValue Default value if variable is not set
 * @return Variable value or default
 */
@Step(
    name = "getEnv",
    description = "Get environment variable value",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED
)
fun getEnv(context: PipelineContext, name: String, defaultValue: String = ""): String {
    // Context is automatically injected by the K2 compiler plugin
    return context.environment[name] ?: defaultValue
}

/**
 * Lists files and directories in a path.
 * 
 * @param path Directory path relative to workspace (default: ".")
 * @param recursive Whether to list recursively (default: false)
 * @param includeHidden Whether to include hidden files (default: false)
 * @return List of file paths relative to the specified directory
 */
@Step(
    name = "listFiles",
    description = "List files and directories in path",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
fun listFiles(context: PipelineContext, path: String = ".", recursive: Boolean = false, includeHidden: Boolean = false): List<String> {
    // Context is automatically injected by the K2 compiler plugin
    
    context.logger.info("+ listFiles: $path (recursive=$recursive)")
    
    val fullPath = context.workingDirectory.resolve(path)
    
    if (!fullPath.toFile().exists()) {
        throw IllegalArgumentException("Directory not found: $path")
    }
    
    if (!fullPath.toFile().isDirectory) {
        throw IllegalArgumentException("Path is not a directory: $path")
    }
    
    val files = mutableListOf<String>()
    
    fun collectFiles(dir: java.io.File, basePath: String = "") {
        dir.listFiles()?.forEach { file ->
            if (!includeHidden && file.name.startsWith(".")) {
                return@forEach
            }
            
            val relativePath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
            files.add(relativePath)
            
            if (recursive && file.isDirectory) {
                collectFiles(file, relativePath)
            }
        }
    }
    
    collectFiles(fullPath.toFile())
    return files.sorted()
}