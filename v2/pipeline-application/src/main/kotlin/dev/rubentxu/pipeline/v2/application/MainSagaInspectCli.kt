package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaActionError
import dev.rubentxu.pipeline.v2.domain.saga.SagaNextAction
import dev.rubentxu.pipeline.v2.domain.saga.SagaOutcome
import dev.rubentxu.pipeline.v2.domain.saga.SagaRunView
import dev.rubentxu.pipeline.v2.events.durable.SagaInspectionProjection
import dev.rubentxu.pipeline.v2.events.durable.SagaInspectionRead
import dev.rubentxu.pipeline.v2.events.durable.SagaInspectionRefusal
import dev.rubentxu.pipeline.v2.events.durable.SqliteSagaObligationReader

/**
 * `pipelinek saga inspect <runId> --db <path>` — what a run still owes, read from the
 * durable promises.
 *
 * ## What this verb is
 *
 * The surface an operator reaches for when a saga did not end cleanly and the question is
 * "what is left, and whose decision is it?". It answers that question from the table where
 * the engine writes every promise BEFORE the effect it promises to undo is allowed to
 * happen, so the answer is the one the engine would act on.
 *
 * ## What this verb is NOT
 *
 *  - NOT a second authority on the state of a saga. It reads the durable promises; it does
 *    not judge them, and it never re-runs anything to find out what happened.
 *  - NOT a reader of logs. The console of a run can be truncated, interleaved or lost; the
 *    promise table cannot, because a promise is written before the effect it protects.
 *  - NOT a follower. It is a static query against a durable store.
 *
 * ## What it never prints
 *
 * No credential, no environment value and no line of the pipeline body. The rows it shows
 * are obligations: which resource, in which phase, in which state, with which receipt.
 *
 * Exit codes follow the canonical contract:
 *   0 — the durable state was read and reported;
 *   2 — usage error, or the durable state could not be read. A refusal says WHY, and never
 *       answers with an empty list that could be read as "nothing is owed".
 */
object MainSagaInspectCli {

    const val USAGE: String =
        "Usage: pipelinek saga inspect <runId> --db <path> [--format text|json]"

    /**
     * Runs the verb and returns the exit code.
     */
    fun main(args: Array<String>): Int {
        val runId = args.firstOrNull()?.takeIf { !it.startsWith("--") }
        if (runId == null) {
            System.err.println("saga inspect: a run id is required.")
            System.err.println(USAGE)
            return 2
        }
        val optionArgs = args.drop(if (args.first() == runId) 1 else 0)

        val options = when (val r = parseOptions(optionArgs.toTypedArray())) {
            is OptionsParse.Parsed -> r.value
            is OptionsParse.Rejected -> {
                System.err.println("saga inspect: ${r.error}")
                System.err.println(USAGE)
                return 2
            }
        }

        if (options.dbPath == null) {
            System.err.println("saga inspect: --db <path> is required to read the durable promises")
            System.err.println(USAGE)
            return 2
        }

        val read = SqliteSagaObligationReader(options.dbPath).read(runId)
        if (read is SagaInspectionRead.Refused) {
            System.err.println("saga inspect: ${read.refusal.render()}")
            return 2
        }
        val state = read as SagaInspectionRead.Read

        // An empty promise list is two different facts, and they are told apart here rather
        // than guessed at: a run that is in this store and armed no promise, and a run that
        // is not in this store at all. The second one is a refusal, because a reader who is
        // told "nothing owed" about a run that does not exist will go and look for the wrong
        // thing.
        if (state.rows.isEmpty()) {
            if (!state.runExists) {
                System.err.println(
                    "saga inspect: no run $runId in ${options.dbPath}. " +
                        "Nothing was read, so nothing can be said about what it owes.",
                )
                return 2
            }
            println("saga inspect: run $runId armed no saga promise; there is nothing owed.")
            return 0
        }

        val view = SagaInspectionProjection.read(runId, state.rows, state.events)
        if (options.format == OutputFormat.JSON) {
            println(renderJson(view))
        } else {
            print(renderText(view))
        }
        return 0
    }

    private enum class OutputFormat { TEXT, JSON }

    private data class Options(val dbPath: String?, val format: OutputFormat)

    private sealed interface OptionsParse {
        data class Parsed(val value: Options) : OptionsParse
        data class Rejected(val error: String) : OptionsParse
    }

    private fun parseOptions(args: Array<String>): OptionsParse {
        var dbPath: String? = null
        var format = OutputFormat.TEXT
        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--db" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return OptionsParse.Rejected("--db needs a path")
                    dbPath = value
                    index += 2
                }

                "--format" -> {
                    val value = args.getOrNull(index + 1)
                        ?: return OptionsParse.Rejected("--format needs a value")
                    format = when (value) {
                        "text" -> OutputFormat.TEXT
                        "json" -> OutputFormat.JSON
                        else -> return OptionsParse.Rejected(
                            "--format accepts text or json, got: $value",
                        )
                    }
                    index += 2
                }

                else -> return OptionsParse.Rejected("unknown option: $option")
            }
        }
        return OptionsParse.Parsed(Options(dbPath, format))
    }

    /**
     * The reading for a person.
     *
     * The columns are the questions an operator asks in order: what was promised, on what
     * resource, where is the promise now, what does the world owe next, and does a person
     * have to decide.
     */
    private fun renderText(view: SagaRunView): String {
        val builder = StringBuilder()
        builder.append("saga inspect: run ").append(view.runId)
        builder.append(" — ").append(view.actions.size).append(" obligation")
        if (view.actions.size != 1) builder.append('s')
        builder.append(", operator needed: ").append(if (view.needsOperator) "yes" else "no")
        builder.append('\n')
        builder.append("run outcome: ").append(outcomeWord(view.outcome)).append('\n')
        view.sagas.forEach { saga ->
            builder.append("saga ").append(sagaWord(saga)).append('\n')
        }

        val headers = listOf(
            "ACTION",
            "COMPENSATION",
            "RESOURCE",
            "PHASE",
            "STATUS",
            "EVIDENCE",
            "NEXT",
            "OPERATOR",
        )
        val rows = view.actions.map { action ->
            listOf(
                action.actionId,
                action.compensationId,
                action.resource.value,
                action.phase.name.lowercase(),
                action.status.name,
                action.evidence?.value ?: "-",
                nextWord(action.next),
                if (action.needsOperator) "yes" else "no",
            )
        }
        val widths = headers.indices.map { column ->
            (listOf(headers[column].length) + rows.map { it[column].length }).max()
        }
        builder.append(renderRow(headers, widths))
        rows.forEach { builder.append(renderRow(it, widths)) }

        // The fingerprint of the plan each promise was armed under. It is what tells an
        // operator whether the promise they are looking at belongs to the plan they think is
        // running, and it is shown whole because a digest that is cut short cannot be compared
        // with anything.
        view.actions.forEach { action ->
            builder.append("plan ").append(action.planFingerprint.hex)
            builder.append(" — ").append(action.sagaId).append('/').append(action.actionId)
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun renderRow(cells: List<String>, widths: List<Int>): String {
        val rendered = cells.mapIndexed { column, cell ->
            cell.padEnd(widths[column])
        }
        return rendered.joinToString("  ").trimEnd() + "\n"
    }

    private fun outcomeWord(outcome: RunOutcome): String = when (outcome) {
        is RunOutcome.Success -> "success"
        is RunOutcome.Unstable -> "unstable"
        is RunOutcome.Aborted -> "aborted"
        is RunOutcome.Failure -> "failure (${outcome.failure.kind}) — ${outcome.failure.message}"
    }

    private fun sagaWord(saga: SagaOutcome): String = when (saga) {
        is SagaOutcome.Settled -> "${saga.sagaId}: settled"
        is SagaOutcome.NeedsIntervention ->
            "${saga.sagaId}: NEEDS_INTERVENTION (${saga.reason})"
    }

    private fun nextWord(next: SagaNextAction): String = when (next) {
        SagaNextAction.None -> "none"
        SagaNextAction.ProceedForward -> "proceed"
        SagaNextAction.SettleForward -> "settle_forward"
        SagaNextAction.Compensate -> "compensate"
        SagaNextAction.SettleCompensation -> "settle_compensation"
        SagaNextAction.Decide -> "decide"
    }

    /**
     * The name of the next step, as the domain spells it.
     */
    private fun nextName(next: SagaNextAction): String = when (next) {
        SagaNextAction.None -> "None"
        SagaNextAction.ProceedForward -> "ProceedForward"
        SagaNextAction.SettleForward -> "SettleForward"
        SagaNextAction.Compensate -> "Compensate"
        SagaNextAction.SettleCompensation -> "SettleCompensation"
        SagaNextAction.Decide -> "Decide"
    }

    /**
     * The reading for a program.
     *
     * The values are the domain's own names, so a consumer does not need a translation table
     * to know what a state means.
     */
    private fun renderJson(view: SagaRunView): String {
        val actions = view.actions.joinToString(",") { action ->
            val error = when (action.error) {
                null -> "null"
                SagaActionError.ForwardOutcomeLost -> "\"ForwardOutcomeLost\""
                SagaActionError.ForwardOutcomeUnknown -> "\"ForwardOutcomeUnknown\""
                SagaActionError.CompensationFailed -> "\"CompensationFailed\""
                SagaActionError.CompensationUncertain -> "\"CompensationUncertain\""
                SagaActionError.SagaStopped -> "\"SagaStopped\""
            }
            "{" +
                "\"sagaId\":\"${action.sagaId}\"," +
                "\"actionId\":\"${action.actionId}\"," +
                "\"compensationId\":\"${action.compensationId}\"," +
                "\"resource\":\"${action.resource.value}\"," +
                "\"compensationKind\":\"${action.compensationKind.name}\"," +
                "\"planFingerprint\":\"${action.planFingerprint.hex}\"," +
                "\"status\":\"${action.status.name}\"," +
                "\"evidence\":${action.evidence?.let { "\"${it.value}\"" } ?: "null"}," +
                "\"phase\":\"${action.phase.name}\"," +
                "\"next\":\"${nextName(action.next)}\"," +
                "\"error\":$error," +
                "\"needsOperator\":${action.needsOperator}" +
                "}"
        }
        val sagas = view.sagas.joinToString(",") { saga ->
            when (saga) {
                is SagaOutcome.Settled -> "{\"sagaId\":\"${saga.sagaId}\",\"outcome\":\"Settled\"}"
                is SagaOutcome.NeedsIntervention ->
                    "{\"sagaId\":\"${saga.sagaId}\",\"outcome\":\"NeedsIntervention\"," +
                        "\"reason\":\"${saga.reason}\"}"
            }
        }
        val outcome = when (val value = view.outcome) {
            is RunOutcome.Success -> "\"Success\""
            is RunOutcome.Unstable -> "\"Unstable\""
            is RunOutcome.Aborted -> "\"Aborted\""
            is RunOutcome.Failure ->
                "{\"Failure\":{\"kind\":\"${value.failure.kind}\",\"message\":\"${value.failure.message}\"}}"
        }
        return "{" +
            "\"runId\":\"${view.runId}\"," +
            "\"needsOperator\":${view.needsOperator}," +
            "\"outcome\":$outcome," +
            "\"sagas\":[$sagas]," +
            "\"actions\":[$actions]" +
            "}"
    }
}

/**
 * Why the durable state could not be read, in words.
 */
private fun SagaInspectionRefusal.render(): String = when (this) {
    is SagaInspectionRefusal.NoSuchDatabase ->
        "there is no database at $path"

    is SagaInspectionRefusal.NotAStore ->
        "$path is not a pipeline store: it has no event log"

    is SagaInspectionRefusal.NoSagaObligations ->
        "$path has no saga promise table, so it cannot say what any saga owes"

    is SagaInspectionRefusal.Unreadable ->
        "the durable state at $path could not be read: $detail"
}
