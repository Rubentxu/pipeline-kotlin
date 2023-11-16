package dev.rubentxu.pipeline.steps


import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.logger.PipelineLogger
import dev.rubentxu.pipeline.model.pipeline.Pipeline
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStream

/**
 * Shell class extends StepBlock and provides functionality to execute shell commands.
 *
 * @property pipeline The pipeline in which this shell command block is being executed.
 */
class Shell(val pipeline: Pipeline, var timeout: Long = 15000)  {
    val logger = PipelineLogger.getLogger()
    /**
     * Executes a shell command in a specific directory.
     *
     * @param command The command to execute.
     * @param directory The directory in which to execute the command. Defaults to the StepBlock's working directory.
     * @return The output of the command execution as a string.
     * @throws Exception If the command exits with a non-zero exit code.
     */
    suspend fun execute(command: String, returnStdout: Boolean = false): String {
        return withContext(Dispatchers.IO) {
            val process: Process = run(command)

            val stdout: InputStream = process.inputStream
            val stderr: InputStream = process.errorStream

            // Read the output and error (if any)
            val output: String = stdout.bufferedReader().readText()
            val error: String = stderr.bufferedReader().readText()

            val exitCode: Int = process.waitFor()

            if (exitCode != 0) {
                throw Exception("Error executing command. Exit code: $exitCode, Error: $error for command: $command")
            }
            logger.info(output)
            if (process.exitValue() == 0) {
                if (returnStdout) {
                    return@withContext output
                }
            } else {
                return@withContext error
            }
            return@withContext ""

        }
    }

    fun run(command: String): Process {
        pipeline.env["JAVA_HOME"]?.let {
            pipeline.env["PATH"] = "${it}/bin:${pipeline.env["PATH"]}"
        }

        pipeline.env["M2_HOME"]?.let {
            pipeline.env["PATH"] = "${it}/bin:${pipeline.env["PATH"]}"
        }

        val directory = File(pipeline.toFullPath(pipeline.workingDir))
        logger.info("+ sh in working directory $directory")
        logger.info("+ sh $command")
        return ProcessBuilder("sh", "-c", command)
            .directory(directory)
            .apply {
                environment().putAll(pipeline.env)
            }
            .start()
    }






}




