package dev.rubentxu.pipeline.model.pipeline

import dev.rubentxu.pipeline.dsl.PipelineDsl
import dev.rubentxu.pipeline.dsl.StageBlock
import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.logger.PipelineLogger

/**
 * Executes a single stage within a pipeline, managing its lifecycle and execution context.
 *
 * StageExecutor is responsible for the complete execution lifecycle of a pipeline stage,
 * including initialization, step execution, error handling, and post-execution cleanup.
 * Each stage provides an isolated execution environment for its steps while maintaining
 * access to the parent pipeline's context and resources.
 *
 * ## Stage Execution Lifecycle
 * 1. **Initialization**: Create stage execution context and DSL block
 * 2. **DSL Evaluation**: Execute the stage's DSL block to define steps and post-execution
 * 3. **Step Execution**: Execute all defined steps sequentially
 * 4. **Error Handling**: Catch and handle any execution errors
 * 5. **Post-Execution**: Run cleanup or notification code regardless of outcome
 * 6. **Result Reporting**: Return execution results to the pipeline
 *
 * ## Error Handling Strategy
 * - **Fail-Fast**: If any step fails, the entire stage fails immediately
 * - **Exception Propagation**: Errors are logged and re-thrown to fail the pipeline
 * - **Guaranteed Cleanup**: Post-execution blocks always run, even on failure
 * - **Status Tracking**: Stage execution status is tracked and reported
 *
 * ## Usage Example
 * ```kotlin
 * // Stage creation (typically done internally by the pipeline DSL)
 * val buildStage = StageExecutor("Build") {
 *     steps {
 *         sh("./gradlew clean")
 *         sh("./gradlew build")
 *         echo("Build completed successfully")
 *     }
 *     post {
 *         always {
 *             echo("Cleaning up build artifacts")
 *             sh("./cleanup.sh")
 *         }
 *         failure {
 *             echo("Build failed, sending notification")
 *             sh("./notify-failure.sh")
 *         }
 *     }
 * }
 * 
 * // Stage execution
 * val result = buildStage.run(pipeline)
 * ```
 *
 * ## Integration with Pipeline
 * StageExecutor integrates closely with the pipeline execution framework:
 * - Receives execution context from the parent [Pipeline]
 * - Creates and manages [StageBlock] DSL instances
 * - Delegates step execution to [StepsBlock]
 * - Reports results back to the pipeline for overall status tracking
 *
 * @param name The unique identifier for this stage within the pipeline
 * @param block The suspending DSL block that defines the stage's behavior, including
 *              steps to execute and post-execution actions
 * @property logger Pipeline logger instance for stage execution logging
 * @property postExecution Optional post-execution configuration defined in the DSL block
 * 
 * @since 1.0.0
 * @see Pipeline
 * @see StageBlock
 * @see StepsBlock
 * @see PostExecution
 */
@PipelineDsl
class StageExecutor(val name: String, val block: suspend StageBlock.() -> Any) {

    val logger = PipelineLogger.getLogger()
    var postExecution: PostExecution? = null

    /**
     * Executes this stage within the context of the specified pipeline.
     *
     * This method orchestrates the complete execution lifecycle of the stage,
     * including DSL block evaluation, step execution, error handling, and
     * post-execution cleanup. The execution is suspendable to support
     * asynchronous operations within steps.
     *
     * ## Execution Flow
     * 1. **Setup**: Create stage execution context and initialize status tracking
     * 2. **DSL Evaluation**: Execute the stage's DSL block to configure steps and post-execution
     * 3. **Step Execution**: Execute all defined steps sequentially through [executeSteps]
     * 4. **Error Handling**: Catch exceptions, log errors, and update status
     * 5. **Post-Execution**: Run cleanup/notification code regardless of outcome
     * 6. **Result Return**: Return execution results to the pipeline
     *
     * ## Error Handling
     * - Exceptions during execution are caught, logged, and re-thrown
     * - Stage status is updated to FAILURE on any error
     * - Post-execution blocks always run, even on failure
     * - Stage results are reported for pipeline tracking
     *
     * ## Example Usage
     * ```kotlin
     * val pipeline = Pipeline.builder()
     *     .workingDirectory("/tmp/workspace")
     *     .build()
     *
     * val stage = StageExecutor("Build") {
     *     steps {
     *         sh("./gradlew build")
     *         echo("Build completed")
     *     }
     * }
     *
     * try {
     *     val result = stage.run(pipeline)
     *     println("Stage completed successfully: $result")
     * } catch (e: Exception) {
     *     println("Stage failed: ${e.message}")
     * }
     * ```
     *
     * @param pipeline The pipeline instance providing execution context, environment,
     *                 and resources for this stage
     * @return The result of the stage execution, typically the result of the last step
     * @throws Exception if any step in the stage fails or if stage setup fails
     * @see Pipeline
     * @see StageBlock
     * @see StepsBlock
     * @since 1.0.0
     */
    suspend fun run(pipeline: Pipeline): Any {
        var status: Status = Status.SUCCESS
        var errorMessage = ""
        val dsl = StageBlock(name, pipeline)
//        val steps = StepsBlock(pipeline)
        var result: Any = ""
        try {
            dsl.block()
            postExecution = dsl.postExecution
            val stepsBlock: (StepsBlock.() -> Unit)? = dsl.stepsBlock
            if (stepsBlock != null) {
                result = executeSteps(stepsBlock, pipeline)
            }

        } catch (e: Exception) {
            status = Status.FAILURE
            errorMessage = e.message ?: ""
            logger.error("Error running stage $name, ${e.message}")
            throw e
        } finally {
            postExecution?.run(pipeline, listOf(StageResult(name, status, "", errorMessage)))
        }
        return result
    }

    /**
     * Executes the steps defined in a steps block within the stage context.
     *
     * This method creates a [StepsBlock] instance and executes the provided DSL block
     * to define and run the steps. It serves as the bridge between the stage's DSL
     * configuration and the actual step execution.
     *
     * ## Step Execution Process
     * 1. **StepsBlock Creation**: Creates a new StepsBlock with the pipeline context
     * 2. **DSL Evaluation**: Executes the steps DSL block to define step operations
     * 3. **Step Collection**: Steps are collected in the StepsBlock for execution
     * 4. **Sequential Execution**: Steps are executed in the order they were defined
     *
     * ## Example Usage
     * ```kotlin
     * // This is typically called internally by the stage execution
     * executeSteps(
     *     block = {
     *         sh("./gradlew clean")
     *         sh("./gradlew build")
     *         echo("Build completed")
     *     },
     *     pipeline = pipeline
     * )
     * ```
     *
     * @param block The DSL block containing step definitions to execute
     * @param pipeline The pipeline instance providing execution context for the steps
     * @see StepsBlock
     * @see Step
     * @since 1.0.0
     */
    fun executeSteps(block: StepsBlock.() -> Unit, pipeline: Pipeline) {
        val steps = StepsBlock(pipeline)
        steps.block()
    }
}