package dev.rubentxu.pipeline.steps

import dev.rubentxu.pipeline.dsl.Pipeline
import dev.rubentxu.pipeline.dsl.StepsBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Shell class extends StepBlock and provides functionality to execute shell commands.
 *
 * @property pipeline The pipeline in which this shell command block is being executed.
 */
class Shell(pipeline: Pipeline) : StepsBlock(pipeline) {

    /**
     * Executes a shell command in a specific directory.
     *
     * @param command The command to execute.
     * @param directory The directory in which to execute the command. Defaults to the StepBlock's working directory.
     * @return The output of the command execution as a string.
     * @throws Exception If the command exits with a non-zero exit code.
     */
    suspend fun execute(command: String, directory: File): String {
        logger.info("Executing command: $command in directory: $directory")
        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder("/bin/bash", "-c", command)
                .directory(directory)
                .start()

            val stdout: InputStream = process.inputStream
            val stderr: InputStream = process.errorStream

            // Read the output and error (if any)
            val output = stdout.bufferedReader().readText()
            val error = stderr.bufferedReader().readText()

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw Exception("Error executing command. Exit code: $exitCode, Error: $error for command: $command")
            }
            output
        }
    }
}




