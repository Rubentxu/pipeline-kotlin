package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.annotations.PipelineDsl
import dev.rubentxu.pipeline.annotations.PipelineStep
import dev.rubentxu.pipeline.context.StepExecutionContext
import dev.rubentxu.pipeline.context.StepExecutionScope
import dev.rubentxu.pipeline.logger.IPipelineLogger
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import kotlinx.coroutines.*

/**
 * Container and executor for pipeline steps within a stage.
 *
 * StepsBlock serves as both a DSL builder for defining steps and an execution context
 * for running them. It provides controlled access to pipeline resources while maintaining
 * security boundaries and execution isolation. Steps defined in this block are executed
 * sequentially during stage execution, with each step having access to the pipeline's
 * environment and execution context.
 *
 * ## Key Responsibilities
 * - **Step Collection**: Maintains a collection of steps to be executed
 * - **Execution Context**: Provides controlled access to pipeline resources
 * - **Security Isolation**: Enforces security boundaries for step execution
 * - **Environment Access**: Provides access to pipeline environment variables
 * - **Logging Integration**: Handles logging for step execution
 * - **Parallel Execution**: Supports concurrent execution of multiple steps
 *
 * ## Available Step Functions
 * The StepsBlock provides extension functions for common pipeline operations:
 * - [sh] - Execute shell commands with output capture
 * - [echo] - Log messages to pipeline output
 * - [writeFile] - Write content to files with security validation
 * - [readFile] - Read file contents with security validation
 * - [checkout] - Checkout source code from SCM systems
 * - [retry] - Retry failed operations with exponential backoff
 * - [delay] - Add execution delays between operations
 * - [parallel] - Execute multiple steps concurrently
 *
 * ## Usage Example
 * ```kotlin
 * stage("Build and Test") {
 *     steps {
 *         // Sequential execution
 *         sh("./gradlew clean")
 *         sh("./gradlew compileKotlin")
 *         
 *         // Write build information
 *         writeFile("build.info", "Build started at ${System.currentTimeMillis()}")
 *         
 *         // Parallel execution of independent tasks
 *         parallel(
 *             "unit-tests" to Step { sh("./gradlew test") },
 *             "integration-tests" to Step { sh("./gradlew integrationTest") },
 *             "lint" to Step { sh("./gradlew ktlintCheck") }
 *         )
 *         
 *         // Custom step with complex logic
 *         step {
 *             val testResults = readFile("build/reports/tests/test/index.html")
 *             if (testResults.contains("failures: 0")) {
 *                 echo("All tests passed!")
 *             } else {
 *                 throw Exception("Tests failed!")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Security Model
 * StepsBlock implements [StepExecutionScope] to provide controlled access to:
 * - File system operations (restricted to working directory)
 * - Environment variables (read-only access)
 * - Network operations (configurable restrictions)
 * - System resources (with usage limits)
 *
 * ## Error Handling
 * If any step fails during execution, the entire stage fails immediately and
 * subsequent steps are not executed. Use try-catch blocks within steps or
 * wrap with retry logic for error recovery.
 *
 * @param pipeline The parent pipeline instance that provides execution context
 * @property logger Pipeline logger instance for step execution logging
 * @property env Environment variables accessible to steps in this block
 * @property steps Mutable collection of steps to be executed
 * @property stepContext Execution context that provides controlled access to pipeline resources
 * 
 * @since 1.0.0
 * @see Step
 * @see StepExecutionScope
 * @see StepExecutionContext
 * @see Pipeline
 */
@PipelineDsl
open class StepsBlock(val pipeline: Pipeline) : StepExecutionScope {
    val logger: IPipelineLogger = PipelineLogger.getLogger()
    val env = pipeline.env

    val steps = mutableListOf<Step>()

    // Provide controlled access to step execution context
    override val stepContext: StepExecutionContext = StepExecutionContext.create(
        pipeline = pipeline,
        logger = logger,
        workingDirectory = pipeline.workingDir.toString(),
        environment = pipeline.env
    )

    /**
     * Adds a custom step to this steps block.
     *
     * This method allows you to define custom logic as a step by providing a suspendable
     * block of code. The step will be executed in sequence with other steps during
     * stage execution.
     *
     * ## Usage Example
     * ```kotlin
     * steps {
     *     step {
     *         // Custom logic here
     *         val buildTime = System.currentTimeMillis()
     *         echo("Build started at $buildTime")
     *         
     *         // Perform some operation
     *         val result = performCustomOperation()
     *         
     *         // Handle result
     *         if (result.isSuccess) {
     *             echo("Operation completed successfully")
     *         } else {
     *             throw Exception("Operation failed: ${result.error}")
     *         }
     *     }
     * }
     * ```
     *
     * @param block The suspendable code block to execute as a step
     * @see Step
     * @since 1.0.0
     */
    @PipelineStep(description = "Executes a custom step with suspendable logic")
    fun step(block: suspend () -> Any) {
        steps += Step(block)
    }

    /**
     * Executes multiple steps concurrently in parallel.
     *
     * This method allows you to run multiple steps at the same time, which can
     * significantly improve pipeline performance when steps don't depend on each other.
     * Each step is executed in its own coroutine, and the method waits for all
     * steps to complete before returning.
     *
     * ## Error Handling
     * If any parallel step fails, the entire parallel operation fails immediately
     * and other running steps are cancelled. This ensures fail-fast behavior
     * consistent with sequential step execution.
     *
     * ## Usage Example
     * ```kotlin
     * steps {
     *     // Run independent operations in parallel
     *     parallel(
     *         "unit-tests" to Step { sh("./gradlew test") },
     *         "integration-tests" to Step { sh("./gradlew integrationTest") },
     *         "lint-check" to Step { sh("./gradlew ktlintCheck") },
     *         "security-scan" to Step { sh("./gradlew dependencyCheckAnalyze") }
     *     )
     *     
     *     // This runs after all parallel steps complete
     *     echo("All parallel operations completed")
     * }
     * ```
     *
     * ## Performance Considerations
     * - Use parallel execution for independent, time-consuming operations
     * - Avoid parallel execution for steps that share resources or have dependencies
     * - Consider system resource limits when running many parallel steps
     * - Each parallel step gets its own execution context
     *
     * @param steps Variable number of named step pairs to execute in parallel.
     *              Each pair consists of a step name (for logging) and a Step instance.
     * @return The results of all parallel step executions
     * @throws Exception if any parallel step fails
     * @see Step
     * @since 1.0.0
     */
    @PipelineStep(description = "Executes multiple steps concurrently in parallel")
    fun parallel(vararg steps: Pair<String, Step>) = runBlocking {
        steps.map { (name, step) ->
            async {
                logger.info("Starting $name")
                step.block()
                logger.info("Finished $name")
            }
        }.awaitAll()
    }

}