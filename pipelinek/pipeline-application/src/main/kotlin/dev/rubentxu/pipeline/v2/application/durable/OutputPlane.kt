package dev.rubentxu.pipeline.v2.application.durable

import dev.rubentxu.pipeline.v2.output.OutputPinPort
import dev.rubentxu.pipeline.v2.output.store.OutputPinPortStoreAdapter
import dev.rubentxu.pipeline.v2.output.store.SegmentOutputStore
import dev.rubentxu.pipeline.v2.output.store.StreamLocks
import java.nio.file.Path

/**
 * The Output Plane state of ONE control root.
 *
 * ## WHAT THIS IS
 * Everything a control root needs to hold its bytes and to keep the threads that touch them apart:
 * the mutual exclusion of its stream directories, the two stores that use it — the reader and the
 * writer — and the pin substrate that says which of those bytes somebody is holding.
 *
 * The reader and the writer are DIFFERENT stores, on purpose. A reader must never reconcile
 * durable state, because reconciliation truncates the uncommitted tail of a stream, and an
 * uncommitted tail is exactly what a writer that is alive right now looks like. The measured cost
 * of violating it: a successful `console` query in a second JVM destroyed 4096 bytes a live writer
 * had written and acknowledged, and the writer was never told.
 *
 * ## WHAT THIS OWNS, AND FOR HOW LONG
 * - The mutual exclusion ([locks]) lives as long as this plane does. It protects the DIRECTORIES of
 *   the root, not the stores, so it outlives a discarded store: a store that a live caller still
 *   holds keeps excluding the root's next writer, and that is the whole point of handing the
 *   exclusion down instead of keeping it inside a store.
 * - The two stores live until they are discarded together ([discardStores]). They are discarded
 *   WHOLE, never one at a time: a root whose writer was discarded while its reader survived kept a
 *   reader holding a stale view of the root's bytes, and the next reader of that root was served
 *   by it.
 * - The reconciliation of the writing store happens once per writer ([reconcileWriter]). It is not
 *   part of creating the store, because a store that has not been reconciled refuses to serve a
 *   read — an unreconciled writer is a legitimate state of a writer, and the provider is what
 *   decides to reconcile it.
 * - The pin substrate ([pinPort]) lives as long as this plane does, and is shared by the reader and
 *   the writer: one root has ONE record of who holds its bytes, so the two stores cannot disagree
 *   about the same directory. It is discarded with the stores, because the substrate is a VIEW of
 *   the root's durable manifest, not a cache of it.
 */
internal class OutputPlane(private val outputDir: Path) {

    /**
     * The mutual exclusion of this root's stream directories.
     *
     * Every store this plane creates is handed it, so the reader and the writer of one root
     * contend for the SAME lock and not for two.
     */
    val locks = StreamLocks()

    private var readerStore: SegmentOutputStore? = null
    private var writerStore: SegmentOutputStore? = null
    private var writerReconciled = false
    private var pinSubstrate: OutputPinPortStoreAdapter? = null

    /**
     * The pin substrate of this root: the record of WHO HOLDS which bytes.
     *
     * ## Why the plane owns it
     *
     * The substrate is a property of the ROOT, not of a store: a pin taken against the reader and a
     * pin taken against the writer name the same bytes, and the retention pass that later deletes
     * those bytes has to find the pin whichever store recorded it. One root, one substrate, one
     * manifest — so there is no second answer to "is anybody holding this".
     *
     * ## Why the committed extent comes from the READER
     *
     * A pin may only cover bytes the stream has COMMITTED, and the committed offset is durable, so
     * the reader and the writer agree about it. The reader is the side that may answer at any time:
     * the writer may be unreconciled, and an unreconciled writer refuses to serve a read — so
     * asking the writer would make "may I pin this?" depend on whether anybody had recovered yet.
     *
     * The extent is asked LAZILY, when a pin is actually taken, and not when the substrate is built:
     * the reader may not exist yet at that moment.
     */
    fun pinPort(): OutputPinPort = substrate()

    /**
     * The same substrate, seen as the [dev.rubentxu.pipeline.v2.output.store.DirectoryPinConsult]
     * the stores consult.
     *
     * Two views of ONE substrate, not two substrates: the published port answers by stream and the
     * retention side answers by directory name, and both read the same manifest, so they cannot
     * disagree about the same bytes.
     */
    private fun substrate(): OutputPinPortStoreAdapter = synchronized(this) {
        pinSubstrate ?: OutputPinPortStoreAdapter(
            root = outputDir.resolve(OutputPinPortStoreAdapter.PINS_DIR),
            committedExtent = { stream -> reader().committedExtent(stream) },
        ).also { pinSubstrate = it }
    }

    /**
     * The reading store of this root. It never reconciles.
     *
     * Reads are honest without reconciliation: a reader only ever serves bytes at or below the
     * committed offset, so it cannot observe an unacknowledged byte. Reconciliation is what makes
     * debris disappear, not what makes a read true.
     */
    fun reader(): SegmentOutputStore = synchronized(this) {
        readerStore ?: SegmentOutputStore(
            root = outputDir,
            recoveryPermitted = false,
            pinConsult = substrate(),
            streamLocks = locks,
        ).also { readerStore = it }
    }

    /**
     * The writing store of this root, NOT reconciled.
     *
     * A writing store that has not reconciled refuses to serve a read, so a caller that needs the
     * bytes asks for [reconcileWriter] instead.
     */
    fun writer(): SegmentOutputStore = synchronized(this) {
        writerStore ?: SegmentOutputStore(
            root = outputDir,
            pinConsult = substrate(),
            streamLocks = locks,
        ).also { writerStore = it }
    }

    /**
     * The writing store of this root, reconciled — once per writer.
     *
     * Reconciliation is scoped by ownership, so it skips every stream a live writer holds — in this
     * process or any other — rather than deciding for them that their bytes are debris.
     */
    fun reconcileWriter(): SegmentOutputStore {
        val store = writer()
        if (!writerReconciled) {
            synchronized(this) {
                if (!writerReconciled) {
                    store.recover()
                    writerReconciled = true
                }
            }
        }
        return store
    }

    /**
     * Discard the stores of this root, both of them at once.
     *
     * The mutual exclusion survives: it protects the root's directories, and a store that a live
     * caller still holds has to keep excluding the root's next writer. What is discarded is the
     * memo — the stores this plane built and nobody outside it has to keep.
     */
    fun discardStores() {
        synchronized(this) {
            readerStore = null
            writerStore = null
            writerReconciled = false
            pinSubstrate = null
        }
    }
}
