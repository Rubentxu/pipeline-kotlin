package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputStreamId
import java.nio.file.Files
import java.nio.file.Path

/**
 * The durable residue a **dead** writer leaves behind, written directly.
 *
 * ## Why this exists and why it is not a shortcut
 *
 * These tests used to say "crashed" about something that was alive. They ran a `SegmentOutputStore`,
 * dropped an `OutputReservation` without committing it, and then built a second store over the same
 * root. That models the *bytes* a crash leaves — `cur.res` plus uncommitted payload — but not the
 * thing `ADR-OBS-002` made load-bearing: **ownership**. The first store never died, so it still held
 * `cur.own`, so the second store's `tryWithStreamOwnership` hit `OverlappingFileLockException`,
 * skipped the stream and reported `ownedByLiveWriter`.
 *
 * That skip is correct — reconciling over a live writer is the defect `OBS-G` measured, and the
 * `M-OWN-1` mutation defends exactly that branch. So the fiction had to go, not the guard. Four tests
 * went red on it, and a fifth (`I2 - bytes written but never committed do not appear after recovery`)
 * was green **for the wrong reason**: it passed because recovery was skipped, not because it
 * reconciled, so it was not testing what its name claims.
 *
 * A real crash releases the lock because the kernel drops it when the process dies. Writing the
 * residue directly reproduces the state a crashed process leaves, with nobody holding the lock —
 * which is the state these tests mean to describe, and the only one in which `recover()` is
 * supposed to do its work.
 *
 * ## What is written
 *
 * `cur.cmt` at the acknowledged length, `cur.seg` holding the acknowledged bytes **followed by** the
 * bytes that were never acknowledged, and `cur.res` naming the outstanding range. `reconcile` reads
 * the commit record, sees the segment is longer, and truncates back; `recover` then deletes `cur.res`
 * and counts the release. That is the whole recovery path, and it now runs for real.
 *
 * ## Why writing the layout by hand is acceptable here
 *
 * This is white-box by construction: the helper lives in the same module as the store, the layout is
 * documented in [SegmentOutputStore]'s own KDoc, and the alternative — a lifecycle `close()` that
 * releases the locks — would add public surface to the byte authority to serve a test.
 *
 * The property that genuinely needs **two operating systems** is not weakened by this: it is covered
 * across process boundaries by `ObsPcReadRecoveryOwnershipUatTest` in `pipeline-application`, which
 * launches real JVMs. What moves here is only the single-process unit coverage that had drifted into
 * asserting a fiction.
 */
internal object CrashedResidue {

    /**
     * The store's own [SegmentOutputStore.STREAMS_DIR]. Repeated as a literal because the constant
     * lives in a `private companion`, and widening production visibility so a test can name it would
     * be surface added to the byte authority for the convenience of a fixture.
     */
    private const val STREAMS_DIR = "streams"

    /**
     * Leaves [stream] as a killed writer would: [acknowledged] bytes committed, [unacknowledged]
     * bytes sitting in the segment past them, and a reservation of [reservedBytes] still outstanding.
     *
     * @param unacknowledged pass empty for a writer that died having reserved but written nothing.
     * @param acknowledged pass empty for a writer that died before committing anything at all.
     */
    fun leave(
        root: Path,
        stream: OutputStreamId,
        acknowledged: ByteArray = ByteArray(0),
        unacknowledged: ByteArray = ByteArray(0),
        reservedBytes: Long = 64L * 1024L,
    ) {
        val dir = root.resolve(STREAMS_DIR).resolve(safeStreamName(stream.value))
        Files.createDirectories(dir)
        // The commit record is a bare offset; `committedLocked` trims and parses it.
        Files.writeString(dir.resolve("cur.cmt"), "${acknowledged.size}\n")
        Files.write(dir.resolve("cur.seg"), acknowledged + unacknowledged)
        Files.writeString(
            dir.resolve("cur.res"),
            "${acknowledged.size}|${acknowledged.size + reservedBytes}",
        )
    }
}
