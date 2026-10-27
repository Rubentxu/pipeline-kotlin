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
 *
 * ## Each verb owns its options, and the other's are REFUSED
 * `--reason` belongs to `cancel` and `--dry-run` belongs to `recover`. A verb
 * that is handed the other's option does not ignore it: it refuses the whole
 * invocation with exit code 2 and says which option does not belong.
 *
 * The two are not the same mistake, and the difference is the whole point:
 *
 *  - `runs cancel --dry-run` used to CLOSE THE RUN. The cancel path never
 *    read the flag, so an operator asking for a simulation got a real
 *    closure and a green exit code over it;
 *  - `runs recover --reason <text>` used to DROP THE TEXT. The recover path
 *    never read it, so an operator who annotated their recovery believed they
 *    had, and nothing said otherwise.
 *
 * An option that is silently dropped is an option the operator believes was
 * honoured. An option that is silently honoured as something else is worse.
 */
internal object MainRunsCli {

    /**
     * The word that opens this family.
     *
     * It is the CLI's command name, and it lives here because everything that
     * names the family reads it: the dispatch in `main`, the usage below, the
     * refusal, and the inventory that renders the help.
     */
    const val FAMILY = "runs"

    private val USAGE = """Usage: pipelinek $FAMILY <${RunsVerb.entries.joinToString("|") { it.spelling }}> <runId> [options]

Closes a run that no live process is working on, and refuses to close one that is.

  cancel   closes the run as aborted and records why.
  recover  reconciles a run whose work finished but whose terminal was never
           recorded, WITHOUT re-running any effect.

Options:
  --db <path>           The durable event store of the run (required).
  --control-root <path> The control directory of the run. Defaults to the same
                        place the `run` verb puts it: <db's directory>/durable-shell.
  --reason <text>       Why the run is being closed (cancel only; 'runs recover'
                        refuses it). Recorded verbatim in the run's history, so do
                        not paste secrets into it.
  --dry-run             recover only ('runs cancel' refuses it): compute the
                        decision without writing.

Exit codes:
  cancel   0 the run is closed, or already was. 2 the cancel was refused.
  recover  0 the recovery was decided: the run was recovered, would be recovered
           by a dry run, was already recovered, or is still in flight. 2 the
           recovery was refused."""

    fun main(args: Array<String>): Int {
        val verb = args.firstOrNull()
        val known = RunsVerb.entries.firstOrNull { it.spelling == verb }
        if (known == null) {
            val code = refuse(runsFamilyDoor(verb), FAMILY)
            System.err.println(USAGE)
            return code
        }

        val rest = args.drop(1).toTypedArray()
        return if (known == RunsVerb.Recover) {
            runRecover(rest)
        } else {
            runCancel(rest)
        }
    }

    /**
     * `runs cancel` — close a run that no live process is working on.
     *
     * ## It takes its OWN options
     * `--dry-run` is REFUSED here, not ignored. This verb closes the run for
     * real: it takes the lease, writes the terminal bookend and seals the
     * output streams. An operator who asked for a simulation and got a
     * closure would be reading a green exit code over a run they wanted
     * untouched, and there is no way to undo that.
     */
    private fun runCancel(rest: Array<String>): Int {
        val runId = rest.firstOrNull()?.takeIf { !it.startsWith("--") }
        if (runId == null) {
            val code = refuse(
                CliError.MissingRequiredArgument("run id", "name the run to close"),
                "runs cancel",
            )
            System.err.println(USAGE)
            return code
        }
        val optionArgs = rest.drop(if (rest.first() == runId) 1 else 0)

        val options = when (val parsed = parseCancelArguments(optionArgs.toTypedArray())) {
            is OptionParse.Parsed -> parsed.value
            is OptionParse.Rejected -> {
                val code = refuse(parsed.error, "runs cancel")
                System.err.println(USAGE)
                return code
            }
        }

        if (options.dbPath == null) {
            val code = refuse(
                CliError.MissingRequiredArgument("--db <path>", "to read the run's durable state"),
                "runs cancel",
            )
            System.err.println(USAGE)
            return code
        }

        val reason = when (val text = options.reason) {
            null -> CancelReason.UserRequested
            else -> try {
                CancelReason.Annotated(text)
            } catch (failure: IllegalArgumentException) {
                return refuse(
                    CliError.InvalidOptionValue("--reason", failure.message ?: "must not be blank"),
                    "runs cancel",
                )
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
     *
     * It also refuses a `--reason`: this verb records the terminal it
     * OBSERVED, and it has no operator's reason to record.
     *
     * ## What its exit code means
     * Zero does NOT mean "the run was closed". It means the recovery was
     * DECIDED: the run was recovered, a dry run would recover it, it was
     * already recovered, or it is still in flight and nothing was recovered.
     * Only the last two are answers about a run that is still open, and the
     * text on stdout says which one it is. Two is a refusal, and the refusal
     * is on stderr.
     */
    private fun runRecover(rest: Array<String>): Int {
        val runId = rest.firstOrNull()?.takeIf { !it.startsWith("--") }
        if (runId == null) {
            val code = refuse(
                CliError.MissingRequiredArgument("run id", "name the run to recover"),
                "runs recover",
            )
            System.err.println(USAGE)
            return code
        }
        val optionArgs = rest.drop(if (rest.first() == runId) 1 else 0)

        val options = when (val parsed = parseRecoverArguments(optionArgs.toTypedArray())) {
            is OptionParse.Parsed -> parsed.value
            is OptionParse.Rejected -> {
                val code = refuse(parsed.error, "runs recover")
                System.err.println(USAGE)
                return code
            }
        }

        if (options.dbPath == null) {
            val code = refuse(
                CliError.MissingRequiredArgument("--db <path>", "to read the run's durable state"),
                "runs recover",
            )
            System.err.println(USAGE)
            return code
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

    /**
     * The verbs this CLI offers.
     *
     * The spelling lives HERE, once: the dispatch, the usage, the option
     * ownership and the help inventory all read it, so the name of a verb is
     * not written in several places that can drift apart.
     *
     * The summary is the line the main help prints next to the form. It
     * lives here for the same reason: a verb the operator cannot find is a
     * verb the operator cannot use, and `runs recover` was implemented,
     * reachable, and missing from the help.
     */
    internal enum class RunsVerb(val spelling: String, val summary: String) {
        /** Closes the run as aborted and records why. */
        Cancel(
            spelling = "cancel",
            summary = "close a run that no live process is working on (--db, --control-root, --reason)",
        ),

        /** Reconciles a run whose work finished but whose terminal was never recorded. */
        Recover(
            spelling = "recover",
            summary = "reconcile a run whose work finished but whose terminal was never recorded " +
                "(--db, --control-root, --dry-run)",
        ),
    }

    /** The options `runs cancel` takes. */
    internal data class CancelArguments(
        val dbPath: String?,
        val controlRoot: Path?,
        val reason: String?,
    )

    /** The options `runs recover` takes. */
    internal data class RecoverArguments(
        val dbPath: String?,
        val controlRoot: Path?,
        /** compute the decision without writing. */
        val dryRun: Boolean = false,
    )

    /**
     * The outcome of reading a verb's options.
     */
    internal sealed interface OptionParse<out T> {
        /** The options the operator wrote. */
        data class Parsed<T>(val value: T) : OptionParse<T>

        /**
         * The options the operator did NOT write, and why they cannot be read.
         *
         * The reason is a [CliError] and not a sentence: the words the operator
         * reads belong to [toCliExit], and this family used to write its own
         * ("unknown option '--x'"), which is how the same mistake got two
         * different sentences depending on the verb that received it.
         */
        data class Rejected(val error: CliError) : OptionParse<Nothing>
    }

    /**
     * The options of `runs cancel`.
     *
     * A `--dry-run` is REFUSED, not ignored: this verb closes the run for
     * real, and a simulation that closed it anyway would be the answer the
     * operator could not recover from.
     */
    internal fun parseCancelArguments(args: Array<String>): OptionParse<CancelArguments> =
        when (val scanned = scanOptions(args, RunsVerb.Cancel)) {
            is OptionParse.Rejected -> scanned
            is OptionParse.Parsed -> OptionParse.Parsed(
                CancelArguments(
                    dbPath = scanned.value.dbPath,
                    controlRoot = scanned.value.controlRoot,
                    reason = scanned.value.reason,
                ),
            )
        }

    /**
     * The options of `runs recover`.
     *
     * A `--reason` is REFUSED, not ignored: this verb records the terminal it
     * OBSERVED, and it has no operator's reason to record. Silently dropping
     * the text would let an operator believe they had annotated the run.
     */
    internal fun parseRecoverArguments(args: Array<String>): OptionParse<RecoverArguments> =
        when (val scanned = scanOptions(args, RunsVerb.Recover)) {
            is OptionParse.Rejected -> scanned
            is OptionParse.Parsed -> OptionParse.Parsed(
                RecoverArguments(
                    dbPath = scanned.value.dbPath,
                    controlRoot = scanned.value.controlRoot,
                    dryRun = scanned.value.dryRun,
                ),
            )
        }

    /**
     * The options as the operator wrote them, read once.
     *
     * The scan knows WHICH verb is asking, because an option that belongs to
     * the other verb is refused HERE: an option that is merely ignored
     * downstream is an option the operator believes was honoured.
     */
    private fun scanOptions(args: Array<String>, verb: RunsVerb): OptionParse<Scanned> {
        var dbPath: String? = null
        var controlRoot: Path? = null
        var reason: String? = null
        var dryRun = false
        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "--db" -> {
                    dbPath = args.getOrNull(++index)
                        ?: return OptionParse.Rejected(CliError.MissingOptionValue("--db"))
                }

                "--control-root" -> {
                    val raw = args.getOrNull(++index)
                        ?: return OptionParse.Rejected(CliError.MissingOptionValue("--control-root"))
                    controlRoot = Paths.get(raw)
                }

                "--reason" -> {
                    if (verb != RunsVerb.Cancel) {
                        return OptionParse.Rejected(
                            CliError.OptionBelongsToVerb("--reason", "runs cancel", REASON_BELONGS_TO_CANCEL),
                        )
                    }
                    reason = args.getOrNull(++index)
                        ?: return OptionParse.Rejected(CliError.MissingOptionValue("--reason"))
                }

                "--dry-run" -> {
                    if (verb != RunsVerb.Recover) {
                        return OptionParse.Rejected(
                            CliError.OptionBelongsToVerb("--dry-run", "runs recover", DRY_RUN_BELONGS_TO_RECOVER),
                        )
                    }
                    dryRun = true
                }

                else -> return OptionParse.Rejected(CliError.UnknownOption(arg))
            }
            index++
        }
        return OptionParse.Parsed(Scanned(dbPath, controlRoot, reason, dryRun))
    }

    /** The options as they were written, before the verb claims them. */
    private data class Scanned(
        val dbPath: String?,
        val controlRoot: Path?,
        val reason: String?,
        val dryRun: Boolean,
    )

    /**
     * Why `--reason` is refused by the verb that has no reason to record.
     *
     * The reason only: the sentence that names the option and the verb that
     * owns it is rendered by [toCliExit], so this text cannot disagree with
     * the rest of the CLI about who owns what.
     */
    private const val REASON_BELONGS_TO_CANCEL =
        "'runs recover' records the terminal it observed and has no reason to record."

    /** Why `--dry-run` is refused by the verb that cannot be simulated. */
    private const val DRY_RUN_BELONGS_TO_RECOVER =
        "'runs cancel' closes the run for real, so it cannot be simulated."
}

/**
 * The refusal of a request that names no verb of the runs family.
 *
 * The family word ALONE and an UNKNOWN verb are two different facts, and
 * the door used to answer both with the same sentence: `pipelinek runs` was
 * told it did not know a subcommand, when what it did not have was a
 * subcommand at all. The missing case names the absence; the unknown one
 * names the token and the verbs that exist.
 *
 * It lives beside the family and not inside it: the door is about the
 * family's OWN words, and it is the only place that reads the enum to name
 * them.
 */
private fun runsFamilyDoor(verb: String?): CliError = if (verb == null) {
    CliError.MissingRequiredArgument("subcommand", "the verbs are ${runsVerbsSpelled()}")
} else {
    CliError.UnknownSubcommand(verb, MainRunsCli.RunsVerb.entries.map { it.spelling })
}

/**
 * The verbs as the operator reads them: `'cancel' and 'recover'`.
 *
 * Derived from the enum, so the refusal cannot name a verb that does not
 * exist and cannot leave one out.
 */
private fun runsVerbsSpelled(): String {
    val spellings = MainRunsCli.RunsVerb.entries.map { it.spelling }
    return when (spellings.size) {
        0 -> ""
        1 -> "'${spellings.first()}'"
        else -> spellings.dropLast(1).joinToString(", ") { "'$it'" } + " and '${spellings.last()}'"
    }
}
