package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.runtime.durable.FencingToken
import dev.rubentxu.pipeline.v2.runtime.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.runtime.durable.RunOwnerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The bytes of an ownership record: the format a restart reads and an operator
 * reads.
 *
 * ## What this pins
 *
 * The record outlives the process that wrote it. It is what the next
 * acquisition reads to know who held the run, and it is what an operator reads
 * with `cat` when a run will not start. So the format is a CONTRACT, and a
 * contract nobody pins is a contract the next change rewrites by accident.
 *
 * Two things are pinned:
 *
 *  - the exact text of a record: four `key=value` lines, in this order, with
 *    the run identity VERBATIM. A key renamed, a line dropped or an identity
 *    transformed leaves the lease files already on disk unreadable, and the
 *    reader answers "no record" for a run that has one;
 *  - the round trip: what is written is what is read back. A format that
 *    writes one thing and reads another is not a format but a coincidence that
 *    holds until the first change.
 *
 * ## Why no filesystem
 *
 * The record is a pure function of the record, and the file adapter only moves
 * its result. That is what lets the exact text be asserted here, with no disk,
 * no process and no clock — and it is what lets a change to the format be seen
 * red without a filesystem in the way.
 */
class LeaseRecordFormatTest {

    @Test
    fun `el registro se escribe como cuatro lineas con la identidad literal`() {
        val record = LeaseRecord(
            runId = RunId("run-42"),
            ownerId = RunOwnerId.of("worker-a")!!,
            fencingToken = FencingToken.of(7),
            ownerAlive = true,
        )

        assertEquals(
            "run_id=run-42\n" +
                "owner_id=worker-a\n" +
                "fencing_token=7\n" +
                "owner_alive=true\n",
            LeaseRecordFormat.encode(record),
            "the exact bytes of a record are the contract a restart and an operator read",
        )
    }

    @Test
    fun `un lease liberado se escribe con su vacio y no con un valor inventado`() {
        val released = LeaseRecord(
            runId = RunId("run-42"),
            ownerId = null,
            fencingToken = FencingToken.of(7),
            ownerAlive = false,
        )

        assertEquals(
            "run_id=run-42\n" +
                "owner_id=\n" +
                "fencing_token=7\n" +
                "owner_alive=false\n",
            LeaseRecordFormat.encode(released),
            "a released lease has no owner, and the record says so with nothing rather than " +
                "with a name that could collide with a real one",
        )
    }

    @Test
    fun `lo que se escribe es lo que se lee`() {
        val owned = LeaseRecord(
            runId = RunId("run-42"),
            ownerId = RunOwnerId.of("worker-a")!!,
            fencingToken = FencingToken.of(7),
            ownerAlive = true,
        )

        assertEquals(
            owned,
            LeaseRecordFormat.decode(LeaseRecordFormat.encode(owned).lines()),
            "a record that survives the round trip is a record a restart can trust",
        )
    }

    @Test
    fun `un lease liberado tambien sobrevive el viaje de vuelta`() {
        val released = LeaseRecord(
            runId = RunId("run-42"),
            ownerId = null,
            fencingToken = FencingToken.of(7),
            ownerAlive = false,
        )

        assertEquals(
            released,
            LeaseRecordFormat.decode(LeaseRecordFormat.encode(released).lines()),
            "the released case travels too: it is the record the next acquisition reads",
        )
    }

    @Test
    fun `un registro sin identidad no es un registro`() {
        // A record that names no run is not evidence about any run: a reader
        // that answered with one would hand out a lease about nobody's run.
        assertNull(
            LeaseRecordFormat.decode(listOf("owner_id=worker-a", "fencing_token=7")),
            "a record without a run identity is not a record",
        )
        assertNull(
            LeaseRecordFormat.decode(listOf("run_id=", "owner_id=worker-a")),
            "a record with a blank run identity is not a record",
        )
    }
}
