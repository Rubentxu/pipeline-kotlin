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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    /**
     * Takes the lock, suspending rather than blocking while it waits.
     *
     * The wait is coroutine-first: the `tryLock` syscall runs on
     * [Dispatchers.IO] because it is a real (fast) file operation, and the WAIT
     * itself is [delay]. A coroutine parked in `delay` retains no thread, so a
     * pipeline holding ten contended locks retains ten nothing.
     *
     * Cancellation propagates as [kotlinx.coroutines.CancellationException] rather
     * than being converted into a typed denial. Turning a cancelled coroutine into
     * a business failure value is the mistake this deliberately avoids: the caller
     * is being cancelled, and a `Denied(Cancelled)` value would invite it to carry
     * on. [LockDenialReason.Cancelled] remains for a coordinator that is asked to
     * stop while its coroutine is still live.
     */
    override suspend fun acquire(
        owner: LockOwner,
        resource: String,
        intent: LockIntent,
    ): LockAdmission {
        val path = lockFileFor(resource)
        val key = HoldKey(path, owner, resource)

        PROCESS_HOLDS[key]?.let { existing ->
            // SAME durable owner already holds it: re-enter and count the depth.
            // The transition is synchronised rather than mutex-guarded because it
            // never suspends, so a Mutex would only add an allocation and a
            // suspension point to protect a two-field counter. Cross-process
            // exclusion remains the OS lock's job, not this one's.
            synchronized(existing) { existing.depth += 1 }
            return LockAdmission.Acquired(resource, reentrant = true)
        }

        val startedAt = System.nanoTime()
        val channel = withContext(Dispatchers.IO) {
            Files.createDirectories(path.parent)
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }

        val fileLock: FileLock? = try {
            takeLock(channel, intent, startedAt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            closeQuietly(channel)
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            closeQuietly(channel)
            return LockAdmission.Denied(LockDenialReason.Cancelled)
        }

        if (fileLock == null) {
            closeQuietly(channel)
            val waitedMillis = (System.nanoTime() - startedAt) / 1_000_000
            return when (intent) {
                // "Held" under Now is the skipIfLocked contract: not a timeout,
                // and the handler maps it to a SUCCESS with the body not run.
                LockIntent.Now -> LockAdmission.Denied(LockDenialReason.Held)
                else -> LockAdmission.Denied(LockDenialReason.TimedOut(waitedMillis))
            }
        }

        PROCESS_HOLDS[key] = HeldLock(channel, fileLock, depth = 1)
        return LockAdmission.Acquired(resource, reentrant = false)
    }

    /**
     * Takes the OS lock according to [intent], SUSPENDING while it waits.
     *
     * There is NO timed `tryLock` on this JDK's [FileChannel] (only `tryLock()` and
     * the positional `tryLock(long, long, boolean)`), so a bounded wait is a poll
     * loop — but the wait between attempts is [delay], not [Thread.sleep], so a
     * waiting acquire holds no thread at all.
     */
    private suspend fun takeLock(
        channel: FileChannel,
        intent: LockIntent,
        startedAtNanos: Long,
    ): FileLock? = when (intent) {
        LockIntent.Now -> withContext(Dispatchers.IO) { tryOnce(channel) { it.tryLock() } }
        is LockIntent.UpTo ->
            pollUntilLocked(channel, startedAtNanos + intent.millis * 1_000_000L)
        LockIntent.Forever -> pollUntilLocked(channel, deadlineNanos = null)
    }

    private suspend fun pollUntilLocked(channel: FileChannel, deadlineNanos: Long?): FileLock? {
        while (true) {
            // Cancellation is checked BEFORE each attempt, so a cancelled acquire
            // stops promptly instead of finishing the current sleep interval.
            currentCoroutineContext().ensureActive()
            val acquired = withContext(Dispatchers.IO) { tryOnce(channel) { it.tryLock() } }
            if (acquired != null) return acquired
            if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) return null
            delay(POLL_INTERVAL_MILLIS)
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
