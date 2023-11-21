package pipeline.kotlin.extensions

import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.steps.Shell
import kotlinx.coroutines.runBlocking
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
fun StepsBlock.sh(script: String, returnStdout: Boolean = false): String = runBlocking {
    val shell = Shell(pipeline)
    val output = shell.execute(script, returnStdout)

    logger.info("Shell script executed successfully: $script")
    if (returnStdout) {
        return@runBlocking output
    }
    logger.info(output)
    return@runBlocking ""
}

/**
 * Prints a message to the standard output.
 *
 * This function is a wrapper around `println` and is used to print a message to the standard output during the execution
 * of a pipeline step.
 *
 * @param message The message to print.
 */
/**
 * Prints a message to the standard output.
 *
 * This function is a wrapper around `println` and is used to print a message to the standard output during the execution
 * of a pipeline step.
 *
 * @param message The message to print.
 */
fun StepsBlock.echo(message: String) {
    logger.info(message)
}

fun StepsBlock.retry(maxRetries: Int, block: () -> Any): Any {
    var currentRetry = 0
    var lastError: Throwable? = null

    while (currentRetry < maxRetries) {
        try {
            return block()
        } catch (e: Throwable) {
            lastError = e
            currentRetry++
            if (currentRetry >= maxRetries) {
                break
            }
            logger.info("Intento $currentRetry/$maxRetries fallido, reintento...")
        }
    }
    throw Exception("La operación ha fallado después de $maxRetries intentos.", lastError)
}

fun StepsBlock.delay(timeMillis: Long, block: () -> Unit) = runBlocking {
    kotlinx.coroutines.delay(timeMillis)
    block()
}

fun StepsBlock.readFile(file: String): String {
    return File(file).readText()
}

fun StepsBlock.fileExists(file: String): Boolean {
    return File(file).exists() && File(file).isFile
}

fun StepsBlock.writeFile(file: String, text: String) {
    File(file).printWriter().use { out ->
        out.print(text)
    }
}
