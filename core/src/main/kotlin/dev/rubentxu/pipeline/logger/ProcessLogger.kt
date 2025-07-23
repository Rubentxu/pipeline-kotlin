package dev.rubentxu.pipeline.logger

import dev.rubentxu.pipeline.logger.interfaces.ILoggerManager
import kotlinx.coroutines.*

/**
 * Pipes the stdout and stderr of a `Process` to the ILoggerManager's stream
 * in a non-blocking and efficient way.
 *
 * @param process The running process to monitor.
 * @param loggerManager The central logger manager to send events to.
 * @param loggerName A name to associate with these log events (e.g., "shell-script").
 * @return A `Job` that represents the ongoing piping work. You can `join()` it to wait for completion.
 */
suspend fun pipeProcessToLogger(
    process: Process,
    loggerManager: ILoggerManager,
    loggerName: String
): Job {
    // Get a logger that captures the current coroutine's context
    val logger = loggerManager.getLogger(loggerName)

    // Launch the reading of the streams in the I/O dispatcher
    return CoroutineScope(Dispatchers.IO).launch {
        // Launch a coroutine for stdout
        launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.info(it) } // Each line is an INFO log
            }
        }
        // Launch a coroutine for stderr
        launch {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.error(it) } // Each line is a WARN log
            }
        }
    }
}