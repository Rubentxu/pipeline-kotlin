package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.runtime.control.CancelOutcome
import dev.rubentxu.pipeline.v2.runtime.control.CancelReason
import dev.rubentxu.pipeline.v2.runtime.control.CancelRefusal
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

    private const val USAGE = """Usage: pipelinek runs cancel <runId> [options]

Closes a run that no live process is working on, and refuses to close one that is.

Options:
  --db <path>           The durable event store of the run (required).
  --control-root <path> The control directory of the run. Defaults to the same
                        place the `run` verb puts it: <db's directory>/durable-shell.
  --reason <text>       Why the run is being closed. Recorded verbatim in the
                        run's history, so do not paste secrets into it.

Exit codes: 0 closed (or already closed), 2 refused.""" 

    fun main(args: Array<String>): Int {
        val verb = args.firstOrNull()
        if (verb != "cancel") {
            System.err.println("runs: the only verb is 'cancel'.")
            System.err.println(USAGE)
            return 2
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
    )

    private sealed interface OptionParse {
        data class Parsed(val value: Options) : OptionParse
        data class Rejected(val error: String) : OptionParse
    }

    private fun parseOptions(args: Array<String>): OptionParse {
        var dbPath: String? = null
        var controlRoot: Path? = null
        var reason: String? = null
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

                else -> return OptionParse.Rejected("unknown option '$arg'")
            }
            index++
        }
        return OptionParse.Parsed(Options(dbPath, controlRoot, reason))
    }
}
