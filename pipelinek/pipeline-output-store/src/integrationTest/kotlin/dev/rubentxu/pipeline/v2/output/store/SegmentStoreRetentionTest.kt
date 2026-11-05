package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputPruneIntent
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The guarantee: a release authorised for one run releases THAT run's bytes.
 *
 * ## The capacity this serves
 * A run that has finished may have its console output discarded. The store may delete those bytes
 * and may not delete anybody else's: the release is authorised for a run, so the bytes it takes
 * have to be that run's. The bytes of a run that merely starts with the same characters are not.
 *
 * ## Why this test is an INTEGRATION test and not a unit test
 * The rule that decides is pure and is exercised as such. What is under trial HERE is the wiring:
 * the store walks a real directory, reads what the frame index declares about each entry, and
 * deletes what the rule says is the run's. That agreement cannot be seen from a function that takes
 * a name and a record — it is the store's own walk over a real filesystem.
 *
 * ## What is faked, and what is not
 * The world outside the modules is faked: the Output Plane lives in a temporary directory the test
 * owns, and the pin substrate is a consult that answers "nobody holds this directory" — a port the
 * world outside answers, not a module under test. Nothing that belongs to the store is faked: the
 * store, the frame index and the filesystem are the real ones.
 *
 * ## The oracle
 * The assertions read the FILESYSTEM and not the store's own answers. A test that asked the store
 * whether it still held the bytes would be answered by the very rule under trial.
 */
class SegmentStoreRetentionTest {

    @TempDir
    lateinit var root: Path

    /**
     * The store under test.
     *
     * ONE instance for the whole test, because the reconciled state belongs to the store and not to
     * the directory: a second store over the same directory has not reconciled anything, and it
     * refuses to write rather than guess.
     */
    private lateinit var store: SegmentOutputStore

    @BeforeEach
    fun reconcile() {
        store = SegmentOutputStore(
            root = root,
            pinConsult = object : DirectoryPinConsult {
                override fun pinsOfDirectory(directoryName: String): DirectoryPinAnswer =
                    DirectoryPinAnswer.Answered(emptyList())
            },
        )
        store.recover()
    }

    @Test
    fun `the release of a run removes that run's own stream directory`() {
        val stream = OutputStreamId("alpha/step1/stdout")
        declare(stream)
        commit(stream, "the bytes of the run that asked for them".toByteArray())

        val report = store.prune(OutputPruneIntent.RunReachedTerminalState("alpha"))

        assertEquals(
            1,
            report.streamsRemoved,
            "the release removed nothing of the run it was authorised to release",
        )
        assertTrue(
            report.bytesReleased > 0,
            "the release removed the directory but reported no bytes: the report would tell a " +
                "caller that data is gone while it is still there",
        )
        assertFalse(
            Files.exists(streamDir(stream)),
            "the directory the release was authorised to delete is still on disk",
        )
    }

    @Test
    fun `the release of a run leaves the directory another run declared alone`() {
        // The two names differ by a suffix, so the shorter run's prefix carries the longer run's
        // directory name. The longer run DECLARED it, so the declaration answers and the prefix
        // never gets to claim it.
        val mine = OutputStreamId("alpha/step1/stdout")
        val theirs = OutputStreamId("alpha_x/step1/stdout")
        declare(mine)
        declare(theirs)
        commit(mine, "the bytes of the run that asked for them".toByteArray())
        commit(theirs, "the bytes of another run entirely".toByteArray())

        val report = store.prune(OutputPruneIntent.RunReachedTerminalState("alpha"))

        assertEquals(
            1,
            report.streamsRemoved,
            "the release removed a number of directories that is not the run's own",
        )
        assertFalse(
            Files.exists(streamDir(mine)),
            "the directory the release was authorised to delete is still on disk",
        )
        assertTrue(
            Files.exists(streamDir(theirs)),
            "the release of a run deleted the bytes of another run whose name starts the same way",
        )
    }

    /**
     * Declare [stream] to its run, the way the durable route declares the streams it writes.
     */
    private fun declare(stream: OutputStreamId) {
        store.frameIndex().declareStream(stream, OutputChannel.STDOUT)
    }

    /**
     * Writes [payload] through the real store and commits it.
     *
     * The order is the product's order: a durable reservation, the bytes inside it, and a commit
     * that publishes them. A reservation that is never committed is not visible to a reader, and a
     * release that claims a directory holds bytes only when the commit says so.
     */
    private fun commit(stream: OutputStreamId, payload: ByteArray) {
        val handle = store.open(stream)
        val reservation = handle.reserve(payload.size)
        reservation.write(payload)
        reservation.commit()
    }

    /**
     * The directory that holds [stream]'s bytes.
     *
     * The fold is the store's own rule, so the test does not keep a second copy of it. The
     * directory the streams live in is the store's private name, so the test states it — and it is
     * the only name in the assertion that the test does not derive.
     */
    private fun streamDir(stream: OutputStreamId): Path =
        root.resolve(STREAMS_DIR).resolve(safeStreamName(stream.value))

    private companion object {
        /** The directory under the output-plane root that holds the stream directories. */
        const val STREAMS_DIR = "streams"
    }
}
