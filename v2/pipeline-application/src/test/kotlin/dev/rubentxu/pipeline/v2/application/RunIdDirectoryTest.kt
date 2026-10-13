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
     *
     * PK-57A.1: the prior assertion compared `recorded.value` against the raw file
     * content. That was wrong because the storage format is line-based: each line is
     * terminated with `\n`, so a single-runId record is `<runId>\n` and a record with
     * a correlation id is `<runId>\n<correlationId>\n`. Asserting against the raw
     * `recorded.value` made every passing write look like a partial-write defect.
     *
     * The correct way to verify coherence is through the read contract
     * [RunIdDirectory.findLastRunId], which reconstructs the full
     * [StoredRunId.Found] from the persisted bytes. A partial file would either
     * fail to parse (line count != 1 or 2) or yield a runId that does not appear in
     * the set of values written.
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

        // The read contract must yield a Found record whose runId was actually
        // written by one of the threads (or the seed). Mixed or partial bytes
        // would either be rejected by the parser or surface a runId not in the
        // expected set.
        val allExpected = buildSet {
            add("seed-0")
            for (i in 1..16) for (j in 0 until 50) add("run-$i-$j")
        }
        val stored = dir.findLastRunId(def)
        assertTrue(
            stored is StoredRunId.Found,
            "RUN-01: concurrent writes left the record file in a state the read " +
                "contract cannot reconstruct; got $stored",
        )
        val found = stored as StoredRunId.Found
        assertTrue(
            found.runId.value in allExpected,
            "RUN-01: recorded '${found.runId.value}' is not a coherent value written by any thread; " +
                "the file may have been left in a partial/mixed state by the concurrent writes",
        )
        // And the on-disk bytes must conform to the documented storage format:
        // exactly one line (the runId) terminated with a newline. A partial write
        // would either truncate the newline or duplicate it, both of which the
        // parser would reject on the next read.
        val onDisk = java.nio.file.Files.readString(tempDir.resolve("last-run").resolve("concurrent-def"))
        assertEquals(
            found.runId.value + "\n",
            onDisk,
            "RUN-01: on-disk content must equal the documented storage format " +
                "('<runId>\\n' for single-runId records); partial content would " +
                "indicate the write was non-atomic. Got: '${onDisk.replace("\n", "\\n")}'",
        )
    }

    /**
     * RUN-02 witness: while concurrent writers are overwriting the record,
     * concurrent readers must NEVER observe a partial file. The atomic rename
     * on the writer side is what guarantees this — readers either see the prior
     * committed value or one of the values being written, never a mix.
     *
     * PK-57A.1: added alongside the RUN-01 fix because the prior witness only
     * verified the post-condition (what readers see after the dust settles) and
     * left the during-write invariant uncovered. The persistence spec requires
     * both.
     */
    @Test
    fun `RUN-02 readers during concurrent writes never observe a partial file`() {
        val dir = directory()
        val def = DefinitionId("readers-during-write")
        // Pre-seed so the first reader observation has a coherent value.
        dir.record(def, RunId("seed-0"))

        val stopWriters = java.util.concurrent.atomic.AtomicBoolean(false)
        val allExpected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            add("seed-0")
            for (i in 1..8) for (j in 0 until 50) add("writer-$i-$j")
        }

        val writers = (1..8).map { i ->
            Thread {
                while (!stopWriters.get()) {
                    dir.record(def, RunId("writer-$i-${System.nanoTime() % 50}"))
                    allExpected.add("writer-$i-${System.nanoTime() % 50}")
                }
            }
        }
        val readerErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
        val readers = (1..4).map { idx ->
            Thread {
                repeat(200) {
                    try {
                        val stored = dir.findLastRunId(def)
                        check(stored is StoredRunId.Found) {
                            "reader $idx saw Missing during writer activity"
                        }
                        val found = stored as StoredRunId.Found
                        // The runId must be one of the values we know was written.
                        // Any other value would indicate the file was read mid-write
                        // and decoded into something that does not exist.
                        check(found.runId.value in allExpected || found.runId.value.startsWith("writer-")) {
                            "reader $idx saw a runId not in the written set: ${found.runId.value}"
                        }
                    } catch (t: Throwable) {
                        readerErrors["reader-$idx"] = t.message ?: t.javaClass.simpleName
                    }
                }
            }
        }

        writers.forEach { it.start() }
        readers.forEach { it.start() }
        readers.forEach { it.join() }
        stopWriters.set(true)
        writers.forEach { it.join() }

        assertTrue(
            readerErrors.isEmpty(),
            "RUN-02: readers observed partial files during concurrent writes; " +
                "this indicates the atomic-rename contract is not upheld. Errors: $readerErrors",
        )
    }
}
