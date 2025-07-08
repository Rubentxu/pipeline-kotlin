package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.model.pipeline.Pipeline
import dev.rubentxu.pipeline.model.pipeline.PostExecution

/**
 * DSL builder for creating and configuring pipeline stages.
 *
 * A stage represents a logical unit of work within a pipeline execution flow. Each stage
 * can contain multiple steps that are executed sequentially within the stage's context.
 * Stages provide isolation and grouping for related operations, making pipeline execution
 * more organized and maintainable.
 *
 * ## Usage Example
 * ```kotlin
 * pipeline {
 *     stages {
 *         stage("Build") {
 *             steps {
 *                 sh("./gradlew build")
 *                 echo("Build completed successfully")
 *             }
 *             post {
 *                 always {
 *                     echo("Stage finished")
 *                 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Stage Lifecycle
 * 1. **Initialization**: Stage is created with a name and pipeline context
 * 2. **Step Definition**: Steps are defined using the [steps] block
 * 3. **Execution**: Steps are executed sequentially during pipeline run
 * 4. **Post-Execution**: Optional cleanup or notification code runs via [post] block
 *
 * @param name The unique identifier for this stage within the pipeline
 * @param pipeline The parent pipeline instance that contains this stage
 * @property postExecution Configuration for post-execution behavior (cleanup, notifications, etc.)
 * @property stepsBlock The executable block containing the steps to run in this stage
 * 
 * @since 1.0.0
 * @see Pipeline
 * @see StepsBlock
 * @see PostExecutionBlock
 */
@PipelineDsl
class StageBlock(val name: String, val pipeline: Pipeline) {
    var postExecution: PostExecution = PostExecution()
    var stepsBlock: (StepsBlock.() -> Unit)? = null

    /**
     * Defines the executable steps for this stage.
     *
     * This method configures the steps that will be executed when this stage runs.
     * Steps are executed sequentially in the order they are defined within the block.
     * If any step fails, the entire stage will fail and subsequent steps won't execute.
     *
     * ## Available Steps
     * - [sh]: Execute shell commands
     * - [echo]: Print messages to the pipeline log
     * - [writeFile]: Write content to files
     * - [readFile]: Read content from files
     * - [checkout]: Checkout source code from SCM
     * - [retry]: Retry failed operations
     * - [delay]: Add delays between operations
     * - [parallel]: Execute multiple steps in parallel
     *
     * ## Example
     * ```kotlin
     * steps {
     *     sh("echo 'Starting build'")
     *     sh("./gradlew clean build")
     *     writeFile("build.log", "Build completed at ${System.currentTimeMillis()}")
     *     echo("Build stage completed successfully")
     * }
     * ```
     *
     * @param block The DSL block containing the steps to execute in this stage
     * @see StepsBlock
     * @since 1.0.0
     */
    fun steps(block: StepsBlock.() -> Unit) {
        stepsBlock = block
    }

    /**
     * Defines post-execution actions for this stage.
     *
     * Post-execution blocks run after the stage's steps have completed, regardless of
     * whether the stage succeeded or failed. This is useful for cleanup operations,
     * notifications, or archiving artifacts.
     *
     * ## Post-Execution Types
     * - **always**: Runs regardless of stage outcome
     * - **success**: Runs only if stage succeeded
     * - **failure**: Runs only if stage failed
     * - **cleanup**: Runs for cleanup operations (equivalent to always)
     *
     * ## Example
     * ```kotlin
     * post {
     *     always {
     *         echo("Stage '${name}' execution completed")
     *         writeFile("stage.log", "Stage execution log")
     *     }
     *     success {
     *         echo("Stage '${name}' succeeded")
     *     }
     *     failure {
     *         echo("Stage '${name}' failed")
     *         sh("./cleanup-on-failure.sh")
     *     }
     * }
     * ```
     *
     * @param block The DSL block containing post-execution actions
     * @see PostExecutionBlock
     * @see PostExecution
     * @since 1.0.0
     */
    fun post(block: PostExecutionBlock.() -> Unit) {
        postExecution = PostExecutionBlock().apply(block).build()
    }


}