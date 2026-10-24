package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest

/**
 * Whether a run's event store may be opened is ONE decision, and the closed set of reasons it may
 * be refused is [EventStoreRefusal]. Before this type existed, six construction sites answered
 * that question themselves and gave four different answers — including exit `1`, which in this CLI
 * means "the pipeline FAILED", for a file that was never open and so executed nothing.
 *
 * [refusalFor] is the whole rule set, and it is deliberately total over two closed ADTs and free of
 * I/O. That is what lets every combination be stated here without a disk: four observations by
 * three purposes is twelve rows, and each one is a rule rather than a coincidence of a filesystem.
 *
 * The rules that separate the purposes are worth stating plainly, because they are the whole reason
 * [EventStorePurpose] exists. A verb that READS a finished run is being pointed at a file that must
 * already be there; a verb that WRITES INTO a finished run adds a fact to a store that must
 * already be there; a verb that STARTS a run is creating that store, so both its absence and the
 * absence of the directory that would hold it are the normal case. One `if (!Files.exists(...))`
 * cannot express those three without a boolean flag repeated at every call site.
 *
 * The two absences are also different, and the difference is load-bearing. A directory that is
 * missing can be created by the producer that owns it; a place that is not a directory cannot be
 * made into one by anybody, and a relative name that has no parent component lives in the working
 * directory, which is a directory like any other. Collapsing those three states into one would
 * make the rule answer "no directory" for a path whose directory is right there.
 *
 * `admitEventStore` and `observePath` are the effectful shells around this decision and are NOT
 * covered here: they touch the filesystem and open a database, which is not what a unit test in this
 * tree is allowed to do.
 */
class EventStoreAdmissionTest {

    private val path = "/runs/run-42/events.db"

    /**
     * Table-driven contract: 4 observations x 3 purposes = twelve independent cases.
     * Both acceptance and refusal are necessary; a reject-all or allow-all
     * mutation must turn at least one row red. No I/O, no duplicate E2E.
     */
    @TestFactory
    fun `all admission decisions match the closed input matrix`(): List<DynamicTest> {
        val cases = listOf(
            Case(
                "missing file / read refuses",
                PathObservation.NoFile, EventStorePurpose.Read, EventStoreRefusal.Absent(path),
            ),
            Case(
                "missing file / write may create the file",
                PathObservation.NoFile, EventStorePurpose.Write, null,
            ),
            Case(
                "missing file / produce may create the file",
                PathObservation.NoFile, EventStorePurpose.Produce, null,
            ),
            Case(
                "missing directory / read refuses",
                PathObservation.MissingDirectory, EventStorePurpose.Read, EventStoreRefusal.NoDirectory(path),
            ),
            Case(
                "missing directory / write refuses",
                PathObservation.MissingDirectory, EventStorePurpose.Write, EventStoreRefusal.NoDirectory(path),
            ),
            Case(
                "missing directory / produce creates it",
                PathObservation.MissingDirectory, EventStorePurpose.Produce, null,
            ),
            Case(
                "place is not a directory / read refuses",
                PathObservation.NotADirectory, EventStorePurpose.Read, EventStoreRefusal.NoDirectory(path),
            ),
            Case(
                "place is not a directory / write refuses",
                PathObservation.NotADirectory, EventStorePurpose.Write, EventStoreRefusal.NoDirectory(path),
            ),
            Case(
                "place is not a directory / produce refuses too",
                PathObservation.NotADirectory, EventStorePurpose.Produce, EventStoreRefusal.NoDirectory(path),
            ),
            Case(
                "present path / read proceeds",
                PathObservation.Present, EventStorePurpose.Read, null,
            ),
            Case(
                "present path / write proceeds",
                PathObservation.Present, EventStorePurpose.Write, null,
            ),
            Case(
                "present path / produce proceeds",
                PathObservation.Present, EventStorePurpose.Produce, null,
            ),
        )
        // A factory returning eleven green rows is not a green test of a 4x3
        // matrix. Reject omissions and duplicates before generating cases.
        val expectedPairs = listOf(
            PathObservation.NoFile,
            PathObservation.MissingDirectory,
            PathObservation.NotADirectory,
            PathObservation.Present,
        ).flatMap { observation ->
            listOf(EventStorePurpose.Read, EventStorePurpose.Write, EventStorePurpose.Produce).map { purpose ->
                observation to purpose
            }
        }
        val observedPairs = cases.map { it.observation to it.purpose }
        assertEquals(expectedPairs.size, observedPairs.size, "the matrix lost a case")
        assertEquals(expectedPairs.toSet(), observedPairs.toSet(), "matrix omitted or repeated a combination")

        return cases.map { case ->
            dynamicTest(case.label) {
                assertEquals(
                    case.expected,
                    refusalFor(case.observation, path, case.purpose),
                    "observation=${case.observation}, purpose=${case.purpose}",
                )
            }
        }
    }

    private data class Case(
        val label: String,
        val observation: PathObservation,
        val purpose: EventStorePurpose,
        val expected: EventStoreRefusal?,
    )

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
            refusalFor(PathObservation.MissingDirectory, path, EventStorePurpose.Read),
            refusalFor(PathObservation.MissingDirectory, path, EventStorePurpose.Write),
            refusalFor(PathObservation.NotADirectory, path, EventStorePurpose.Produce),
            // The pure rule cannot see INSIDE the file, so it cannot produce this one: a
            // database that opens but holds no events at all is decided by the store itself
            // when it opens read-only. It belongs to the same closed set and owes the
            // operator the same thing.
            EventStoreRefusal.NoEventHistory(path),
        )

        val named = refusals.map { refusal ->
            when (refusal) {
                is EventStoreRefusal.Absent -> refusal.path
                is EventStoreRefusal.NoDirectory -> refusal.path
                is EventStoreRefusal.NotReadable -> refusal.path
                is EventStoreRefusal.NoEventHistory -> refusal.path
            }
        }

        assertEquals(5, named.size)
        assertEquals(List(5) { path }, named, "a refusal that loses the path is not actionable")
    }

    // --- the producer is the only lane that creates its place ------------------

    @Test
    fun `only the producer may create the directory that holds the store`() {
        // The guarantee is about WHO creates the place, not about whether a
        // directory happens to be missing. A verb that writes into a run that
        // already happened is pointing at a store somebody else produced, and
        // the place that store lives in is not that verb's to make.
        val missing = PathObservation.MissingDirectory

        assertEquals(
            null,
            refusalFor(missing, path, EventStorePurpose.Produce),
            "the producer starts the run and owns the directory it writes into",
        )
        assertEquals(
            EventStoreRefusal.NoDirectory(path),
            refusalFor(missing, path, EventStorePurpose.Write),
            "a writer of a finished run must find the place, not make it",
        )
        assertEquals(
            EventStoreRefusal.NoDirectory(path),
            refusalFor(missing, path, EventStorePurpose.Read),
            "a reader must find the place, not make it",
        )
    }
}
