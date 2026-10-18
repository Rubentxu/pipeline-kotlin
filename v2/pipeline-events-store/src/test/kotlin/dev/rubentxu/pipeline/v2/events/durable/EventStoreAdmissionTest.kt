package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Whether a run's event store may be opened is ONE decision, and the closed set of reasons it may
 * be refused is [EventStoreRefusal]. Before this type existed, six construction sites answered
 * that question themselves and gave four different answers — including exit `1`, which in this CLI
 * means "the pipeline FAILED", for a file that was never open and so executed nothing.
 *
 * [refusalFor] is the whole rule set, and it is deliberately total over two closed ADTs and free of
 * I/O. That is what lets every combination be stated here without a disk: three observations by
 * two purposes is six rows, and each one is a rule rather than a coincidence of a filesystem.
 *
 * The rule that separates the two purposes is worth stating plainly, because it is the whole reason
 * [EventStorePurpose] exists. A verb that READS a finished run is being pointed at a file that must
 * already be there; a verb that STARTS a run is creating that file, so its absence is the normal
 * case and only its DIRECTORY has to exist. One `if (!Files.exists(...))` cannot express both
 * without a boolean flag repeated at every call site.
 *
 * `admitEventStore` and `observePath` are the effectful shells around this decision and are NOT
 * covered here: they touch the filesystem and open a database, which is not what a unit test in this
 * tree is allowed to do.
 */
class EventStoreAdmissionTest {

    private val path = "/runs/run-42/events.db"

    // --- the rule that carries the axis ----------------------------------------

    @Test
    fun `a read is refused when the file is absent`() {
        // The specific claim first: a reader pointed at a run that did not happen
        // must be told so, not handed a store it will create and appear empty.
        assertEquals(
            EventStoreRefusal.Absent(path),
            refusalFor(PathObservation.NoFile, path, EventStorePurpose.Read),
        )
    }

    @Test
    fun `a write proceeds when the file is absent, because creating it is the point`() {
        // Refusing here would break every first run, which is the most common run
        // there is -- the case that made the pre-existing split necessary.
        assertNull(refusalFor(PathObservation.NoFile, path, EventStorePurpose.Write))
    }

    // --- a missing directory refuses BOTH purposes ------------------------------

    @Test
    fun `a missing directory refuses a read`() {
        assertEquals(
            EventStoreRefusal.NoDirectory(path),
            refusalFor(PathObservation.NoDirectory, path, EventStorePurpose.Read),
        )
    }

    @Test
    fun `a missing directory refuses a write too`() {
        // The axis does NOT apply here: a write may create the FILE, not the
        // DIRECTORY holding it. Both refusals name the path for the same reason --
        // it is the actionable half for an operator who has to create it.
        assertEquals(
            EventStoreRefusal.NoDirectory(path),
            refusalFor(PathObservation.NoDirectory, path, EventStorePurpose.Write),
        )
    }

    // --- a present path is admitted for both ------------------------------------

    @Test
    fun `a present path is admitted for a read`() {
        assertNull(refusalFor(PathObservation.Present, path, EventStorePurpose.Read))
    }

    @Test
    fun `a present path is admitted for a write`() {
        // A PRESENT path that SQLite still cannot open is `NotReadable`, carrying
        // SQLite's own words. This layer does not second-guess that, which is why
        // admission here is not a promise that opening will succeed.
        assertNull(refusalFor(PathObservation.Present, path, EventStorePurpose.Write))
    }

    // --- the refusal set stays closed -------------------------------------------

    @Test
    fun `every refusal names the path the operator was given`() {
        // The path is what the operator has to act on; a reason without it is a
        // diagnosis they cannot use.
        //
        // The path is read per case rather than through the interface on purpose:
        // `EventStoreRefusal` declares no `path` accessor, so a polymorphic read
        // would not compile. That is a fair shape -- the three cases carry
        // different payloads and no common one is forced on them -- and this test
        // states the consequence rather than working around it.
        val refusals = listOfNotNull(
            refusalFor(PathObservation.NoFile, path, EventStorePurpose.Read),
            refusalFor(PathObservation.NoDirectory, path, EventStorePurpose.Read),
            refusalFor(PathObservation.NoDirectory, path, EventStorePurpose.Write),
        )

        val named = refusals.map { refusal ->
            when (refusal) {
                is EventStoreRefusal.Absent -> refusal.path
                is EventStoreRefusal.NoDirectory -> refusal.path
                is EventStoreRefusal.NotReadable -> refusal.path
            }
        }

        assertEquals(3, named.size)
        assertEquals(List(3) { path }, named, "a refusal that loses the path is not actionable")
    }
}