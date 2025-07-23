package dev.rubentxu.pipeline.dsl

// TODO: Re-enable when testing framework is compatible
// import dev.rubentxu.pipeline.annotations.PipelineDsl
// import dev.rubentxu.pipeline.annotations.PipelineStep
import dev.rubentxu.pipeline.context.*
import dev.rubentxu.pipeline.logger.interfaces.ILogger

import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.steps.builtin.*
import kotlinx.coroutines.*

/**
 * StepsBlock that integrates the @Step system with PipelineContext.
 * 
 * This class provides full support for the @Step system where PipelineContext
 * is automatically injected, similar to how @Composable functions work in Jetpack Compose.
 * 
 * The @Step system enables natural syntax with automatic context injection:
 * 
 * ```kotlin
 * steps {
 *     sh("echo hello")  // @Step function with automatic context injection
 *     echo("Build started")
 *     writeFile("output.txt", "Hello World")
 * }
 * ```
 */
// @PipelineDsl
open class StepsBlock(val pipeline: Pipeline) : StepExecutionScope {
    val logger: ILogger = PipelineLogger.getLogger()
    val env = pipeline.env

    val steps = mutableListOf<Step>()

    // Provide controlled access to step execution context
    override val stepContext: StepExecutionContext = StepExecutionContext.create(
        pipeline = pipeline,
        logger = logger,
        workingDirectory = pipeline.workingDir.toString(),
        environment = pipeline.env
    )
    
    // Create PipelineContext from existing StepExecutionContext
    private val pipelineContext: PipelineContext = stepContext.toPipelineContext()
    
    /**
     * Adds a custom step to this steps block.
     *
     * This method allows you to define custom logic as a step by providing a suspendable
     * block of code. The step will be executed in sequence with other steps during
     * stage execution.
     */
    // @PipelineStep(description = "Executes a custom step with suspendable logic")
    fun step(block: suspend () -> Any) {
        steps += Step(block)
    }

    /**
     * Executes multiple steps concurrently in parallel.
     *
     * This method allows you to run multiple steps at the same time, which can
     * significantly improve pipeline performance when steps don't depend on each other.
     */
    // @PipelineStep(description = "Executes multiple steps concurrently in parallel")
    fun parallel(vararg steps: Pair<String, Step>) = runBlocking {
        steps.map { (name, step) ->
            async {
                logger.info("Starting $name")
                step.block()
                logger.info("Finished $name")
            }
        }.awaitAll()
    }
    
    /**
     * Executes a block with PipelineContext available for @Step functions
     */
    suspend fun executeWithStepContext(block: suspend StepsBlock.() -> Unit) {
        // Set the PipelineContext for the execution
        LocalPipelineContext.runWith(pipelineContext) {
            runBlocking { block() }
        }
    }
    
    // Bridge methods to call @Step functions directly
    // These delegate to the @Step implementations in builtin package
    // For now, we'll use direct implementation until compiler plugin is working
    
    /**
     * Execute shell command (bridges to @Step function)
     */
    suspend fun sh(command: String, returnStdout: Boolean = false): String {
        require(command.isNotBlank()) { "Command cannot be blank" }
        
        logger.info("+ sh $command")
        
        val result = pipelineContext.executeShell(
            command = command,
            options = ShellOptions(returnStdout = returnStdout)
        )
        
        if (!result.success) {
            throw RuntimeException("Shell command failed with exit code ${result.exitCode}: ${result.stderr}")
        }
        
        return if (returnStdout) {
            result.stdout
        } else {
            logger.info(result.stdout)
            ""
        }
    }
    

    
    /**
     * Execute any registered step by name with configuration
     */
    open suspend fun executeStep(stepName: String, config: Map<String, Any> = emptyMap()): Any {
        return pipelineContext.executeStep(stepName, config)
    }
    
    /**
     * Get available steps from registry
     */
    fun getAvailableSteps(): List<String> {
        return pipelineContext.getAvailableSteps()
    }
    
    /**
     * Enhanced step method that provides PipelineContext to the block
     */
    fun stepWithContext(block: suspend PipelineContext.() -> Any) {
        step {
            LocalPipelineContext.runWith(pipelineContext) {
                kotlinx.coroutines.runBlocking {
                    block(pipelineContext)
                }
            }
        }
    }
    
    /**
     * Execute multiple @Step functions in parallel
     */
    fun parallelSteps(vararg namedBlocks: Pair<String, suspend () -> Any>) = runBlocking {
        namedBlocks.map { (name, block) ->
            async {
                logger.info("Starting parallel step: $name")
                val result = LocalPipelineContext.runWith(pipelineContext) {
                    runBlocking { block() }
                }
                logger.info("Finished parallel step: $name")
                result
            }
        }.awaitAll()
    }
}

/**
 * Throws an error with specified message
 */
fun StepsBlock.error(message: String) {
    throw RuntimeException(message)
}
//
///**
// * Print message to logs (bridges to @Step function)
// */
//@Step(
//
//)
//fun echo(message: String) {
//    require(message.isNotBlank()) { "Message cannot be blank" }
//
//    logger.info("+ echo")
//    logger.info(message)
//}
//
///**
// * Read file content (bridges to @Step function)
// */
//open suspend fun readFile(file: String): String {
//    require(file.isNotBlank()) { "File path cannot be blank" }
//    return pipelineContext.readFile(file)
//}
//
///**
// * Write file content (bridges to @Step function)
// */
//open suspend fun writeFile(file: String, text: String) {
//    require(file.isNotBlank()) { "File path cannot be blank" }
//    pipelineContext.writeFile(file, text)
//}
//
///**
// * Check if file exists (bridges to @Step function)
// */
//open suspend fun fileExists(file: String): Boolean {
//    require(file.isNotBlank()) { "File path cannot be blank" }
//    return pipelineContext.fileExists(file)
//}
//
///**
// * Sleep for specified time (bridges to @Step function)
// */
//open suspend fun sleep(timeMillis: Long) {
//    require(timeMillis >= 0) { "Delay time must be non-negative" }
//    logger.info("+ sleep ${timeMillis}ms")
//    kotlinx.coroutines.delay(timeMillis)
//}
//
///**
// * Retry operation with backoff (bridges to @Step function)
// */
//suspend fun retry(maxRetries: Int, block: suspend () -> Any): Any {
//    require(maxRetries > 0) { "Max retries must be positive" }
//
//    var currentRetry = 0
//    var lastError: Throwable? = null
//    var backoffMs = 1000L
//
//    while (currentRetry < maxRetries) {
//        try {
//            return block()
//        } catch (e: Throwable) {
//            lastError = e
//            currentRetry++
//            if (currentRetry >= maxRetries) {
//                break
//            }
//            logger.info("Attempt $currentRetry/$maxRetries failed, retrying in ${backoffMs}ms...")
//            kotlinx.coroutines.delay(backoffMs)
//            backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(30000L) // Cap at 30 seconds
//        }
//    }
//    throw Exception("Operation failed after $maxRetries attempts.", lastError)
//}