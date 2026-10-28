package dev.rubentxu.pipeline.v2.events.durable

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
        val files = FakeLeaseFileSystem()
        val store = FileBackedRunExecutionLeaseStore(LEASE_DIR, files)

        val verdict = store.acquire(request(runId = "  "))

        assertTrue(verdict is LeaseAcquisition.Unverifiable, "a blank run id must not be a lease")
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
        LeaseRequest(runId = runId, ownerId = OWNER)

    /**
     * The machine, with the one property that matters for this guarantee: the
     * kernel's lock table holds ONE lock, so a second acquisition while the
     * first is alive is refused exactly as the operating system refuses it.
     */
    private class FakeLeaseFileSystem : LeaseFileSystem {

        var failOnLock = false
        var failOnRead = false
        var failOnWrite = false

        /** Whether the kernel considers the lock taken. */
        var lockHeld = false

        var record: LeaseRecord? = null
            private set
        var writeCount = 0
            private set
        var locksGivenBack = 0
            private set
        var channelsClosed = 0
            private set

        /** The lock was given back. */
        fun noteLockGivenBack() {
            locksGivenBack++
        }

        /** The channel was closed. */
        fun noteChannelClosed() {
            channelsClosed++
        }

        override fun ensureDirectory(leaseDir: Path) = Unit

        override fun openLockFile(lockFile: Path): LeaseChannel = newChannel()

        override fun probeLock(lockFile: Path): LeaseLock? {
            if (lockHeld) return null
            lockHeld = true
            return FakeLeaseLock(newChannel(), this)
        }

        fun newChannel(): FakeLeaseChannel = FakeLeaseChannel(this)

        override fun fileExists(path: Path): Boolean = false

        override fun readRecord(recordFile: Path): LeaseRecord? {
            if (failOnRead) throw IOException("the ownership record could not be read")
            return record
        }

        override fun writeRecord(recordFile: Path, record: LeaseRecord) {
            if (failOnWrite) throw IOException("the ownership record could not be written")
            this.record = record
            writeCount++
        }
    }

    /**
     * The channel the machine hands over. It knows how to lock itself, and it
     * knows that closing it releases the lock: that is what the operating
     * system does, and the guarantee under test depends on it.
     */
    private class FakeLeaseChannel(private val system: FakeLeaseFileSystem) : LeaseChannel {

        var closed = false
            private set

        override fun tryLock(): LeaseLock? {
            if (system.failOnLock) throw IOException("the lock could not be taken")
            if (system.lockHeld) return null
            system.lockHeld = true
            return FakeLeaseLock(this, system)
        }

        override fun close() {
            if (closed) return
            closed = true
            system.noteChannelClosed()
            system.lockHeld = false
        }
    }

    /** The lock, and the channel it lives on, given back together and only once. */
    private class FakeLeaseLock(
        private val channel: FakeLeaseChannel,
        private val system: FakeLeaseFileSystem,
    ) : LeaseLock {

        var givenBack = false
            private set

        override fun close() {
            if (givenBack) return
            givenBack = true
            system.noteLockGivenBack()
            channel.close()
        }
    }

    private companion object {
        val LEASE_DIR: Path = Path.of("leases")
        val OWNER: RunOwnerId = RunOwnerId.of("owner-a")!!
    }
}
