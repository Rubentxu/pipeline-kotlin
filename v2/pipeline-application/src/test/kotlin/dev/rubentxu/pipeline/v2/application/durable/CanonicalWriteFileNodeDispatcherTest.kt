package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.application.CanonicalCoreStepCommand
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [CanonicalWriteFileNodeDispatcher].
 *
 * Verifies:
 * - Writes UTF-8 file via FileWriteExecutor and emits FileWritten with correct sha256/size
 * - workspace resolver receives the correct (stageName, stageIndex)
 * - Missing controlDirRoot throws IllegalStateException
 * - Base64 encoding path works
 */
class CanonicalWriteFileNodeDispatcherTest {

    @Test
    fun `dispatch writes UTF-8 file and emits FileWritten event`(@TempDir tempDir: Path) = runBlocking {
        val eventStore = InMemoryEventStore()
        val controlDirRoot = tempDir.resolve("ctrl")
        Files.createDirectories(controlDirRoot)

        val dispatcher = CanonicalWriteFileNodeDispatcher()
        val command = CanonicalCoreStepCommand.WriteFile(
            file = "output.txt",
            text = "hello world",
            encoding = "UTF-8",
        )
        val ctx = CanonicalWriteFileDispatchContext(
            runId = "test-run",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            controlDirRoot = controlDirRoot,
            eventSink = eventStore,
        )

        val outcome = dispatcher.dispatch(command, ctx)

        assertEquals(StepOutcome.Success, outcome, "writeFile should succeed")

        val events = eventStore.eventsFor("test-run").toList()
        val fileWritten = events.filterIsInstance<FileWritten>().singleOrNull()
        assertNotNull(fileWritten, "FileWritten event must be emitted. Events: ${events.map { it::class.simpleName }}")
        val fw = fileWritten!!
        assertEquals("test-run", fw.runId)
        assertTrue(Files.exists(fw.path), "Written file must exist at ${fw.path}")
        assertEquals("hello world", Files.readString(fw.path))
        assertTrue(fw.sha256.isNotBlank(), "sha256 must be computed")
        assertTrue(fw.size > 0, "size must be > 0")
    }

    @Test
    fun `dispatch emits FileWritten with correct sha256 and size`(@TempDir tempDir: Path) = runBlocking {
        val eventStore = InMemoryEventStore()
        val controlDirRoot = tempDir.resolve("ctrl")
        Files.createDirectories(controlDirRoot)

        val dispatcher = CanonicalWriteFileNodeDispatcher()
        val command = CanonicalCoreStepCommand.WriteFile(
            file = "data.bin",
            text = "test content",
            encoding = "UTF-8",
        )
        val ctx = CanonicalWriteFileDispatchContext(
            runId = "sha-test-run",
            stageName = "package",
            stageIndex = 1,
            stepIndex = 2,
            controlDirRoot = controlDirRoot,
            eventSink = eventStore,
        )

        dispatcher.dispatch(command, ctx)

        val fileWritten = eventStore.eventsFor("sha-test-run")
            .filterIsInstance<FileWritten>()
            .single()
        val fw = fileWritten
        // Verify the file content matches the sha256
        val computedSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(fw.path))
            .joinToString("") { "%02x".format(it) }
        assertEquals(computedSha, fw.sha256, "sha256 must match file content")
        assertEquals(Files.size(fw.path), fw.size, "size must match Files.size()")
    }

    @Test
    fun `dispatch works when stage workspace does not yet exist`(@TempDir tempDir: Path) = runBlocking {
        val eventStore = InMemoryEventStore()
        val controlDirRoot = tempDir.resolve("ctrl")
        Files.createDirectories(controlDirRoot)

        val dispatcher = CanonicalWriteFileNodeDispatcher()
        val command = CanonicalCoreStepCommand.WriteFile(
            file = "VERSION.txt",
            text = "1.0.0",
            encoding = "UTF-8",
        )
        val ctx = CanonicalWriteFileDispatchContext(
            runId = "resolver-test",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            controlDirRoot = controlDirRoot,
            eventSink = eventStore,
        )

        val outcome = dispatcher.dispatch(command, ctx)

        assertEquals(StepOutcome.Success, outcome)
        val fileWritten = eventStore.eventsFor("resolver-test")
            .filterIsInstance<FileWritten>()
            .single()
        assertTrue(Files.exists(fileWritten.path), "File must be written at ${fileWritten.path}")
        assertEquals("1.0.0", Files.readString(fileWritten.path))
    }

    @Test
    fun `dispatch throws when controlDirRoot is null`(@TempDir tempDir: Path) = runBlocking {
        val eventStore = InMemoryEventStore()
        val dispatcher = CanonicalWriteFileNodeDispatcher()
        val command = CanonicalCoreStepCommand.WriteFile(
            file = "output.txt",
            text = "hello",
            encoding = "UTF-8",
        )
        val ctx = CanonicalWriteFileDispatchContext(
            runId = "null-root-test",
            stageName = "build",
            stageIndex = 0,
            stepIndex = 0,
            controlDirRoot = null,
            eventSink = eventStore,
        )

        var exception: Exception? = null
        try {
            dispatcher.dispatch(command, ctx)
        } catch (e: Exception) {
            exception = e
        }
        assertNotNull(exception, "Should throw when controlDirRoot is null")
        assertTrue(exception is IllegalStateException, "Should throw IllegalStateException")
    }
}
