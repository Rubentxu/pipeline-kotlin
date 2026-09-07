package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The in-memory run path (no `--db`) must apply the same fail-closed canonical
 * eligibility gate as the durable path: a non-canonical pipeline is rejected up
 * front with exit 2 instead of dying mid-run with a partial event stream.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CliNonCanonicalInMemoryExitsTwoTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `in-memory run rejects non-canonical pipelines with exit 2 before execution`() {
        val appBin = AppBinSupport.discover()
        val script = tempDir.resolve("non-canonical.pipeline.kts")
        Files.writeString(
            script,
            """
            pipeline {
                stages {
                    stage("workspace") {
                        deleteDir()
                    }
                }
            }
            """.trimIndent(),
        )

        val pb = ProcessBuilder(appBin.toString(), "run", script.toAbsolutePath().toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        assertEquals(2, exitCode, "Non-canonical in-memory run must fail closed with exit 2. stderr: $stderr")
        assertTrue(
            stderr.contains("non-canonical plugins"),
            "stderr must carry the canonical bridge fail-closed message, got: $stderr",
        )
        assertFalse(
            (stdout + stderr).contains("Pipeline finished with"),
            "A rejected pipeline must never execute partially: stdout=$stdout stderr=$stderr",
        )
    }
}
