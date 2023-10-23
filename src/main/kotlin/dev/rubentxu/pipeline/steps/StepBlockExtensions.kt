package dev.rubentxu.pipeline.steps

import dev.rubentxu.pipeline.dsl.PipelineDsl
import dev.rubentxu.pipeline.dsl.StepBlock
import java.io.File

/**
 * Executes a shell script in the specified directory.
 *
 * This function creates a new Shell instance and uses it to execute the provided script. The output of the script
 * is captured and, if the `returnStdout` parameter is set to true, is printed to the standard output.
 *
 * @param script The shell script to execute.
 * @param directory The directory in which to execute the script. Defaults to the current directory.
 * @param returnStdout Whether to print the output of the script to the standard output. Defaults to false.
 * @throws ShellCommandExecutionException If the shell command fails to execute.
 */
suspend fun PipelineDsl.sh(script: String, returnStdout: Boolean = false): String {
    val shell = Shell(this)
    val output = shell.execute(script, getPipeline().workingDir.toFile())
    logger.info(output)
    logger.info("Shell script executed successfully: $script")
    if(returnStdout) {
       return output
    }
    logger.info(output)
    return ""
}

/**
 * Prints a message to the standard output.
 *
 * This function is a wrapper around `println` and is used to print a message to the standard output during the execution
 * of a pipeline step.
 *
 * @param message The message to print.
 */
suspend fun StepBlock.echo(message: String): Unit {
    logger.info(message)
    return Unit
}