package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputCursor
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputReadResult
import dev.rubentxu.pipeline.v2.output.OutputRefusal
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * B2 — retention DELETION: what a prune actually does to bytes.
 *
 * Split from `OutputRetentionTest` by the BLOCK 2 module boundary, not by taste. The policy that
 * decides *which* deletion is legal is pure and lives in `:pipeline-output`; the rows here need a
 * real store, a real filesystem and a real recovery gate, so they live beside the store they
 * exercise. The property under test is unchanged by the move: a deletion is possible only through
 * a named intent, and the store never learns whether a run is alive.
 */
class OutputPruneTest {

    private fun store(root: Path): SegmentOutputStore =
        SegmentOutputStore(root).also { it.recover() }

    private fun SegmentOutputStore.write(stream: OutputStreamId, payload: String) {
        open(stream).reserve(payload.length).apply {
            write(payload.toByteArray(StandardCharsets.UTF_8))
        }.commit()
    }

    @Test
    fun `pruning a finished run removes its streams and reports the committed bytes`(@TempDir root: Path) {
        val store = store(root)
        val run = "run-alpha"
        store.write(OutputStreamId("$run/step-0"), "first transcript\n")
        store.write(OutputStreamId("$run/step-1"), "second transcript, longer\n")

        val report = store.prune(OutputPruneIntent.RunReachedTerminalState(run))

        assertEquals(2, report.streamsRemoved, "both of the run's streams must go")
        assertEquals(0, report.streamsRetained, "nothing may resist a deletion the filesystem allows")
        // Counted from the committed offset, not from file size: the report is what a caller uses
        // to confirm data is gone, so it must be the number the store actually promised readers.
        assertEquals(
            "first transcript\n".length + "second transcript, longer\n".length.toLong(),
            report.bytesReleased,
        )
        assertFalse(store.hasOutputFor(run), "the run must hold no output after its release")
    }

    @Test
    fun `a released run is unknown to a reader, not a short read`(@TempDir root: Path) {
        val store = store(root)
        val run = "run-beta"
        val stream = OutputStreamId("$run/step-0")
        store.write(stream, "bytes that are about to stop existing\n")

        store.prune(OutputPruneIntent.RunReachedTerminalState(run))

        // UnknownStream, not an empty page. A reader handed zero bytes here could not tell a
        // released transcript from a silent process, and that is the one difference a console has
        // to be able to make.
        val result = store.read(stream, OutputCursor.start(stream), 64)
        val reason = assertInstanceOf(OutputReadResult.Refused::class.java, result, "got $result").reason
        assertInstanceOf(OutputRefusal.UnknownStream::class.java, reason, "got $reason")
        assertNull(store.committedExtent(stream))
    }

    @Test
    fun `releasing one run never touches another run's output`(@TempDir root: Path) {
        val store = store(root)
        val kept = "run-kept"
        val dropped = "run-dropped"
        store.write(OutputStreamId("$kept/step-0"), "must survive\n")
        store.write(OutputStreamId("$dropped/step-0"), "must go\n")

        val report = store.prune(OutputPruneIntent.RunReachedTerminalState(dropped))

        assertEquals(1, report.streamsRemoved)
        assertTrue(store.hasOutputFor(kept), "a neighbour run must be untouched")
        // Read the survivor rather than counting directories: "the directory still exists" is not
        // the claim, "its bytes are still readable" is.
        val stream = OutputStreamId("$kept/step-0")
        val read = assertInstanceOf(
            OutputReadResult.Page::class.java,
            store.read(stream, OutputCursor.start(stream), 64),
            "the surviving run must still be readable",
        )
        assertEquals("must survive\n", String(read.page.bytes, StandardCharsets.UTF_8))
    }

    @Test
    fun `releasing a run with no output is a no-op report, never an error`(@TempDir root: Path) {
        val store = store(root)
        val report = store.prune(OutputPruneIntent.OperatorReleased("run-never-existed", "tester"))
        assertEquals(0, report.streamsRemoved)
        assertEquals(0L, report.bytesReleased)
        assertEquals(0, report.streamsRetained)
    }

    @Test
    fun `retention obeys the same recovery gate as reads and appends`(@TempDir root: Path) {
        // O3 is a property of the store, not of one port. A retention path that skipped the gate
        // could delete a run's output while a concurrent recovery was still reconciling it.
        val unrecovered = SegmentOutputStore(root)
        assertThrows(IllegalStateException::class.java) {
            unrecovered.prune(OutputPruneIntent.RunReachedTerminalState("r"))
        }
    }

    @Test
    fun `a run that never wrote anything is absent rather than empty`(@TempDir root: Path) {
        // Consistent with the measured contract from B2a: the plane holds a stream only when bytes
        // were written, so "has output" is a real question with a real yes and a real no.
        val store = store(root)
        assertFalse(store.hasOutputFor("run-silent"), "a run that wrote nothing holds nothing")
        assertNotNull(store, "sanity: the store is usable")
    }
}
