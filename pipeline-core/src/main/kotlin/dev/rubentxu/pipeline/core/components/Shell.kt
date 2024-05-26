package dev.rubentxu.pipeline.model.steps


import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.interfaces.IWorkspace
import dev.rubentxu.pipeline.core.pipeline.EnvVars
import kotlinx.coroutines.*

/**
 * Shell class extends StepBlock and provides functionality to execute shell commands.
 *
 * @property pipeline The pipeline in which this shell command block is being executed.
 */
class Shell(
    val env: EnvVars,
    val workspaceManager: IWorkspace,
    val logger: ILogger,
    var timeout: Long = 15000,
) {


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
            val output = StringBuilder()
            val error = StringBuilder()

            // Corutina para manejar la salida estándar
            val stdOutJob = launch {
                val stdInput = process.inputStream.bufferedReader()
                var line: String?
                while (stdInput.readLine().also { line = it } != null) {
                    if (returnStdout) {
                        output.append(line).append("\n")
                    }
                    println("STDOUT: $line")

                }
            }

            // Corutina para manejar el error stream

            val stdErrJob = launch {
                val stdError = process.errorStream.bufferedReader()
                var line: String?
                while (stdError.readLine().also { line = it } != null) {
                    if (returnStdout) {
                        output.append(line).append("\n")
                    }
                    logger.error("STDERR", line?:"")

                }
            }

            stdOutJob.join()
            stdErrJob.join()

            val exitCode: Int = process.waitFor()

            if (exitCode != 0) {
                throw Exception("Error executing command. Exit code: $exitCode, Error: $error for command: $command")
            }
//            logger.info(output)
            if (process.exitValue() == 0) {
                if (returnStdout) {
                    return@withContext output.toString()
                }
            } else {
                return@withContext error.toString()
            }
            return@withContext ""

        }
    }

    fun run(command: String): Process {
        env["JAVA_HOME"]?.let {
            env["PATH"] = "${it}/bin:${env["PATH"]}"
        }

        env["M2_HOME"]?.let {
            env["PATH"] = "${it}/bin:${env["PATH"]}"
        }

        val directory = workspaceManager.currentPath.toFile()
        return ProcessBuilder("sh", "-c", command)
            .directory(directory)
            .apply {
                environment().putAll(env)
            }
            .start()
    }


}




