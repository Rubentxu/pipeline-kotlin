package dev.rubentxu.pipeline.v2.events.durable

import java.io.IOException
import java.nio.file.Path

/**
 * The machine under the lease, in memory.
 *
 * One double serves every lease test in this package, and it doubles the
 * MACHINE, not the lease: it knows that the kernel's lock table holds one lock,
 * that closing a channel releases it, and which paths it was asked about. It
 * knows nothing about ownership, tokens or verdicts — those belong to the pure
 * decider, which is what makes the lease testable without a filesystem.
 *
 * Two properties matter to the callers:
 *
 *  - it can fail at the exact step the real machine would fail at, so the window
 *    of an acquisition can be exercised without a real I/O failure;
 *  - it records every path it was asked about, so a test can prove WHICH file a
 *    run was named after — and that a run nobody could name never reached the
 *    machine at all.
 */
internal class FakeLeaseFileSystem : LeaseFileSystem {

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

    /** Every path the store asked the machine about, in order. */
    val pathsAsked: MutableList<Path> = mutableListOf()

    /** How many times a directory was created. */
    var directoriesEnsured = 0
        private set

    /** A record that was already on disk before the call under test. */
    fun seed(record: LeaseRecord) {
        this.record = record
    }

    /** The lock was given back. */
    fun noteLockGivenBack() {
        locksGivenBack++
    }

    /** The channel was closed. */
    fun noteChannelClosed() {
        channelsClosed++
    }

    override fun ensureDirectory(leaseDir: Path) {
        directoriesEnsured++
        pathsAsked.add(leaseDir)
    }

    override fun openLockFile(lockFile: Path): LeaseChannel {
        pathsAsked.add(lockFile)
        return newChannel()
    }

    override fun probeLock(lockFile: Path): LeaseLock? {
        pathsAsked.add(lockFile)
        if (lockHeld) return null
        lockHeld = true
        return FakeLeaseLock(newChannel(), this)
    }

    fun newChannel(): FakeLeaseChannel = FakeLeaseChannel(this)

    override fun fileExists(path: Path): Boolean {
        pathsAsked.add(path)
        return false
    }

    override fun readRecord(recordFile: Path): LeaseRecord? {
        pathsAsked.add(recordFile)
        if (failOnRead) throw IOException("the ownership record could not be read")
        return record
    }

    override fun writeRecord(recordFile: Path, record: LeaseRecord) {
        pathsAsked.add(recordFile)
        if (failOnWrite) throw IOException("the ownership record could not be written")
        this.record = record
        writeCount++
    }
}

/**
 * The channel the machine hands over. It knows how to lock itself, and it
 * knows that closing it releases the lock: that is what the operating system
 * does, and the guarantees under test depend on it.
 */
internal class FakeLeaseChannel(private val system: FakeLeaseFileSystem) : LeaseChannel {

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
internal class FakeLeaseLock(
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
