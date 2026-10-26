package dev.rubentxu.pipeline.v2.runtime.recover

import dev.rubentxu.pipeline.v2.domain.AttemptId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.ParallelFrame
import dev.rubentxu.pipeline.v2.domain.durable.RerunOperation
import dev.rubentxu.pipeline.v2.events.durable.BranchExecutionResult
import dev.rubentxu.pipeline.v2.events.durable.FencingToken
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursor
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.events.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.events.durable.StageIndex
import dev.rubentxu.pipeline.v2.output.OutputChannel
import dev.rubentxu.pipeline.v2.output.OutputFrame
import dev.rubentxu.pipeline.v2.output.OutputFrameIndex
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryPort
import dev.rubentxu.pipeline.v2.output.store.OutputRecoveryReport
import dev.rubentxu.pipeline.v2.runtime.inspect.JournalPosition
import dev.rubentxu.pipeline.v2.runtime.inspect.LeaseHolder
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeIntrospectionResult
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.runtime.control.CancelReceipt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the recover plane is allowed to close, and how.
 *
 * ## The guarantee under trial
 *
 * The recover closes a RUN, and the run's verdict is the verdict of ALL of its
 * work — never the verdict of one operation.
 *
 * It used to be the FIRST operation row whose status is terminal, and that is
 * a fact about an OPERATION. Two different lies came out of it:
 *
 *  1. A run whose first step succeeded and whose second step was still
 *     running already had a "terminal" row, so the recover closed a
 *     half-finished run as a successful one.
 *  2. A run whose first operation failed and whose second one succeeded had
 *     its verdict taken from whichever operation ended last, so a failed run
 *     could be closed as a successful one.
 *
 * The first is what the executable example `durable/40` demonstrates from the
 * outside. The second is not constructible from an example — it needs a run
 * with two operations that ended in a particular order — and it is the one
 * that would fabricate a success, which is the thing a durable replay may
 * never do. So the rule lives here, where it can be observed.
 *
 * ## The rule, and whose it is
 *
 * The run's verdict is the one the DOMAIN computes:
 * `RunOutcomeReducer` is the single authority that folds a run's step verdicts
 * into a run verdict, and it says that success requires EVERY step to have
 * succeeded. The adapter does not have a rule of its own about what a run is;
 * it reads the run's operations and hands the domain's verdict to the
 * published decider.
 *
 * ## Why the stores answer from memory
 *
 * The adapter's authorities are narrow ports, and this test wires them to
 * in-memory answers: the production `InMemoryOperationJournal` and
 * `InMemoryReplayCursorStore`, plus small fakes for the ports that have no
 * in-memory implementation. Nothing here touches a disk, a socket or a clock
 * that moves on its own. That is what makes this a unit test of a decision
 * rather than a second harness of the stores.
 */
class RuntimeRecoverPortStoreAdapterTest {

    private val runId: String = "run-under-trial"

    /**
     * The defect row: the run still has work in flight.
     *
     * Before the fix this returned `RecoveredTerminal`, which is a lie about
     * the run: the run is still going, and the recover closed it as a
     * successful one.
     */
    @Test
    fun `un run con una operacion en vuelo no se cierra`() {
        val fixture = fixture(
            ops = listOf(
                op("op-1", OperationStatus.SUCCEEDED),
                op("op-2", OperationStatus.RUNNING),
            ),
            observation = runningWithDeadWriter(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions.Default)

        assertInstanceOf(
            RecoverOutcome.ReattachPending::class.java,
            outcome,
            "a run with an operation still running is not finished; the recover must not " +
                "close it, and it must not write a terminal row for it",
        )
        assertEquals(
            2,
            fixture.journal.size(),
            "the recover wrote a row for a run that had not finished: the journal is where " +
                "that lie would live",
        )
    }

    /**
     * A run whose work all finished is closed — as what it was.
     */
    @Test
    fun `un run con toda su labor terminada se cierra`() {
        val fixture = fixture(
            ops = listOf(
                op("op-1", OperationStatus.SUCCEEDED),
                op("op-2", OperationStatus.SUCCEEDED),
            ),
            observation = runningWithDeadWriter(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions.Default)

        val recovered = assertInstanceOf(RecoverOutcome.RecoveredTerminal::class.java, outcome)
        assertEquals(
            TerminalObservation.Succeeded(fixture.endedAtOf("op-2")),
            recovered.terminal,
            "every operation succeeded, so the run succeeded",
        )
        assertEquals(
            3,
            fixture.journal.size(),
            "the recover wrote the run's terminal row and nothing else",
        )
    }

    /**
     * A run that failed is a failed run, even when a later operation succeeded.
     *
     * This is the row that says the recover may never fabricate a success.
     */
    @Test
    fun `un run con una operacion fallida no se cierra como exitoso`() {
        val fixture = fixture(
            ops = listOf(
                op("op-1", OperationStatus.FAILED),
                op("op-2", OperationStatus.SUCCEEDED),
            ),
            observation = runningWithDeadWriter(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions.Default)

        val recovered = assertInstanceOf(RecoverOutcome.RecoveredTerminal::class.java, outcome)
        assertInstanceOf(
            TerminalObservation.Failed::class.java,
            recovered.terminal,
            "an operation failed, so the run failed: the domain's reducer says success " +
                "requires EVERY step to have succeeded",
        )
    }

    /**
     * A run that was cancelled is an aborted run, not a successful one.
     *
     * The run's lease was released cleanly — the writer gave it back when it
     * stopped — so the recover can say what the run was.
     */
    @Test
    fun `un run abortado se cierra como abortado`() {
        val fixture = fixture(
            ops = listOf(
                op("op-1", OperationStatus.ABORTED),
                op("op-2", OperationStatus.SUCCEEDED),
            ),
            observation = runningWithoutHolder(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions.Default)

        val recovered = assertInstanceOf(RecoverOutcome.RecoveredTerminal::class.java, outcome)
        assertInstanceOf(
            TerminalObservation.Aborted::class.java,
            recovered.terminal,
            "the orchestrator aborted this run, and an abort is not a success",
        )
    }

    /**
     * The no-rerun invariant: the recover writes the run's terminal row and
     * re-executes nothing.
     *
     * The journal is the evidence: the operations keep their identity and
     * their attempt, and the only new row is the run's terminal one.
     */
    @Test
    fun `la recuperacion no reejecuta`() {
        val fixture = fixture(
            ops = listOf(op("op-1", OperationStatus.SUCCEEDED)),
            observation = runningWithDeadWriter(),
        )

        fixture.adapter.recover(runId, RecoverOptions.Default)

        val rows = fixture.journal.listForRun(runId)
        assertEquals(
            listOf("op-1", "recovered-succeeded-$runId"),
            rows.map { it.id },
            "the recover wrote exactly one row — the run's terminal one — and touched none " +
                "of the run's operations",
        )
        assertEquals(
            1,
            rows.first { it.id == "op-1" }.attempt,
            "the operation was attempted again: the recover re-ran it",
        )
    }

    /**
     * The lease is taken and given back.
     *
     * Taking it is what makes "nobody is publishing this run" a fact; giving
     * it back is what leaves the run closable for whoever comes next.
     */
    @Test
    fun `la lease se toma y se devuelve`() {
        val lease = FakeLease()
        val fixture = fixture(
            ops = listOf(op("op-1", OperationStatus.SUCCEEDED)),
            lease = lease,
            observation = runningWithDeadWriter(),
        )

        fixture.adapter.recover(runId, RecoverOptions.Default)

        assertEquals(
            listOf(runId),
            lease.acquisitions.map { it.runId },
            "the recover took the run's lease",
        )
        assertEquals(
            listOf(lease.acquisitions.single().ownerId),
            lease.releases,
            "the recover gave the lease back, to the owner it took it as",
        )
    }

    /**
     * A run the lease cannot be taken for is refused, and the refusal is
     * typed: someone else is publishing it RIGHT NOW.
     */
    @Test
    fun `una lease que otro tiene se niega con su propia negativa`() {
        val fixture = fixture(
            ops = listOf(op("op-1", OperationStatus.SUCCEEDED)),
            lease = HeldLease(),
            observation = runningWithDeadWriter(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions.Default)

        val failed = assertInstanceOf(RecoverOutcome.FailClosed::class.java, outcome)
        assertInstanceOf(
            RecoverRefusal.LeaseHeldByAnother::class.java,
            failed.reason,
            "another live process owns this run, and the recover must say so instead of " +
                "closing it underneath",
        )
    }

    /**
     * Un run cancelado no se reanuda.
     *
     * El cancel interrumpe el trabajo a propósito, y un step puede quedarse en
     * RUNNING: el run no tiene veredicto propio y su journal no dice que el
     * run terminó. El recover NO debe ofrecer un re-attach de ese run:
     * reanudarlo repetiría un efecto que el step interrumpido puede ya haber
     * tenido, que es justo lo que un cancel quería evitar.
     *
     * El cancel es el hecho terminal del run — lo dice el journal, en su
     * propio vocabulario — y por eso el recover no escribe una fila terminal
     * para un run cancelado: no tiene nada que añadir.
     */
    @Test
    fun `un run cancelado no se reanuda`() {
        val fixture = fixture(
            ops = listOf(
                op("op-1", OperationStatus.RUNNING),
                op(CancelReceipt.opIdOf(runId), OperationStatus.SUCCEEDED),
            ),
            observation = runningWithDeadWriter(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions.Default)

        assertInstanceOf(
            RecoverOutcome.AlreadyRecovered::class.java,
            outcome,
            "a cancelled run is not a run to re-attach: the operator cancelled it on purpose, " +
                "and resuming it could repeat an effect the interrupted step already had",
        )
        assertEquals(
            2,
            fixture.journal.size(),
            "the recover wrote a terminal row for a run that was cancelled: the cancel is the " +
                "run's terminal fact, and the recover has nothing to add to it",
        )
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /**
     * The adapter under trial, with the authorities it composes.
     */
    // ---------------------------------------------------------------------
    // The dry run: a decision without a write
    // ---------------------------------------------------------------------

    /**
     * A dry run takes no lease and writes nothing.
     *
     * The lease is the one thing the recover WRITES before it decides: taking
     * it locks the run and stamps a durable ownership record, and giving it
     * back stamps another. A dry run asked "what would happen?" must not leave
     * a trace, and it must not take the exclusivity either — a dry run that
     * held the lease would make a live writer look busy.
     */
    @Test
    fun `un dry-run no toma la lease ni escribe`() {
        val lease = FakeLease()
        val fixture = fixture(
            ops = listOf(op("op-1", OperationStatus.SUCCEEDED)),
            lease = lease,
            observation = runningWithDeadWriter(),
        )

        fixture.adapter.recover(runId, RecoverOptions(dryRun = true))

        assertEquals(
            emptyList<LeaseRequest>(),
            lease.acquisitions,
            "the dry run took the run's lease: taking it is a write, and it also makes a live " +
                "writer look busy for as long as it lasts",
        )
        assertEquals(
            emptyList<RunOwnerId>(),
            lease.releases,
            "the dry run gave a lease back that it never took",
        )
        assertEquals(
            1,
            fixture.journal.size(),
            "the dry run wrote a row to the journal: a decision that writes is not a decision",
        )
    }

    /**
     * A dry run answers about the RUN, not about the lease.
     *
     * The lease record is not the authority on "is anybody publishing this
     * run" — the OS lock is, and the lock cannot be asked without taking it,
     * which is the write the dry run must not do. So the dry run DECLARES the
     * acquisition it would perform and decides on the state that acquisition
     * would produce: the holder is us, and we are alive.
     *
     * Without that, a run whose writer gave the lease back cleanly — the
     * commonest shape of a crashed run — was reported as unrecoverable, while
     * the real recovery (which takes the lease and then sees itself as the
     * holder) offers the re-attach. The dry run and the recovery have to
     * answer the same question with the same answer.
     */
    @Test
    fun `un dry-run de un run en vuelo sin dueno de lease ofrece reenganche`() {
        val fixture = fixture(
            ops = listOf(op("op-1", OperationStatus.RUNNING)),
            observation = runningWithoutHolder(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions(dryRun = true))

        assertInstanceOf(
            RecoverOutcome.ReattachPending::class.java,
            outcome,
            "the run still has work in flight, so the dry run must offer the re-attach the " +
                "real recovery offers; a run that is in flight is not an unrecoverable substrate",
        )
    }

    /**
     * The re-attach deadline is a wall-clock instant.
     *
     * It used to be the lease's fencing token plus thirty seconds — and a
     * fencing token is an authority counter, not an instant. A run whose token
     * was 100 produced a deadline of 1970-01-01T00:00:30Z, and the operator
     * was told to come back before the epoch.
     *
     * The decider no longer computes a time at all: it says "re-attach" and
     * leaves the instant to the one authority that owns a clock. So the
     * deadline is the clock's now plus the re-attach window.
     */
    @Test
    fun `el plazo de reenganche es un instante y no un token`() {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        val fixture = fixture(
            ops = listOf(op("op-1", OperationStatus.RUNNING)),
            observation = runningWithDeadWriter(),
            clock = FixedClock(now),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions(dryRun = true))

        val pending = assertInstanceOf(RecoverOutcome.ReattachPending::class.java, outcome)
        assertEquals(
            now.toEpochMilli() + 30_000L,
            pending.deadlineMs,
            "the deadline must be an instant the operator can read, taken from the clock — not a " +
                "fencing token dressed up as one",
        )
    }

    /**
     * A cursor that cannot be read is a storage failure, not an absent cursor.
     *
     * The two are opposites and the difference decides the run's fate: an
     * absent cursor means the run never got there, and the recover refuses for
     * that reason; a cursor that could not be READ means the substrate is
     * broken, and the recover must say so with the typed refusal for storage
     * errors. Reading the failure as "there is no cursor" turned a broken
     * substrate into a confident verdict about the run.
     */
    @Test
    fun `un error de lectura del cursor se niega con su propia negativa`() {
        val fixture = fixture(
            ops = listOf(op("op-1", OperationStatus.SUCCEEDED)),
            cursors = FailingCursorStore(),
        )

        val outcome = fixture.adapter.recover(runId, RecoverOptions.Default)

        val failed = assertInstanceOf(RecoverOutcome.FailClosed::class.java, outcome)
        assertInstanceOf(
            RecoverRefusal.StorageError::class.java,
            failed.reason,
            "a cursor that could not be read is a storage failure; reporting it as an absent " +
                "cursor invents a verdict about the run that nobody observed",
        )
    }

    private class Fixture(
        val adapter: RuntimeRecoverPortStoreAdapter,
        val journal: InMemoryOperationJournal,
        val lease: RunLeasePort,
    ) {
        /** When the named operation ended, in epoch millis. */
        fun endedAtOf(opId: String): Long = journal.getEndedAt(opId, attempt = 1)!!
    }

    /**
     * A clock that advances one second per reading.
     *
     * The journal stamps a row's `endedAt` when it is appended, so a clock
     * that does not move would give every operation the same end time — and
     * the verdict of a run is decided by the ORDER in which its operations
     * ended. This one is the cheapest way to say "these ended in this order".
     */
    private class SteppingClock : Clock {
        private var ticks: Long = 0

        override fun now(): Instant = Instant.parse("2026-10-21T10:00:00Z").plusSeconds(ticks++)
    }

    /**
     * A clock that never moves, for the rows whose exact instant is the claim.
     */
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    /**
     * A cursor store whose READ fails and whose writes work.
     *
     * The failure sits on the read because that is where the two opposites
     * live: a cursor that is absent, and a cursor that could not be read.
     */
    private class FailingCursorStore : ReplayCursorStore {
        private val inner = InMemoryReplayCursorStore(SteppingClock())

        override fun load(runId: String): ReplayCursor? =
            throw IllegalStateException("the cursor store is unavailable")

        override fun advance(runId: String, opId: String, stageIndex: Int) =
            inner.advance(runId, opId, stageIndex)

        override fun advancePastParallelFrame(
            runId: String,
            frame: ParallelFrame,
            branchResults: List<BranchExecutionResult>,
            explicitMaxStageIndex: Int?,
        ): StageIndex = inner.advancePastParallelFrame(runId, frame, branchResults, explicitMaxStageIndex)
    }

    private fun fixture(
        ops: List<RerunOperation>,
        lease: RunLeasePort = FakeLease(),
        observation: RuntimeObservation = runningWithDeadWriter(),
        clock: Clock = SteppingClock(),
        cursors: ReplayCursorStore = InMemoryReplayCursorStore(clock),
    ): Fixture {
        val journal = InMemoryOperationJournal(clock)
        val frames = QuietFrameIndex()
        ops.forEach { op -> journal.append(op, deadlineMs = null) }
        // A run that got this far has a cursor: the writer advanced it.
        cursors.advance(runId, ops.lastOrNull()?.id ?: "op-0", stageIndex = 0)
        val adapter = RuntimeRecoverPortStoreAdapter(
            journal = journal,
            cursors = cursors,
            lease = lease,
            outputRecovery = NoOutputRecovery,
            frames = frames,
            introspect = RuntimeRecoverPortIntrospect {
                RuntimeIntrospectionResult.Observation(observation)
            },
            clock = clock,
        )
        return Fixture(adapter, journal, lease)
    }

    private fun op(id: String, status: OperationStatus): RerunOperation = RerunOperation(
        id = id,
        fingerprint = Fingerprint("a".repeat(64)),
        input = OperationInput(
            stepId = "step-$id",
            params = emptyMap(),
            runId = runId,
            attempt = 1,
        ),
        output = null,
        status = status,
        attempt = 1,
    )

    /**
     * The run is in flight and its writer is gone: the lease names an owner
     * that is not alive. This is the shape a recover exists for.
     */
    private fun runningWithDeadWriter(): RuntimeObservation.Running = RuntimeObservation.Running(
        attempt = AttemptId(1),
        leaseHolder = LeaseHolder(ownerId = "writer-that-died", fencingToken = 7L, alive = false),
        fencingToken = 7L,
        journalPosition = JournalPosition(operations = 1, latestOpId = "op-1", latestTerminalAtMs = null),
        outputTails = emptyList(),
    )

    /**
     * The run is in flight and nobody holds its lease: the writer gave the
     * lease back when it stopped.
     */
    private fun runningWithoutHolder(): RuntimeObservation.Running = RuntimeObservation.Running(
        attempt = AttemptId(1),
        leaseHolder = null,
        fencingToken = 7L,
        journalPosition = JournalPosition(operations = 1, latestOpId = "op-1", latestTerminalAtMs = null),
        outputTails = emptyList(),
    )

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    /**
     * A lease nobody holds, which counts what it was asked for.
     */
    private class FakeLease : RunLeasePort {
        val acquisitions = mutableListOf<LeaseRequest>()
        val releases = mutableListOf<RunOwnerId>()

        override fun acquire(request: LeaseRequest): LeaseAcquisition {
            acquisitions += request
            return LeaseAcquisition.Acquired(
                record = LeaseRecord(
                    runId = request.runId,
                    ownerId = request.ownerId,
                    fencingToken = FencingToken.FIRST,
                    ownerAlive = true,
                ),
                fencingToken = FencingToken.FIRST,
            )
        }

        override fun release(ownerId: RunOwnerId) {
            releases += ownerId
        }

        override fun isKnown(runId: String): Boolean = true

        override fun observe(runId: String): LeaseRecord? = null
    }

    /**
     * A lease another live process holds right now.
     */
    private class HeldLease : RunLeasePort {
        private val holder = RunOwnerId.of("live-writer")!!

        override fun acquire(request: LeaseRequest): LeaseAcquisition = LeaseAcquisition.AlreadyOwned(
            record = LeaseRecord(
                runId = request.runId,
                ownerId = holder,
                fencingToken = FencingToken.of(3L),
                ownerAlive = true,
            ),
            heldBy = holder,
            fencingToken = FencingToken.of(3L),
        )

        override fun release(ownerId: RunOwnerId) = Unit

        override fun isKnown(runId: String): Boolean = true

        override fun observe(runId: String): LeaseRecord? = null
    }

    /**
     * An output store with nothing to reconcile.
     */
    private object NoOutputRecovery : OutputRecoveryPort {
        override fun recover(): OutputRecoveryReport = OutputRecoveryReport(
            streamsReconciled = 0,
            streamsOwned = 0,
            committedBytes = 0,
            bytesReleased = 0,
            reservationsReleased = 0,
            bytesUnbacked = 0,
        )
    }

    /**
     * A frame index with no frames, and no opinion about them.
     */
    private class QuietFrameIndex : OutputFrameIndex {
        override fun declareStream(stream: OutputStreamId, channel: OutputChannel) = Unit

        override fun append(stream: OutputStreamId, channel: OutputChannel, from: Long, to: Long): OutputFrame =
            error("the recover must not append frames")

        override fun framesOfRun(runId: String, afterOrdinal: Long, limit: Int): List<OutputFrame> = emptyList()

        override fun lastOrdinal(runId: String): Long? = null

        override fun streamsOfRun(runId: String): List<OutputStreamId> = emptyList()

        override fun recoverUnframedBytes(): List<OutputFrame> = emptyList()
    }
}
