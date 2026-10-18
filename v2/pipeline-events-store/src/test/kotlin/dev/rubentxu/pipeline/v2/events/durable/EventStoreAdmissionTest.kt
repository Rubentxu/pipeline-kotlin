package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The admission rule, at the two levels it can be wrong at.
 *
 * ## The decision is pure, and that is not decoration
 *
 * [refusalFor] takes an observation and a purpose and returns a verdict. Nothing in it can fail, so
 * the whole rule set — three observations, two purposes, six combinations — is exercisable without a
 * disk. That is the reason the observation is a closed ADT rather than the two booleans
 * `fileExists` and `parentExists`: booleans admit the shape "the file does not exist AND its parent
 * does not exist", which no filesystem produces, and a rule over it cannot be stated exhaustively.
 *
 * ## What the purpose axis is FOR
 *
 * The row that matters most is `RULE_1`: a run that is about to WRITE legitimately has no event
 * store yet. A single "does the file exist" guard, which is what every caller wrote for itself,
 * either refuses every first run or admits every unreadable one. Making the intent part of the
 * input is what removes the choice.
 *
 * ## The mutation that must kill this
 *
 * ```text
 * M-4  PathObservation.NoFile -> when (purpose) { Write -> null; Read -> Absent(path) }
 *      collapsed to  PathObservation.NoFile -> EventStoreRefusal.Absent(path)
 * ```
 *
 * The plausible wrong code: one rule for everyone, which is exactly what the six construction sites
 * approximated. Measured with the mutant's file SHA-256 verified before the result was read:
 *
 * ```text
 *   RULE_1  RED   expected null, was Absent
 *   RULE_3  RED   NoFile under Write: expected admitted, was Absent
 *   OPEN_3  RED   the fixture's Write was refused — a database could not be made
 *   OPEN_4  RED   expected Admitted, was Refused
 *   RULE_2  GREEN   OPEN_1 GREEN   OPEN_2 GREEN     (the arms M-4 does not touch)
 * ```
 *
 * The green half is the point. A guard that dies on everything proves only that the suite runs; a
 * guard that dies on the three rows about the mutated arm and stays green on the other three proves
 * the rows are attributed to the behaviour they name.
 */
@Timeout(60)
class EventStoreAdmissionTest {

    @TempDir
    lateinit var root: Path

    // ------------------------------------------------------------------ HF0: the pure rule

    /**
     * A WRITE may create its own file; only a missing DIRECTORY stops it.
     *
     * If this row is red, every first run on a fresh machine is refused — the most common run there
     * is — and no test at a higher level will notice, because the higher rows all use stores that
     * already exist.
     */
    @Test
    fun RULE_1_a_write_admits_an_absent_file_but_never_an_absent_directory() {
        assertNull(
            refusalFor(PathObservation.NoFile, "/x/events.db", EventStorePurpose.Write),
            "a run creates its own event store; refusing an absent FILE would break every first run",
        )
        assertInstanceOf(
            EventStoreRefusal.NoDirectory::class.java,
            refusalFor(PathObservation.NoDirectory, "/x/events.db", EventStorePurpose.Write),
            "but a directory that does not exist cannot be created by SQLite, so WRITE is refused too",
        )
    }

    /** The same observation read by a verb that is reading a run which already happened. */
    @Test
    fun RULE_2_a_read_refuses_an_absent_file_and_names_it() {
        val refusal = refusalFor(PathObservation.NoFile, "/x/events.db", EventStorePurpose.Read)
        val absent = assertInstanceOf(EventStoreRefusal.Absent::class.java, refusal)
        assertEquals("/x/events.db", absent.path, "the refusal carries the path the operator asked for")
    }

    /**
     * Every combination, so adding a purpose cannot silently reuse another one's rule.
     *
     * The table is the point: it is what makes the `when` above exhaustive by construction rather
     * than by inspection, and it fails loudly the day a third purpose appears without a rule.
     */
    @Test
    fun RULE_3_every_observation_purpose_pair_has_a_stated_verdict() {
        val observations = listOf(
            PathObservation.NoDirectory to "NoDirectory",
            PathObservation.NoFile to "NoFile",
            PathObservation.Present to "Present",
        )
        val purposes = listOf(
            EventStorePurpose.Read to "Read",
            EventStorePurpose.Write to "Write",
        )

        for ((observation, obsName) in observations) {
            for ((purpose, purposeName) in purposes) {
                val verdict = refusalFor(observation, "/x/events.db", purpose)
                val stated = when {
                    verdict == null -> "admitted"
                    verdict is EventStoreRefusal.Absent -> "Absent"
                    verdict is EventStoreRefusal.NoDirectory -> "NoDirectory"
                    verdict is EventStoreRefusal.NotReadable -> "NotReadable"
                    else -> error("$obsName/$purposeName produced an unstated case")
                }
                assertEquals(
                    expected(observation, purpose),
                    stated,
                    "$obsName under $purposeName",
                )
            }
        }
    }

    private fun expected(observation: PathObservation, purpose: EventStorePurpose): String = when (observation) {
        PathObservation.NoDirectory -> "NoDirectory"
        PathObservation.NoFile -> if (purpose is EventStorePurpose.Write) "admitted" else "Absent"
        PathObservation.Present -> "admitted"
    }

    // ------------------------------------------------------------------ HF1: the effectful admission

    /**
     * A real path under a real missing directory is refused, and the refusal names it.
     *
     * The observation is produced by the real probe against the real filesystem; only the rule is
     * pure. This is the row that would fail if `observePath` disagreed with SQLite about what is
     * missing — which is the disagreement the defect was made of.
     */
    @Test
    fun OPEN_1_a_path_under_a_missing_directory_is_refused_with_its_own_path() {
        val path = root.resolve("no-such-dir/events.db").toString()

        val admission = admitEventStore(path, EventStorePurpose.Read)

        val refusal = assertInstanceOf(
            EventStoreAdmission.Refused::class.java, admission,
            "SQLite refuses this path, so the admission must too",
        ).refusal
        assertInstanceOf(EventStoreRefusal.NoDirectory::class.java, refusal)
        assertEquals(path, (refusal as EventStoreRefusal.NoDirectory).path)
    }

    /**
     * A real file that is not a database is refused, not thrown.
     *
     * `SQLiteException` is a `SQLException`, and both are operational outcomes for an operator, so
     * both must arrive as a reason. `IllegalStateException` from the WAL verification is NOT converted:
     * that one is a defect, and dressing it as a bad argument would hide it.
     */
    @Test
    fun OPEN_2_a_file_that_is_not_a_database_is_refused_not_thrown() {
        val path = root.resolve("garbage.db")
        Files.write(path, ByteArray(2048) { (it * 31 % 251).toByte() })

        val admission = admitEventStore(path.toString(), EventStorePurpose.Read)

        val refusal = assertInstanceOf(EventStoreAdmission.Refused::class.java, admission).refusal
        val notReadable = assertInstanceOf(EventStoreRefusal.NotReadable::class.java, refusal)
        assertTrue(notReadable.detail.isNotEmpty()) { "SQLite's own words are the triage material: $notReadable" }
    }

    /**
     * An openable store with no such run is admitted — the control on over-refusing.
     *
     * A factory that refuses anything it cannot find rows in would be green on every refusal row and
     * broken for the operator, who would be told their database is unusable when it is merely empty.
     */
    @Test
    fun OPEN_3_an_openable_empty_database_is_admitted() {
        val path = root.resolve("empty.db").toString()
        // A database is made by WRITING one, not by pointing a Read at a path that is not there.
        // The first draft of this row passed the path straight to Read and was refused as `Absent` —
        // the correct answer for a Read of an absent file, and the reason the row said one thing and
        // meant another. A control on over-refusing has to control over a REAL subject.
        //
        // The fixture's admission is ASSERTED before it is cast, not cast blindly. Under mutation
        // M-4 a bare cast here dies as a ClassCastException, which names the symptom and hides the
        // cause; the assertion names the cause, which is that a Write was refused.
        val fixture = admitEventStore(path, EventStorePurpose.Write)
        assertInstanceOf(EventStoreAdmission.Admitted::class.java, fixture, "a WRITE must create its own store")
        (fixture as EventStoreAdmission.Admitted).store.close()

        val admission = admitEventStore(path, EventStorePurpose.Read)

        val admitted = assertInstanceOf(EventStoreAdmission.Admitted::class.java, admission)
        admitted.store.use { assertEquals(emptyList<Any>(), it.eventsFor("no-such-run").toList()) }
    }

    /**
     * A WRITE over an empty directory creates the file, which is what a first run does.
     *
     * This is the row that would catch an over-eager guard, and it is the counterpart of `RULE_1` at
     * the level where SQLite actually gets asked.
     */
    @Test
    fun OPEN_4_a_write_creates_its_own_database() {
        val path = root.resolve("fresh/events.db")
        Files.createDirectories(path.parent)

        val admission = admitEventStore(path.toString(), EventStorePurpose.Write)

        val admitted = assertInstanceOf(EventStoreAdmission.Admitted::class.java, admission)
        admitted.store.close()
        assertTrue(Files.isRegularFile(path), "the store the run was admitted must exist afterwards")
    }
}