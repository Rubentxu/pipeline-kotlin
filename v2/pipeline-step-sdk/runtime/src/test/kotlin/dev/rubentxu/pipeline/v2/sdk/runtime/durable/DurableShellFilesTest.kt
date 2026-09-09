package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * LB-02 / S6.8 — durable console-transcript file naming authority.
 *
 * New executions write only `console.log`; `jenkins-log.txt` is never written and exists only
 * behind the read-compatibility boundary ([DurableShellFiles.resolveConsoleLog]) for pre-rename
 * durable operations.
 */
class DurableShellFilesTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `canonical write target is console log`() {
        assertEquals("console.log", DurableShellFiles.CONSOLE_LOG)
        assertEquals(tempDir.resolve("console.log"), DurableShellFiles.consoleLog(tempDir))
    }

    @Test
    fun `resolve returns console log when only console log exists`() {
        Files.createFile(DurableShellFiles.consoleLog(tempDir))
        assertEquals("console.log", DurableShellFiles.resolveConsoleLog(tempDir).fileName.toString())
        assertFalse(Files.exists(tempDir.resolve("jenkins-log.txt")))
    }

    @Test
    fun `resolve falls back to legacy jenkins log when console log absent`() {
        Files.createFile(tempDir.resolve("jenkins-log.txt"))
        assertEquals("jenkins-log.txt", DurableShellFiles.resolveConsoleLog(tempDir).fileName.toString())
    }

    @Test
    fun `resolve prefers console log over legacy when both exist`() {
        Files.createFile(DurableShellFiles.consoleLog(tempDir))
        Files.createFile(tempDir.resolve("jenkins-log.txt"))
        assertEquals("console.log", DurableShellFiles.resolveConsoleLog(tempDir).fileName.toString())
    }

    @Test
    fun `resolve returns canonical console log target when neither exists`() {
        // Read path with no transcript yet resolves to the canonical write target (no bogus file).
        assertEquals("console.log", DurableShellFiles.resolveConsoleLog(tempDir).fileName.toString())
        assertTrue(!Files.exists(tempDir.resolve("console.log")))
    }
}
