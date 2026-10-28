package dev.rubentxu.pipeline.v2.events.durable

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The machine underneath a lease: an exclusive OS lock and a durable ownership record.
 *
 * ## Why this is a door and not a detail
 *
 * An acquisition holds TWO resources — the open channel and the lock taken over it — from the
 * moment the lock file is opened until the store takes responsibility for them. Every step in
 * between can fail, and a failure that leaves the lock behind is not a failed acquisition: it is
 * a run that nobody can acquire again, because the kernel still sees this process as the holder.
 *
 * The symptom is invisible from the outside. The caller sees an exception, the run fails, and
 * the NEXT acquisition of the same run is answered "somebody else owns it" — by a process that
 * does not exist. A run that can never be recovered is a much worse failure than a run that
 * failed loudly.
 *
 * So the guarantee has to be proved where it lives: the window between opening the lock file
 * and handing the lock to the store. The cheapest instrument that can fail for THAT window is
 * a double of this door: it can be told to throw at the exact step the real machine would, and
 * it can be asked afterwards whether the lock was given back. A test on real files would be
 * proving that the filesystem works.
 *
 * ## What this door does NOT decide
 *
 * Who owns the run and which fencing token is current is decided by [RunExecutionLease], which
 * stays pure. This door moves bytes and takes locks; it never decides.
 */
interface LeaseFileSystem {

    /**
     * Make sure [leaseDir] exists, so the lock file can be created inside it.
     *
     * @throws IOException when the directory cannot be created.
     */
    fun ensureDirectory(leaseDir: Path)

    /**
     * Open [lockFile], CREATING it when it is absent, and return the channel to lock over.
     *
     * The caller owns the returned channel and must give it back, whatever happens next. That
     * ownership is the whole reason this method exists separately from [LeaseChannel.tryLock]:
     * a failure to take the lock has to leave the caller holding a channel it can close.
     */
    fun openLockFile(lockFile: Path): LeaseChannel

    /**
     * Open [lockFile] WITHOUT creating it and try the exclusive lock over it.
     *
     * `null` when the lock is held — by another process or by this JVM. The file is given back
     * either way, so a caller that receives `null` owns nothing. Unlike [openLockFile] this
     * never creates: a probe that creates the file it is probing has written, and a caller that
     * promised to write nothing must not write on a run that never held a lease.
     */
    fun probeLock(lockFile: Path): LeaseLock?

    /** Whether [path] is a regular file. */
    fun fileExists(path: Path): Boolean

    /** The durable ownership record, or `null` when there is none. */
    fun readRecord(recordFile: Path): LeaseRecord?

    /** Write the durable ownership record. */
    fun writeRecord(recordFile: Path, record: LeaseRecord)
}

/**
 * An open channel over the lock file, and the exclusive lock taken over it.
 *
 * The channel and the lock are one thing to give back, because in the operating system they
 * are: closing the channel releases every lock this process holds over it. The two are
 * therefore never separated by the caller, and a [LeaseLock] carries its own channel.
 */
interface LeaseChannel : AutoCloseable {

    /**
     * Try the EXCLUSIVE lock over this channel.
     *
     * `null` when the lock is already held — by another process or by this JVM. The channel
     * stays open and belongs to the caller.
     *
     * @throws IOException when the lock could not be taken for a reason that is not a conflict.
     */
    fun tryLock(): LeaseLock?

    /** Give the channel back. Closing an already closed channel is a no-op. */
    override fun close()
}

/**
 * An exclusive lock and the channel it lives on, given back together.
 *
 * Giving it back twice is a no-op, not a failure.
 */
interface LeaseLock : AutoCloseable {

    /** Release the lock and close its channel. */
    override fun close()
}

/**
 * The real machine: an OS file lock, and the flat text record that outlives it.
 *
 * The record format is deliberately a flat `key=value` text file: it is written while the OS lock
 * is held, read by an operator with `cat`, and must survive a `SIGKILL` mid-run. A JSON or
 * binary format would buy nothing here and cost inspectability.
 */
class FileLeaseFileSystem : LeaseFileSystem {

    override fun ensureDirectory(leaseDir: Path) {
        Files.createDirectories(leaseDir)
    }

    override fun openLockFile(lockFile: Path): LeaseChannel = FileLeaseChannel(
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ),
    )

    override fun probeLock(lockFile: Path): LeaseLock? {
        val channel = FileLeaseChannel(
            FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE),
        )
        val lock = channel.tryLock()
        if (lock == null) {
            // The lock is not ours: the channel that was opened to ask goes straight back.
            channel.close()
        }
        return lock
    }

    override fun fileExists(path: Path): Boolean = Files.isRegularFile(path)

    override fun readRecord(recordFile: Path): LeaseRecord? {
        if (!Files.isRegularFile(recordFile)) return null
        val fields = Files.readAllLines(recordFile)
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
        val runId = fields["run_id"]?.takeIf { it.isNotBlank() } ?: return null
        val owner = fields["owner_id"]?.takeIf { it.isNotBlank() }?.let { RunOwnerId.of(it) }
        val token = fields["fencing_token"]?.toLongOrNull()?.let { FencingToken.of(it) }
            ?: FencingToken.UNISSUED
        val alive: Boolean? = when (fields["owner_alive"]?.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        return LeaseRecord(runId, owner, token, alive)
    }

    override fun writeRecord(recordFile: Path, record: LeaseRecord) {
        val body = buildString {
            appendLine("run_id=${record.runId}")
            appendLine("owner_id=${record.ownerId?.value.orEmpty()}")
            appendLine("fencing_token=${record.fencingToken.value}")
            appendLine("owner_alive=${record.ownerAlive ?: ""}")
        }
        // Write-and-rename so a crash mid-write cannot leave a half record
        // that parses as a valid but wrong ownership claim.
        val tmp = recordFile.resolveSibling(recordFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, body)
        Files.move(
            tmp,
            recordFile,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}

/**
 * A real channel, and the real lock taken over it.
 *
 * `tryLock` answers `null` for an [OverlappingFileLockException] — the same JVM already holds
 * the lock, which is a real ownership conflict and not an infrastructure failure — and lets every
 * other failure through, because an `IOException` there says nothing about ownership and must
 * not be dressed up as a refusal.
 */
private class FileLeaseChannel(private val channel: FileChannel) : LeaseChannel {

    private var closed = false

    override fun tryLock(): LeaseLock? {
        val lock: FileLock = try {
            channel.tryLock() ?: return null
        } catch (_: OverlappingFileLockException) {
            return null
        }
        return FileLeaseLock(channel, lock)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
    }
}

/**
 * A real lock and the channel it lives on, given back together and only once.
 */
private class FileLeaseLock(
    private val channel: FileChannel,
    private val lock: FileLock,
) : LeaseLock {

    private var givenBack = false

    override fun close() {
        if (givenBack) return
        givenBack = true
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}
