package dev.rubentxu.pipeline.v2.application

import java.io.FileNotFoundException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LockCoordinator] backed by POSIX file locks (RP6-A / WU-091).
 *
 * ## Why a file and not a durable queue
 *
 * Measured, not assumed: PipelineK has no controller and no worker (that is RP-8),
 * and `parallel` is coroutines inside ONE run. The contention that actually exists
 * today is therefore (a) branches of `parallel` in the same process and (b) two runs
 * in two processes on the same host. A file lock covers both, and nothing else.
 *
 * The decisive property is that **the OS owns the hold's lifetime**. There is no
 * durable row that a killed process can leave behind, so there is no stale lease to
 * expire and no recovery step to get wrong. That is the reason this backend is
 * chosen over a database queue, not convenience.
 *
 * What it deliberately does NOT do: order waiters. `priority` and
 * `inversePrecedence` need a queue, and a queue between hosts is an RP-8
 * requirement. Building one now would be building RP-8 by convenience.
 *
 * ## Re-entrancy: keyed by DURABLE owner, not by process
 *
 * Re-entrancy follows [LockOwner], never the process, thread or coroutine. A
 * second RUN in the same JVM is a different owner and must contend through the
 * OS lock like anyone else; only the same owner re-enters.
 *
 * This was decided here because Jenkins is re-entrant per build
 * (`LockableResourcesManager` is per build), so `lock("a") { lock("a") { ... } }`
 * inside one run runs the inner body. A backend that denied it would skip a body
 * silently and still report Success.
 *
 * The in-process map is not optional bookkeeping: a `FileLock` is owned by the
 * JVM, not the thread, so a second `tryLock` from the same JVM throws
 * [OverlappingFileLockException] instead of reporting contention. Some registry
 * keyed by owner is unavoidable, and counting depth in it is barely more work
 * than flagging a boolean — and it is what makes
 * [LockAdmission.Acquired.reentrant] a real value rather than a field nobody
 * sets.
 */
class FileLockCoordinator(
    private val lockRoot: Path,
) : LockCoordinator {

    /**
     * Key of a hold: the canonical lock FILE plus a durable owner. Never a thread,
     * a coroutine, or the coordinator instance.
     */
    private data class HoldKey(val lockPath: Path, val owner: LockOwner, val resource: String)

    private class HeldLock(
        val channel: FileChannel,
        val fileLock: FileLock,
        var depth: Int,
    )

    private companion object {
        /**
         * Poll cadence for a bounded or unbounded wait. Small enough that a
         * `skipIfLocked` denial is prompt, large enough not to spin a core while
         * a genuinely long `lock` body runs.
         */
        const val POLL_INTERVAL_MILLIS = 50L

        /**
         * Holds taken by this PROCESS, keyed by canonical lock file and durable
         * owner. PROCESS-scoped and NOT instance-scoped, for two reasons:
         *
         *  1. A `FileLock` is owned by the JVM, not by the thread or the object, so
         *     a second `tryLock` on the same file from the same JVM throws
         *     [OverlappingFileLockException] instead of reporting contention. A
         *     registry keyed per instance would therefore not even see its own
         *     process's hold, and would report a re-entrant acquire as contention.
         *  2. The durable spine may construct a FRESH coordinator per dispatch. A
         *     resume re-runs the handler, which must re-enter its own earlier hold;
         *     an instance-scoped registry would deadlock the run against itself.
         *
         * Keyed by the canonical lock PATH so two coordinators over the same lock
         * root share holds, while two DIFFERENT lock roots (two workspaces) never
         * collide.
         */
        val PROCESS_HOLDS: ConcurrentHashMap<HoldKey, HeldLock> = ConcurrentHashMap()
    }

    override suspend fun acquire(
        owner: LockOwner,
        resource: String,
        intent: LockIntent,
    ): LockAdmission =
        withContext(Dispatchers.IO) {
            val path = lockFileFor(resource)
            val key = HoldKey(path, owner, resource)
            val existing = PROCESS_HOLDS[key]
            if (existing != null) {
                // SAME durable owner already holds it: re-enter and count the depth.
                // No OS call. A DIFFERENT owner falls through to the OS lock below
                // and contends properly, even inside this same JVM.
                existing.depth += 1
                return@withContext LockAdmission.Acquired(resource, reentrant = true)
            }

            Files.createDirectories(path.parent)
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val startedAt = System.nanoTime()

            val fileLock: FileLock? = try {
                takeLock(channel, intent, deadlineNanos = null)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                closeQuietly(channel)
                return@withContext LockAdmission.Denied(LockDenialReason.Cancelled)
            }

            if (fileLock == null) {
                closeQuietly(channel)
                val waitedMillis = (System.nanoTime() - startedAt) / 1_000_000
                return@withContext when (intent) {
                    // "Held" under Now is the skipIfLocked contract: not a timeout,
                    // and the handler maps it to a SUCCESS with the body not run.
                    LockIntent.Now -> LockAdmission.Denied(LockDenialReason.Held)
                    else -> LockAdmission.Denied(LockDenialReason.TimedOut(waitedMillis))
                }
            }

            PROCESS_HOLDS[key] = HeldLock(channel, fileLock, depth = 1)
            LockAdmission.Acquired(resource, reentrant = false)
        }

    /**
     * Takes the OS lock according to [intent].
     *
     * There is NO timed `tryLock` on this JDK's [FileChannel] (only `tryLock()` and
     * the positional `tryLock(long, long, boolean)`), so a bounded wait is a poll
     * loop. That is not only a workaround: the loop is the only place that can
     * observe thread interruption, which is how a cancelled run stops waiting
     * instead of sitting in an uninterruptible `lock()`.
     *
     * [deadlineNanos] is `null` for an unbounded wait.
     */
    private fun takeLock(channel: FileChannel, intent: LockIntent, deadlineNanos: Long?): FileLock? =
        when (intent) {
            LockIntent.Now -> tryOnce(channel) { it.tryLock() }
            is LockIntent.UpTo -> pollUntilLocked(channel, intent.millis * 1_000_000L)
            LockIntent.Forever -> pollUntilLocked(channel, deadlineNanos)
        }

    private fun pollUntilLocked(channel: FileChannel, budgetNanos: Long?): FileLock? {
        val deadline = budgetNanos?.let { System.nanoTime() + it }
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("lock wait interrupted")
            tryOnce(channel) { it.tryLock() }?.let { return it }
            if (deadline != null && System.nanoTime() >= deadline) return null
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun tryOnce(channel: FileChannel, block: (FileChannel) -> FileLock?): FileLock? = try {
        block(channel)
    } catch (e: OverlappingFileLockException) {
        null
    }

    override fun release(hold: LockHold) {
        val key = HoldKey(lockFileFor(hold.resource), hold.owner, hold.resource)
        val entry = PROCESS_HOLDS[key] ?: return
        val remaining = synchronized(entry) {
            if (entry.depth > 0) entry.depth - 1 else 0
        }
        if (remaining > 0) {
            synchronized(entry) { entry.depth = remaining }
            return
        }
        // Remove FIRST so a concurrent release cannot double-close the channel.
        if (PROCESS_HOLDS.remove(key, entry)) {
            runCatching { entry.fileLock.release() }
            closeQuietly(entry.channel)
        }
    }

    /** Test seam: is [owner] currently holding [resource] in this process? */
    internal fun isHeldLocally(owner: LockOwner, resource: String): Boolean =
        PROCESS_HOLDS.containsKey(HoldKey(lockFileFor(resource), owner, resource))

    /** Test seam: current re-entrant depth for one owner, 0 when not held. */
    internal fun depthOf(owner: LockOwner, resource: String): Int =
        PROCESS_HOLDS[HoldKey(lockFileFor(resource), owner, resource)]?.depth ?: 0

    /**
     * Canonical lock file for a resource name.
     *
     * The name is sanitised AND suffixed with a short digest of the original, so
     * two different resource names can never map to one file by sanitisation
     * accident, and a name can never escape [lockRoot] through `..` or a
     * separator. A collision here would be a mutual-exclusion hole, so it is
     * designed out rather than validated at runtime.
     */
    private fun lockFileFor(resource: String): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(resource.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        val safe = buildString {
            for (c in resource) {
                append(if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' || c == '.') c else '_')
            }
        }.trim('.').ifEmpty { "resource" }.take(64)
        return lockRoot.resolve("$safe-$digest.lock")
    }

    private fun closeQuietly(channel: FileChannel) {
        try {
            channel.close()
        } catch (ignored: Exception) {
            // Closing a channel that the OS already reclaimed is not a failure of
            // the lock contract; the hold is released either way.
        }
    }
}

/** Raised by a backend that cannot even open its lock file; fail closed, never silent. */
class LockBackendUnavailableException(message: String, cause: Throwable) :
    RuntimeException(message, cause) {
    init {
        if (cause !is FileNotFoundException && cause !is java.io.IOException) {
            throw IllegalArgumentException("expected an I/O cause, got ${cause::class.simpleName}")
        }
    }
}
