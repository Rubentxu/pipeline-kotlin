package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * A control root has ONE Output Plane state, and that state can be discarded without leaving half
 * of it behind.
 *
 * **The guarantee.** The state of one control root — its reader, its writer and the mutual
 * exclusion of its stream directories — belongs to that root and to nothing else. Discarding it
 * discards ALL of it: the reader that survived a discarded writer kept a view of the root's bytes
 * that nothing had asked to keep, and the next reader of that root was served by it. And the
 * mutual exclusion is shared by the reader and the writer of one root, because the retention pass
 * deletes a stream directory under that stream's lock, and a reader that does not take the same
 * lock can be served from a directory being removed underneath it.
 *
 * **The observable capacity it serves.** A long-lived process over one control root does not
 * accumulate one lock per stream the root ever touched, and a root that is closed does not leave
 * a reader behind that keeps answering for it.
 *
 * **Why this is a unit test and not a scenario.** What is under trial is the WIRING: which
 * exclusion a store is handed, and which stores a discard reaches. There is no byte and no
 * directory in it, and the surface that shows it is the store's own field — the object the store
 * takes its stream locks from. A scenario could only show it by racing a reader against a
 * retention pass and losing the race on purpose, which is a flaky test by construction.
 *
 * **Pure and total.** Nothing here touches a disk, a clock or a process. The roots are paths that
 * do not exist and are never created: the reader never reconciles, and the writing store is read
 * here before it reconciles, which is a legitimate state of a writing store — one that refuses to
 * serve a read until it has reconciled.
 */
class OutputPlaneLifetimeTest {

    @AfterEach
    fun forgetEveryRoot() {
        OutputPlaneProvider.forgetAll()
    }

    @Test
    @DisplayName("a discarded root hands out a new reader")
    fun aDiscardedRootHandsOutANewReader() {
        val root = rootNamed("discarded")
        val before = OutputPlaneProvider.storeForReading(root)

        OutputPlaneProvider.forget(root)

        assertNotSame(
            before,
            OutputPlaneProvider.storeForReading(root),
            "a discarded root must not answer with the reader it had before the discard",
        )
    }

    @Test
    @DisplayName("a discarded root hands out new stores")
    fun aDiscardedRootHandsOutNewStores() {
        val plane = OutputPlane(rootNamed("stores"))
        val reader = plane.reader()
        val writer = plane.writer()

        plane.discardStores()

        assertNotSame(
            reader,
            plane.reader(),
            "the reader of a discarded root is part of the discard, not a survivor of it",
        )
        assertNotSame(
            writer,
            plane.writer(),
            "the writer of a discarded root is part of the discard too",
        )
    }

    @Test
    @DisplayName("the stores of one root share one mutual exclusion")
    fun theStoresOfOneRootShareOneMutualExclusion() {
        val plane = OutputPlane(rootNamed("one-root"))

        assertSame(
            plane.locks,
            plane.reader().streamLocks,
            "the reader of a root takes the root's exclusion, not one of its own",
        )
        assertSame(
            plane.locks,
            plane.writer().streamLocks,
            "the writer of a root takes the root's exclusion, not one of its own: two exclusions " +
                "over one directory are two locks that exclude nothing",
        )
    }

    @Test
    @DisplayName("two roots do not share one mutual exclusion")
    fun twoRootsDoNotShareOneMutualExclusion() {
        val first = OutputPlane(rootNamed("first"))
        val second = OutputPlane(rootNamed("second"))

        assertNotSame(
            first.locks,
            second.locks,
            "two roots hold different bytes: one exclusion over both of them would serialise the " +
                "streams of a root that has nothing to do with the other",
        )
    }

    /**
     * A path that names a root and does not exist. Nothing creates it: the stores built over it
     * are not reconciled here, and a store that is never reconciled never touches the disk.
     */
    private fun rootNamed(name: String): Path = Path.of("/pipelinek-plane-that-does-not-exist", name)
}
