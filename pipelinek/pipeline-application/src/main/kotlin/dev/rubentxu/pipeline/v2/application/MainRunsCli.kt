package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.control.CancelOutcome
import dev.rubentxu.pipeline.v2.runtime.control.CancelReason
import dev.rubentxu.pipeline.v2.runtime.control.CancelRefusal
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import dev.rubentxu.pipeline.v2.runtime.recover.RecoverOptions
import dev.rubentxu.pipeline.v2.runtime.recover.RecoverOutcome
import dev.rubentxu.pipeline.v2.runtime.recover.RecoverRefusal
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `pipelinek runs ...` — the operator's verbs over a run.
 *
 * ## Why this verb exists
 * A run can die in a way nobody planned: the agent is killed, the machine
 * reboots, the CI job is reaped. The run's facts then say two different
 * things at once — the journal says an operation was still running, and the
 * event lane says the run never finished — and the operator has no way to
 * close it. Until now the only way out was to start a new run, which leaves
 * the dead one hanging forever.
 *
 * `runs cancel` is that way out: it closes a run nobody is working on, and it
 * refuses to close one that somebody is.
 *
 * ## What it does, in order
 *  1. asks the stores whether they know the run at all;
 *  2. takes the run's lease, which is what proves that no live process is
 *     publishing it (the lock is the authority, not the lease record);
 *  3. writes the run's terminal bookend in the event lane, records the
 *     cancellation in the journal, and seals the output streams the dead
 *     writer left open;
 *  4. releases the lease.
 *
 * ## Exit codes
 *  - **0** — the run is closed (now, or it already was). A second cancel is a
 *    no-op and still exits 0, because idempotency is the contract.
 *  - **2** — the cancel was refused, or the arguments were wrong. The refusal
 *    is on stderr and names the reason.
 *
 * ## The reason is recorded verbatim
 * `--reason` lands in the run's history, in the journal and in the terminal
 * bookend. It is operator text, written as given: this verb does not open the
 * credentials store, so it has no credential pattern to redact against, and
 * the usage says not to paste secrets into a reason.
 */
internal object MainRunsCli {

    private const val USAGE = """Usage: pipelinek runs <cancel|recover> <runId> [options]

Closes a run that no live process is working on, and refuses to close one that is.

  cancel   closes the run as aborted and records why.
  recover  reconciles a run whose work finished but whose terminal was never
           recorded, WITHOUT re-running any effect.

Options:
  --db <path>           The durable event store of the run (required).
  --control-root <path> The control directory of the run. Defaults to the same
                        place the `run` verb puts it: <db's directory>/durable-shell.
  --reason <text>       Why the run is being closed (cancel only). Recorded
                        verbatim in the run's history, so do not paste secrets into it.
  --dry-run             recover only: compute the decision without writing.

Exit codes: 0 closed (or already closed), 2 refused."""

    fun main(args: Array<String>): Int {
        val verb = args.firstOrNull()
        if (verb != "cancel" && verb != "recover") {
            System.err.println("runs: the verbs are 'cancel' and 'recover'.")
            System.err.println(USAGE)
            return 2
        }

        if (verb == "recover") {
            return runRecover(args.drop(1).toTypedArray())
        }

        val rest = args.drop(1)
        val runId = rest.firstOrNull()?.takeIf { !it.startsWith("--") }
        if (runId == null) {
            System.err.println("runs cancel: a run id is required.")
            System.err.println(USAGE)
            return 2
        }
        val optionArgs = rest.drop(if (rest.first() == runId) 1 else 0)

        val options = when (val parsed = parseOptions(optionArgs.toTypedArray())) {
            is OptionParse.Parsed -> parsed.value
            is OptionParse.Rejected -> {
                System.err.println("runs cancel: ${parsed.error}")
                System.err.println(USAGE)
                return 2
            }
        }

        if (options.dbPath == null) {
            System.err.println("runs cancel: --db <path> is required to read the run's durable state")
            System.err.println(USAGE)
            return 2
        }

        val reason = when (val text = options.reason) {
            null -> CancelReason.UserRequested
            else -> try {
                CancelReason.Annotated(text)
            } catch (failure: IllegalArgumentException) {
                System.err.println("runs cancel: --reason ${failure.message}")
                return 2
            }
        }

        // The control root defaults to the same place the `run` verb puts it,
        // so an operator who never passed --control-root can still close the
        // run they started.
        val controlRoot: Path = options.controlRoot
            ?: Paths.get(options.dbPath).toAbsolutePath().parent.resolve("durable-shell")

        val admission = admitEventStore(options.dbPath, EventStorePurpose.Write)
        if (admission is EventStoreAdmission.Refused) {
            System.err.println(admission.refusal.render())
            return 2
        }
        val store = (admission as EventStoreAdmission.Admitted).store

        val outcome = try {
            store.use { eventStore ->
                composeRuntimeControlPort(
                    eventStore = eventStore,
                    controlRoot = controlRoot,
                    clock = SystemClock(),
                ).cancel(runId, reason)
            }
        } catch (failure: Exception) {
            System.err.println("runs cancel: ${failure.shortDiagnostic()}")
            return 2
        }

        return when (outcome) {
            is CancelOutcome.Cancelled -> {
                println(
                    "cancelled run $runId at ${outcome.terminalAtMs} " +
                        "(${outcome.streamsSealed} output stream(s) sealed)",
                )
                0
            }

            is CancelOutcome.AlreadyCancelled -> {
                println("run $runId was already cancelled at ${outcome.terminalAtMs}")
                0
            }

            is CancelOutcome.Refused -> {
                System.err.println("runs cancel: ${outcome.refusal.render(runId)}")
                2
            }
        }
    }

    /**
     * `runs recover` — reconcile a run whose work finished but whose terminal
     * was never recorded, WITHOUT re-running any effect.
     *
     * ## What it does, in order
     *  1. takes the run's lease, which proves that no live process is
     *     publishing it (the lock is the authority, not the lease record);
     *  2. asks the published introspection port what the run looks like;
     *  3. hands the facts to the published recovery decider;
     *  4. records the run's terminal in the journal, advances the replay
     *     cursor and reconciles the output store — and re-runs NOTHING.
     *
     * ## What it refuses to do
     * It does not close a run that has not finished its work. A run whose
     * last operation is still running is not a run that finished, and
     * recovering it would publish a result nobody observed. That run is the
     * `runs cancel` case.
     */
    private fun runRecover(rest: Array<String>): Int {
        val runId = rest.firstOrNull()?.takeIf { !it.startsWith("--") }
        if (runId == null) {
            System.err.println("runs recover: a run id is required.")
            System.err.println(USAGE)
            return 2
        }
        val optionArgs = rest.drop(if (rest.first() == runId) 1 else 0)

        val options = when (val parsed = parseOptions(optionArgs.toTypedArray())) {
            is OptionParse.Parsed -> parsed.value
            is OptionParse.Rejected -> {
                System.err.println("runs recover: ${parsed.error}")
                System.err.println(USAGE)
                return 2
            }
        }

        if (options.dbPath == null) {
            System.err.println("runs recover: --db <path> is required to read the run's durable state")
            System.err.println(USAGE)
            return 2
        }

        val controlRoot: Path = options.controlRoot
            ?: Paths.get(options.dbPath).toAbsolutePath().parent.resolve("durable-shell")

        // A recover WRITES: the journal's terminal row, the replay cursor and
        // the output store's reconciliation. The event store itself is only
        // read, but it shares the database with the journal, so the admission
        // is the write one.
        //
        // A dry run writes NOTHING, so it must not even OPEN the write lane:
        // the write lane's journal_mode=WAL is a header write, and a writable
        // close checkpoints the WAL and removes the -wal and -shm files. The
        // read lane is the door the engine refuses to write through.
        val admission = admitEventStore(
            options.dbPath,
            if (options.dryRun) EventStorePurpose.Read else EventStorePurpose.Write,
        )
        if (admission is EventStoreAdmission.Refused) {
            System.err.println(admission.refusal.render())
            return 2
        }
        val store = (admission as EventStoreAdmission.Admitted).store

        val outcome = try {
            store.use { eventStore ->
                composeRuntimeRecoverPort(
                    eventStore = eventStore,
                    controlRoot = controlRoot,
                    clock = SystemClock(),
                    dryRun = options.dryRun,
                ).recover(runId, RecoverOptions(dryRun = options.dryRun))
            }
        } catch (failure: Exception) {
            System.err.println("runs recover: ${failure.shortDiagnostic()}")
            return 2
        }

        return when (outcome) {
            is RecoverOutcome.RecoveredTerminal -> {
                // A dry run recovered NOTHING: it said what the recovery WOULD
                // do. Printing "recovered" for a run that is still in flight is
                // a lie the operator can act on — they would walk away from a
                // run that is still broken.
                val verb = if (options.dryRun) "would recover" else "recovered"
                println("$verb run $runId as ${outcome.terminal.render()} at ${outcome.terminalAtMs}")
                0
            }

            is RecoverOutcome.AlreadyRecovered -> {
                println("run $runId was already recovered at ${outcome.terminalAtMs}")
                0
            }

            is RecoverOutcome.ReattachPending -> {
                println(
                    "run $runId is still in flight (retry by ${outcome.deadlineMs.asInstant()}); " +
                        "nothing was recovered",
                )
                0
            }

            is RecoverOutcome.FailClosed -> {
                System.err.println("runs recover: ${outcome.reason.render(runId)}")
                2
            }
        }
    }

    /** The operator's text for a refusal. The closed reasons live in the port. */
    private fun CancelRefusal.render(runId: String): String = when (this) {
        is CancelRefusal.UnknownRun ->
            "this instance does not know run '$runId'."

        is CancelRefusal.RunTerminal ->
            "run $runId already finished with outcome '$outcome'."

        is CancelRefusal.LeaseHeldByAnother ->
            "run $runId is being published by '$ownerId' (fencing token $fencingToken). " +
                "A live writer owns it; stop that process first."

        is CancelRefusal.IncompatibleRunState ->
            "run $runId is in a state a cancel does not apply to: $details"

        is CancelRefusal.JournalUnavailable ->
            "the run's journal could not be written: $cause"

        is CancelRefusal.StorageError ->
            "the run's storage failed: $cause"
    }

    /** The recovered terminal, in the operator's words. */
    private fun TerminalObservation.render(): String = when (this) {
        is TerminalObservation.Succeeded -> "succeeded"
        is TerminalObservation.Failed -> "failed"
        is TerminalObservation.Unstable -> "unstable"
        is TerminalObservation.Aborted -> "aborted"
        is TerminalObservation.Cancelled -> "cancelled"
        is TerminalObservation.Other -> rawOutcome
    }

    /**
     * An instant the operator can read.
     *
     * Epoch millis are a wire format, not a text: "1793016030000" says
     * nothing about WHEN to come back. The deadline is a wall-clock instant,
     * so it is printed as one.
     */
    private fun Long.asInstant(): String = java.time.Instant.ofEpochMilli(this).toString()

    /** The refusal, in the operator's words. */
    private fun RecoverRefusal.render(runId: String): String = when (this) {
        is RecoverRefusal.UnknownRun -> "this instance does not know run '$runId'."
        RecoverRefusal.NotRecoverable -> "run $runId is in a state a recover does not apply to."
        is RecoverRefusal.SubstrateUnavailable ->
            "the run's substrate could not be observed: ${cause}"

        is RecoverRefusal.JournalIncompatible ->
            "the run's journal is at schema version $version, which this build cannot read."

        is RecoverRefusal.LeaseHeldByAnother ->
            "run $runId is being published by another process: $details"

        is RecoverRefusal.StorageError -> "the run's storage failed: $cause"
        is RecoverRefusal.PinnedBytesOutsideRecoveredRegion ->
            "run $runId has pinned bytes outside the recovered region (stream ${stream}, range $range)."
    }

    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(160)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }

    /** The options this verb takes. */
    private data class Options(
        val dbPath: String?,
        val controlRoot: Path?,
        val reason: String?,
        /** recover only: compute the decision without writing. */
        val dryRun: Boolean = false,
    )

    private sealed interface OptionParse {
        data class Parsed(val value: Options) : OptionParse
        data class Rejected(val error: String) : OptionParse
    }

    private fun parseOptions(args: Array<String>): OptionParse {
        var dbPath: String? = null
        var controlRoot: Path? = null
        var reason: String? = null
        var dryRun = false
        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "--db" -> {
                    dbPath = args.getOrNull(++index)
                        ?: return OptionParse.Rejected("--db requires a value")
                }

                "--control-root" -> {
                    val raw = args.getOrNull(++index)
                        ?: return OptionParse.Rejected("--control-root requires a value")
                    controlRoot = Paths.get(raw)
                }

                "--reason" -> {
                    reason = args.getOrNull(++index)
                        ?: return OptionParse.Rejected("--reason requires a value")
                }

                "--dry-run" -> dryRun = true

                else -> return OptionParse.Rejected("unknown option '$arg'")
            }
            index++
        }
        return OptionParse.Parsed(Options(dbPath, controlRoot, reason, dryRun))
    }
}
