package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest.dynamicTest

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

    /**
     * Six observable outcomes, one row per member of the closed product
     * PathObservation x EventStorePurpose. Parameterization reduces test-code
     * duplication; it deliberately does NOT reduce the six executed cases.
     *
     * The positive and negative pair for NoFile is essential: a blanket refusal
     * or a blanket admission must both turn this table red.
     */
    @TestFactory
    fun `all combinations decide admission by purpose and observation`() = listOf(
        AdmissionCase(
            "missing file, read: refuse absent",
            PathObservation.NoFile,
            EventStorePurpose.Read,
            EventStoreRefusal.Absent(path),
        ),
        AdmissionCase(
            "missing file, write: permit creation",
            PathObservation.NoFile,
            EventStorePurpose.Write,
            null,
        ),
        AdmissionCase(
            "missing directory, read: refuse",
            PathObservation.NoDirectory,
            EventStorePurpose.Read,
            EventStoreRefusal.NoDirectory(path),
        ),
        AdmissionCase(
            "missing directory, write: refuse",
            PathObservation.NoDirectory,
            EventStorePurpose.Write,
            EventStoreRefusal.NoDirectory(path),
        ),
        AdmissionCase(
            "existing path, read: permit",
            PathObservation.Present,
            EventStorePurpose.Read,
            null,
        ),
        AdmissionCase(
            "existing path, write: permit",
            PathObservation.Present,
            EventStorePurpose.Write,
            null,
        ),
    ).map { case ->
        dynamicTest(case.label) {
            assertEquals(
                case.expected,
                refusalFor(case.observation, path, case.purpose),
                "observation=${case.observation}, purpose=${case.purpose}; acceptance cannot be inferred from exit 0",
            )
        }
    }

    private data class AdmissionCase(
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
