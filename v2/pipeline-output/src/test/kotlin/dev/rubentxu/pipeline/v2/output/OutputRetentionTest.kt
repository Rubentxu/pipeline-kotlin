package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * B2 — retention: what may be deleted, and by whose authority.
 *
 * ## The split these tests defend
 *
 * The store can destroy bytes; it cannot decide that they are destroyable. It never learns whether
 * a run is still running, and the moment it guessed, it would become a second authority on run
 * lifecycle — a fact the runtime already owns and can be wrong about in a different direction.
 *
 * So deletion travels as an [OutputPruneIntent], whose constructors are the only permissions that
 * exist. There is deliberately no `prune(runId, force)`, and no case for a live run. **The tests
 * below cannot assert that a running run's output survives, because there is no way to ask for that
 * deletion** — the type system holds the property, and the rows here check the policy that decides
 * which intent the runtime is handed.
 */
class OutputRetentionTest {

    private fun store(root: Path): SegmentOutputStore =
        SegmentOutputStore(root).also { it.recover() }

    private fun SegmentOutputStore.write(stream: OutputStreamId, payload: String) {
        open(stream).reserve(payload.length).apply {
            write(payload.toByteArray(StandardCharsets.UTF_8))
        }.commit()
    }

    // ---------------------------------------------------------------- the policy

    @Test
    fun `RunTerminalPlus keeps a running run and releases a finished one`() {
        assertNull(
            RetainUntil.RunTerminalPlus.authorize("r1", RunLifecycle.StillRunning),
            "a running run's output must never be authorised for deletion",
        )
        val terminal = RetainUntil.RunTerminalPlus.authorize("r1", RunLifecycle.Terminal)
        assertInstanceOf(OutputPruneIntent.RunReachedTerminalState::class.java, terminal, "got $terminal")
        assertEquals("r1", terminal!!.runId)

        // The intent names the run and nothing else. There is deliberately no outcome to assert on
        // here: retention asks "has the run ended?", the engine owns "how did it end?", and a
        // second copy of that answer in a prune intent is how the two would drift apart.
    }

    @Test
    fun `Forever never authorises, not even after the run ends`() {
        // The row that matters: retention holds are enforced by the policy answering `null`, not
        // by a check somewhere downstream that a caller may forget. Forever on a terminal run must
        // still be nothing.
        assertNull(RetainUntil.Forever.authorize("r1", RunLifecycle.StillRunning))
        assertNull(RetainUntil.Forever.authorize("r1", RunLifecycle.Terminal))
    }

    @Test
    fun `ExplicitReleaseOnly never self-authorises and the operator intent is a separate case`() {
        assertNull(RetainUntil.ExplicitReleaseOnly.authorize("r1", RunLifecycle.StillRunning))
        assertNull(RetainUntil.ExplicitReleaseOnly.authorize("r1", RunLifecycle.Terminal))
        // What the operator has instead is an intent they construct themselves, carrying who asked.
        val released = OutputPruneIntent.OperatorReleased("r1", "oncall@example")
        assertEquals("oncall@example", (released as OutputPruneIntent.OperatorReleased).requestedBy)
    }

    // ---------------------------------------------------------------- the deletion

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
