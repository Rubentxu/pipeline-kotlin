package dev.rubentxu.pipeline.dsl

/**
 * Represents a single executable unit of work within a pipeline stage.
 *
 * A Step encapsulates a suspendable operation that can be executed asynchronously
 * within a pipeline's execution context. Steps are the atomic building blocks of
 * pipeline stages, providing fine-grained control over the execution flow.
 *
 * ## Key Characteristics
 * - **Asynchronous**: Uses Kotlin coroutines for non-blocking execution
 * - **Composable**: Can be combined with other steps in a stage
 * - **Isolatable**: Executes within a controlled security context
 * - **Retryable**: Can be wrapped in retry logic for error handling
 * - **Parallelizable**: Multiple steps can execute concurrently
 *
 * ## Step Execution Context
 * Steps execute within a [StepExecutionContext] that provides:
 * - Controlled access to pipeline resources
 * - Environment variables and configuration
 * - Security sandboxing for safe execution
 * - Logging and monitoring capabilities
 *
 * ## Common Step Types
 * Steps are typically created through convenience functions:
 * - `sh("command")` - Execute shell commands
 * - `echo("message")` - Log messages
 * - `writeFile("path", "content")` - Write files
 * - `readFile("path")` - Read files
 * - `checkout(scm)` - Source code checkout
 * - `delay(1000)` - Add execution delays
 * - `retry(3) { ... }` - Retry failed operations
 *
 * ## Usage Example
 * ```kotlin
 * stage("Build") {
 *     steps {
 *         // Each of these creates a Step instance
 *         sh("./gradlew clean")
 *         sh("./gradlew build")
 *         echo("Build completed successfully")
 *         
 *         // Custom step with complex logic
 *         step {
 *             val result = performComplexOperation()
 *             if (result.isSuccess) {
 *                 echo("Operation succeeded: ${result.value}")
 *             } else {
 *                 throw Exception("Operation failed: ${result.error}")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Error Handling
 * If a step's execution block throws an exception, the entire stage will fail
 * and subsequent steps in the stage won't execute. Use try-catch blocks within
 * the step or wrap with retry logic for error recovery.
 *
 * ## Parallel Execution
 * Steps can be executed in parallel using the `parallel` function:
 * ```kotlin
 * steps {
 *     parallel(
 *         { sh("./run-tests.sh") },
 *         { sh("./run-linter.sh") },
 *         { sh("./run-security-scan.sh") }
 *     )
 * }
 * ```
 *
 * @param block The suspendable operation to execute. Must be non-blocking and
 *              should return a meaningful result or throw an exception on failure.
 * @property block The executable code block that defines this step's behavior
 * 
 * @since 1.0.0
 * @see StepsBlock
 * @see StepExecutionContext
 * @see StepExecutionScope
 */
@PipelineDsl
class Step(val block: suspend () -> Any)