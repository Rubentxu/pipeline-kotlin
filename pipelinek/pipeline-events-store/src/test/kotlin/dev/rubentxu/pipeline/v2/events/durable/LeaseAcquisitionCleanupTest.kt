package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path

/**
 * The acquisition of a lease, and the resources it must never leave behind.
 *
 * ## What this test is for
 *
 * Taking a lease holds two resources — the open channel and the lock taken over it — from the
 * moment the lock file is opened until the store takes responsibility for them. Every step in
 * between can fail, and a failure that leaves the lock behind is not a failed acquisition: it
 * is a run that nobody can acquire again, because the kernel still sees the process as the
 * holder. The next acquisition of that run is answered "somebody else owns it", by a process
 * that does not exist.
 *
 * The cleanup used to be spread over four places — one per failure mode — and each of the four
 * could be forgotten by the next change. This test pins the property that closes all of them
 * at once: **an acquisition that does not hand the lease over gives back everything it took.**
 *
 * ## Why a double and not real files
 *
 * The unit of the guarantee is the WINDOW between opening the lock file and handing the lock to
 * the store. A test on real files could only exercise the window by provoking a real I/O
 * failure, which is neither reproducible nor deterministic. The double models the machine
 * faithfully where it matters — the kernel's lock table is one entry, so a second acquisition
 * while the first is alive is refused exactly as the operating system refuses it — and can be
 * told to fail at the exact step the real machine would fail at.
 *
 * So the instrument asks the question the guarantee is about: after a failure at step N, is the
 * run still takeable?
 */
class LeaseAcquisitionCleanupTest {

    @Test
    fun `a failure taking the lock gives the channel back`() {
        val files = FakeLeaseFileSystem().apply { failOnLock = true }
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        assertThrows(IOException::class.java) { store.acquire(request()) }

        assertEquals(1, files.channelsClosed, "the channel must be given back")
        assertFalse(store.isHolding, "the store must not claim a lease it never took")
    }

    @Test
    fun `a failure reading the previous record gives the lock back`() {
        val files = FakeLeaseFileSystem().apply { failOnRead = true }
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        assertThrows(IOException::class.java) { store.acquire(request()) }

        assertEquals(1, files.locksGivenBack, "the lock must be given back")
        assertFalse(files.lockHeld, "the kernel must not still see this process as the holder")
        assertFalse(store.isHolding, "the store must not claim a lease it never took")
    }

    @Test
    fun `a failure writing the ownership record gives the lock back`() {
        val files = FakeLeaseFileSystem().apply { failOnWrite = true }
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        assertThrows(IOException::class.java) { store.acquire(request()) }

        assertEquals(1, files.locksGivenBack, "the lock must be given back")
        assertFalse(files.lockHeld, "the kernel must not still see this process as the holder")
    }

    @Test
    fun `a failure writing the ownership record does not advance the fencing token`() {
        val files = FakeLeaseFileSystem().apply { failOnWrite = true }
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        assertThrows(IOException::class.java) { store.acquire(request()) }

        assertEquals(0, files.writeCount, "the record must not have been written")
        assertNull(files.record, "a failed write must leave no ownership behind")

        // The token belongs to what was WRITTEN. Nothing was written, so the run
        // still carries the token it had before the failed acquisition.
        files.failOnWrite = false
        val second = store.acquire(request())
        val acquired = assertInstanceOf(LeaseAcquisition.Acquired::class.java, second, "the run must still be takeable")
        assertEquals(FencingToken.FIRST, acquired.fencingToken, "the token must not have advanced")
    }

    @Test
    fun `a verdict that grants no ownership gives the lock back`() {
        // The record on disk belongs to ANOTHER run. The acquisition must not
        // answer it, and the lock it took on the way must go back: a refusal
        // that kept the lock would leave a run nobody can acquire again.
        val files = FakeLeaseFileSystem().apply {
            seed(LeaseRecord(RunId("other-run"), OWNER, FencingToken.FIRST, ownerAlive = true))
        }
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        val verdict = store.acquire(request(runId = "run-1"))

        assertTrue(
            verdict is LeaseAcquisition.Unverifiable,
            "a record of another run must not be a lease, but the verdict was $verdict",
        )
        assertEquals(1, files.locksGivenBack, "the lock must be given back")
        assertFalse(files.lockHeld, "the kernel must not still see this process as the holder")
        assertFalse(store.isHolding, "the store must not claim a lease it never took")
    }

    @Test
    fun `the next acquisition of the same run progresses after a failure`() {
        val files = FakeLeaseFileSystem().apply { failOnRead = true }
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        assertThrows(IOException::class.java) { store.acquire(request()) }

        // The run must not be locked for ever by a failure that already happened.
        files.failOnRead = false
        val second = store.acquire(request())

        assertTrue(
            second is LeaseAcquisition.Acquired,
            "the run must be takeable again, but the verdict was $second",
        )
    }

    @Test
    fun `a granted acquisition keeps the lock in the store's hands`() {
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        val verdict = store.acquire(request())

        assertTrue(verdict is LeaseAcquisition.Acquired, "the verdict was $verdict")
        assertTrue(store.isHolding, "the store must own the lease it was granted")
        assertTrue(files.lockHeld, "the kernel must see this process as the holder")
        assertEquals(0, files.locksGivenBack, "a granted lease must not be given back")
        assertEquals(0, files.channelsClosed, "the channel stays open while the store holds the lease")
        assertEquals(OWNER, files.record?.ownerId, "the record must name the owner")
        assertEquals(FencingToken.FIRST, files.record?.fencingToken, "the first owner takes token 1")
    }

    @Test
    fun `closing the store gives the lock back exactly once`() {
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)
        store.acquire(request())

        store.close()
        store.close()

        assertEquals(1, files.locksGivenBack, "the lock must be given back exactly once")
        assertFalse(files.lockHeld, "the kernel must not still see this process as the holder")
        assertFalse(store.isHolding, "a closed store holds nothing")
    }

    private fun request(runId: String = "run-1"): LeaseRequest =
        LeaseRequest(runId = RunId(runId), ownerId = OWNER)

    private companion object {
        val LEASE_DIR: Path = Path.of("leases")
        val OWNER: RunOwnerId = RunOwnerId.of("owner-a")!!
    }
}
