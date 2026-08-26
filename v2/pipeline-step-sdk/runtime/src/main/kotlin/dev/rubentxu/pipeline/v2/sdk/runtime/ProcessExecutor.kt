package dev.rubentxu.pipeline.v2.sdk.runtime

import java.nio.file.Path

/**
 * ProcessBuilder wrapper for executing external commands.
 * Uses List<String> argv (not shell parsing) for security.
 */
class ProcessExecutor {

    fun execute(
        argv: List<String>,
        timeoutMs: Long = 60_000L,  // 60s default; 0L = no timeout per TMO-S-013
        cwd: Path? = null,
        env: Map<String, String> = emptyMap(),
    ): ShellResult {
        val pb = ProcessBuilder(argv)
        if (cwd != null) {
            pb.directory(cwd.toFile())
        }
        if (env.isNotEmpty()) {
            val environment = pb.environment()
            environment.putAll(env)
        }
        pb.redirectErrorStream(false)

        return try {
            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                ShellResult(
                    exitCode = -1,
                    stdout = stdout,
                    stderr = "$stderr\n[TIMEOUT after ${timeoutMs}ms]",
                )
            } else {
                ShellResult(
                    exitCode = process.exitValue(),
                    stdout = stdout,
                    stderr = stderr,
                )
            }
        } catch (e: Exception) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Unknown error",
            )
        }
    }
}
