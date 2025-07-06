package pipeline.kotlin.extensions

import dev.rubentxu.pipeline.context.StepExecutionScope
import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.model.scm.Scm
import dev.rubentxu.pipeline.model.scm.GitScm
import dev.rubentxu.pipeline.steps.Shell
import dev.rubentxu.pipeline.steps.CheckoutStep
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * DSL extension functions for pipeline steps.
 * These functions can only be used within a StepsBlock context to ensure proper scoping.
 */

/**
 * Executes a shell command within the pipeline context.
 * This function is scoped to StepsBlock and provides secure execution.
 *
 * @param command The shell command to execute
 * @param returnStdout Whether to return the stdout output
 * @return The command output if returnStdout is true, empty string otherwise
 */
fun StepsBlock.sh(command: String, returnStdout: Boolean = false): String = runBlocking {
    require(command.isNotBlank()) { "Command cannot be blank" }
    
    val shell = Shell(pipeline)
    logger.info("+ sh $command")
    val output = shell.execute(command, returnStdout)

    if (returnStdout) {
        return@runBlocking output
    }
    logger.info(output)
    return@runBlocking ""
}

/**
 * Prints a message to the pipeline logs.
 * This function is scoped to StepsBlock for controlled logging.
 *
 * @param message The message to log
 */
fun StepsBlock.echo(message: String) {
    require(message.isNotBlank()) { "Message cannot be blank" }
    logger.info("+ echo")
    logger.info(message)
}

/**
 * Checks out source code from an SCM repository.
 * This function is scoped to StepsBlock and provides secure SCM operations.
 *
 * @param scm The SCM configuration
 * @return The checkout result
 */
fun StepsBlock.checkout(scm: Scm): String = runBlocking {
    val checkoutStep = CheckoutStep(stepContext)
    val result = checkoutStep.execute(scm)
    
    if (result.success) {
        logger.info("Checkout successful: ${result.commitId}")
        result.commitId
    } else {
        throw Exception("Checkout failed: ${result.error}")
    }
}

/**
 * Convenience function to create a Git SCM configuration.
 * This function uses modern Kotlin DSL practices with trailing lambda.
 *
 * @param url The Git repository URL
 * @param configure Configuration block for additional Git settings
 * @return Configured GitScm instance
 */
fun git(url: String, configure: GitScmBuilder.() -> Unit = {}): GitScm {
    require(url.isNotBlank()) { "Git URL cannot be blank" }
    return GitScmBuilder(url).apply(configure).build()
}

/**
 * Builder class for GitScm using modern Kotlin DSL practices
 */
class GitScmBuilder(private val url: String) {
    var branch: String = "main"
    var credentialsId: String? = null
    var shallow: Boolean = false
    var noTags: Boolean = false
    var timeout: Int = 10
    var recursiveSubmodules: Boolean = false
    
    fun build(): GitScm = GitScm(
        url = url,
        branch = branch,
        credentialsId = credentialsId
    )
}

/**
 * Retries a block of code with exponential backoff.
 * This function is scoped to StepsBlock for controlled retry logic.
 *
 * @param maxRetries Maximum number of retry attempts
 * @param block The code block to retry
 * @return The result of the successful execution
 */
fun StepsBlock.retry(maxRetries: Int, block: () -> Any): Any {
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
            logger.info("Attempt $currentRetry/$maxRetries failed, retrying in ${backoffMs}ms...")
            Thread.sleep(backoffMs)
            backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(30000L) // Cap at 30 seconds
        }
    }
    throw Exception("Operation failed after $maxRetries attempts.", lastError)
}

/**
 * Delays execution for the specified time.
 * This function is scoped to StepsBlock and uses coroutines.
 *
 * @param timeMillis Delay time in milliseconds
 * @param block The code block to execute after delay
 */
fun StepsBlock.delay(timeMillis: Long, block: () -> Unit) = runBlocking {
    require(timeMillis >= 0) { "Delay time must be non-negative" }
    kotlinx.coroutines.delay(timeMillis)
    block()
}

/**
 * Reads file content within the pipeline working directory.
 * This function is scoped to StepsBlock for secure file operations.
 *
 * @param file The file path relative to working directory
 * @return The file content as string
 */
fun StepsBlock.readFile(file: String): String {
    require(file.isNotBlank()) { "File path cannot be blank" }
    
    val workingDir = pipeline.workingDir
    val filePath = workingDir.resolve(file).normalize()
    
    // Security check: ensure file is within working directory
    if (!filePath.startsWith(workingDir)) {
        throw SecurityException("File access outside working directory not allowed: $file")
    }
    
    return Files.readString(filePath)
}

/**
 * Checks if a file exists within the pipeline working directory.
 * This function is scoped to StepsBlock for secure file operations.
 *
 * @param file The file path relative to working directory
 * @return True if file exists and is readable
 */
fun StepsBlock.fileExists(file: String): Boolean {
    require(file.isNotBlank()) { "File path cannot be blank" }
    
    val workingDir = pipeline.workingDir
    val filePath = workingDir.resolve(file).normalize()
    
    // Security check: ensure file is within working directory
    if (!filePath.startsWith(workingDir)) {
        return false
    }
    
    return Files.exists(filePath) && Files.isRegularFile(filePath)
}

/**
 * Writes content to a file within the pipeline working directory.
 * This function is scoped to StepsBlock for secure file operations.
 *
 * @param file The file path relative to working directory
 * @param text The content to write
 */
fun StepsBlock.writeFile(file: String, text: String) {
    require(file.isNotBlank()) { "File path cannot be blank" }
    
    val workingDir = pipeline.workingDir
    val filePath = workingDir.resolve(file).normalize()
    
    // Security check: ensure file is within working directory
    if (!filePath.startsWith(workingDir)) {
        throw SecurityException("File access outside working directory not allowed: $file")
    }
    
    // Create parent directories if they don't exist
    filePath.parent?.let { Files.createDirectories(it) }
    
    Files.writeString(filePath, text)
}

