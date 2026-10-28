package dev.rubentxu.pipeline.v2.output.store

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The mutual exclusion of ONE control root's stream directories.
 *
 * ## WHO OWNS THIS
 * The Output Plane state of one control root owns it: the single object that holds that root's
 * reader, that root's writer and that root's per-stream locks. A store does not own it — a store is
 * HANDED one, and two stores handed the same one exclude each other.
 *
 * That is the whole reason this is an object of its own and not a map inside a store. The reader and
 * the writer of a root are DIFFERENT stores, on purpose: a reader must never reconcile durable
 * state, because reconciliation truncates the uncommitted tail of a stream, and an uncommitted tail
 * is exactly what a writer that is alive right now looks like. A lock that lived inside a store
 * could therefore never be shared by the reader and the writer of one root, and the exclusion the
 * retention pass documents ("a concurrent reader is served or refused, never served from a directory
 * being removed underneath it") could not exist between them at all.
 *
 * ## HOW LONG AN ENTRY LIVES
 * An entry lives while a caller is inside it, and no longer. The count of the callers is what says
 * so, and it is what makes the removal safe: an entry is dropped only when nobody holds it and
 * nobody is waiting for it, so a caller that has already taken the entry and has not yet acquired
 * it still finds the exclusion it came for.
 *
 * Without that, the registry would keep one lock per stream the root ever touched, for as long as
 * the process lived. The bytes of a stream are bounded by the root's retention, but the lock was
 * not: a root that serves a thousand runs in one process would hold a thousand locks for a thousand
 * streams that no longer exist.
 *
 * ## WHY THE KEY IS THE DIRECTORY NAME
 * The key is the **canonical** name of a stream directory: the `safe()`-folded id, which is exactly
 * the directory name the store resolves for that stream. It is deliberately a `String` and not an
 * [dev.rubentxu.pipeline.v2.output.OutputStreamId], because the id is not invertible (`safe` folds
 * `/` onto `_`) and the retention pass reaches a stream by its directory name. A registry keyed by
 * the raw id could never share an entry with that pass: the writer used to key on the raw id and the
 * pruner on the folded name, so releasing a run added a second, permanently retained lock for a
 * stream whose writer lock was already present (measured: `2N` locks after releasing `N` runs).
 *
 * Two ids that fold to the same name share one on-disk directory, so they share one lock — the lock
 * protects the DIRECTORY, and the directory is what both of them reach. They are not one stream,
 * though: a folded name is a lossy address, and the store refuses two identities that land in one
 * directory (see `claimStreamDirectory`).
 *
 * ## WHAT IT DOES NOT EXCLUDE
 * It does not exclude across control roots — two roots are two planes and hold different bytes — and
 * it does not exclude across processes. Two processes over one root are fenced by the run's lease,
 * not by this.
 */
class StreamLocks {

    /**
     * One stream's exclusion, and the number of the callers that are inside it or waiting for it.
     */
    private class Exclusion(val lock: ReentrantLock) {
        var holders: Int = 0
    }

    private val perStream = HashMap<String, Exclusion>()

    /**
     * The number of streams this registry is currently excluding.
     *
     * An entry exists only while a caller is inside it or waiting for it, so this number is the
     * number of streams being touched RIGHT NOW — not the number of streams the registry ever saw.
     * That is the difference between a registry that is a fact about the work in flight and one
     * that is a list of every stream the root ever touched: the latter grew for as long as the
     * process lived, and a root that serves a thousand runs in one process kept a thousand locks
     * for a thousand streams that no longer existed.
     */
    val heldStreams: Int
        get() = synchronized(perStream) { perStream.size }

    /**
     * Run [block] holding the exclusion of the stream directory named [key].
     *
     * The key is the canonical directory name, so the writer of a stream and the retention pass
     * that removes its directory contend for the SAME lock and not for two.
     */
    fun <T> withLock(key: String, block: () -> T): T {
        val exclusion = synchronized(perStream) {
            perStream.getOrPut(key) { Exclusion(ReentrantLock()) }.also { it.holders++ }
        }
        try {
            return exclusion.lock.withLock { block() }
        } finally {
            synchronized(perStream) {
                exclusion.holders--
                if (exclusion.holders == 0) perStream.remove(key, exclusion)
            }
        }
    }
}
