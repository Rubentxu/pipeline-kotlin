package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RunIdDirectoryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun directory(): RunIdDirectory = RunIdDirectory(tempDir.resolve("last-run"))

    @Test
    fun `record then lastRunId returns the recorded run id`() {
        val dir = directory()

        dir.record(DefinitionId("abc123"), RunId("run-1"))

        assertEquals(RunId("run-1"), dir.lastRunId(DefinitionId("abc123")))
    }

    @Test
    fun `lastRunId with no record fails closed with an actionable message`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            directory().lastRunId(DefinitionId("never-recorded"))
        }

        assertTrue(ex.message!!.contains("No prior run recorded"))
        assertTrue(ex.message!!.contains("--resume"))
    }

    @Test
    fun `re-recording the same definition replaces the previous run id`() {
        val dir = directory()

        dir.record(DefinitionId("abc123"), RunId("run-1"))
        dir.record(DefinitionId("abc123"), RunId("run-2"))

        assertEquals(RunId("run-2"), dir.lastRunId(DefinitionId("abc123")))
    }

    @Test
    fun `records for different definitions are isolated`() {
        val dir = directory()

        dir.record(DefinitionId("def-a"), RunId("run-a"))
        dir.record(DefinitionId("def-b"), RunId("run-b"))

        assertEquals(RunId("run-a"), dir.lastRunId(DefinitionId("def-a")))
        assertEquals(RunId("run-b"), dir.lastRunId(DefinitionId("def-b")))
    }

    @Test
    fun `blank recorded content fails closed`() {
        val dir = directory()
        dir.record(DefinitionId("abc123"), RunId("run-1"))
        // Simulate corruption: blank out the record file behind the API's back.
        java.nio.file.Files.writeString(tempDir.resolve("last-run").resolve("abc123"), "   ")

        val ex = assertThrows(IllegalArgumentException::class.java) {
            dir.lastRunId(DefinitionId("abc123"))
        }

        assertTrue(ex.message!!.contains("blank"))
    }

    @Test
    fun `definition id with path-unsafe characters is rejected`() {
        val dir = directory()

        assertThrows(IllegalArgumentException::class.java) {
            dir.record(DefinitionId("../../etc/passwd"), RunId("run-1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            dir.lastRunId(DefinitionId("with spaces"))
        }
    }

    @Test
    fun `directory is created lazily on first record`() {
        val nested = tempDir.resolve("a").resolve("b").resolve("last-run")
        val dir = RunIdDirectory(nested)

        dir.record(DefinitionId("abc123"), RunId("run-1"))

        assertTrue(java.nio.file.Files.isRegularFile(nested.resolve("abc123")))
    }

    // -------------------------------------------------------------- RUN-01

    /**
     * RUN-01 witness: concurrent record() calls from many threads must leave the record file
     * in a coherent state — either the old value or one of the new values, never a partial or
     * mixed-content file. Atomic write (temp + rename) is what guarantees this; without it, the
     * truncate-then-write race window of `Files.writeString` could leave the file empty or
     * truncated between the two halves of the call.
     */
    @Test
    fun `RUN-01 concurrent record calls leave the file in a coherent state - no partial writes`() {
        val dir = directory()
        val def = DefinitionId("concurrent-def")
        // Pre-seed so there is always a previous value to overwrite atomically.
        dir.record(def, RunId("seed-0"))

        val writers = (1..16).map { i ->
            Thread {
                repeat(50) { j ->
                    dir.record(def, RunId("run-$i-$j"))
                }
            }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join() }

        val recorded = dir.lastRunId(def)
        // Coherent: must equal ONE of the values that was written (the last writer wins).
        val allExpected = buildSet {
            add("seed-0")
            for (i in 1..16) for (j in 0 until 50) add("run-$i-$j")
        }
        assertTrue(
            recorded.value in allExpected,
            "RUN-01: recorded '${recorded.value}' is not a coherent value written by any thread; " +
                "the file may have been left in a partial/mixed state by the concurrent writes",
        )
        // And the on-disk content must match exactly what we read back (no truncation).
        val onDisk = java.nio.file.Files.readString(tempDir.resolve("last-run").resolve("concurrent-def"))
        assertEquals(
            recorded.value,
            onDisk,
            "RUN-01: on-disk content must equal what lastRunId returns; partial content would " +
                "indicate the write was non-atomic",
        )
    }
}
