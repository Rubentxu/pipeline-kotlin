package dev.rubentxu.pipeline.steps

import dev.rubentxu.pipeline.dsl.PipelineDsl
import dev.rubentxu.pipeline.dsl.Steps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.lang.Exception
import java.nio.file.Path

class Shell(pipeline: PipelineDsl) : Steps(pipeline) {

    suspend fun executeCommand(command: String, directory: File = File(workingDir.toString())): String {
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

    // Use execute method from StepsExecutor to run command in coroutine
    fun executeCommandInCoroutine(command: String, directory: File = File(workingDir.toString())) {
        execute {
            val output = executeCommand(command, directory)
            println(output)
        }
    }

    private fun toFullPath(workingDir: Path): String {
        return if (workingDir.isAbsolute) {
            workingDir.toString()
        } else {
            "${this.workingDir}/${workingDir}"
        }
    }
}



suspend fun Steps.sh(script: String, directory: File = File("."), returnStdout: Boolean = false) {
    val shell = Shell(pipeline)
    val output = shell.executeCommand(script, directory)
    if (returnStdout) {
        println(output)
    }
}

suspend fun Steps.echo(message: String) {
    println(message)
}


