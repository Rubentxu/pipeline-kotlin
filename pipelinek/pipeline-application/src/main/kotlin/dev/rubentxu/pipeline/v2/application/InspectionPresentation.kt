package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.runtime.inspect.IntrospectionRefusal
import dev.rubentxu.pipeline.v2.runtime.inspect.RuntimeObservation
import dev.rubentxu.pipeline.v2.runtime.inspect.TerminalObservation
import java.io.PrintStream

/**
 * The PRESENTATION of an inspection: how the rows and the run's state are written.
 *
 * ## What this owns
 *
 * Every sentence and every byte the verb puts in front of the operator: the document itself (in
 * the text and in the JSON shape), the default columns, the one line the state surface prints and
 * the sentences its refusals are rendered with.
 *
 * ## What it does not own
 *
 * The rows (that is [InspectionSelection]) and the reading of the durable lanes (that is
 * [InspectionReader]). A renderer that also decided which rows to show would make the document's
 * shape depend on the data, and the same query rendered twice could answer two different
 * questions.
*/
internal object InspectionPresentation {

    /**
     * Write the inspection document.
     *
     * The shape is fixed: the text form is a header, a column line and one line per row; the
     * JSON form is an object with `runId` and `records`. The columns are the ones the query asked
     * for, or the default set when it asked for none.
    */
    fun emitDocument(
        out: PrintStream,
        runId: String,
        q: CompiledInspectionQuery,
        records: List<InspectionRecord>,
    ) {
        val cols = q.fields ?: defaultColumns()
        when (q.format) {
            MainInspectCli.InspectFormat.TEXT -> {
                out.println("inspect run=$runId")
                out.println("  ${cols.joinToString(separator = " ") { it.cli }}")
                records.forEach { rec ->
                    out.println("  " + cols.joinToString(separator = " ") { it.extract(rec) })
                }
            }
            MainInspectCli.InspectFormat.JSON -> {
                out.println("{")
                out.println("  \"runId\": \"$runId\",")
                out.println("  \"records\": [")
                records.forEachIndexed { i, rec ->
                    val fields = cols.joinToString(separator = ",") { f ->
                        "\"${f.cli.lowercase()}\": \"${escapeJson(f.extract(rec))}\""
                    }
                    val sep = if (i < records.size - 1) "," else ""
                    out.println("    {$fields}$sep")
                }
                out.println("  ]")
                out.println("}")
            }
        }
    }

    /** The columns a query that named none gets. */
    fun defaultColumns(): List<MainInspectCli.InspectField> = listOf(
        MainInspectCli.InspectField.Stage,
        MainInspectCli.InspectField.Step,
        MainInspectCli.InspectField.Outcome,
        MainInspectCli.InspectField.Message,
    )

    /** The one line the state surface prints. */
    fun renderRunState(observation: RuntimeObservation): String = when (observation) {
        is RuntimeObservation.Running -> "run-state running"

        is RuntimeObservation.Terminal ->
            "run-state terminal outcome=${renderTerminalOutcome(observation.terminal)} at=${observation.terminalAtMs}"

        is RuntimeObservation.LiveButEmpty -> "run-state empty"

        is RuntimeObservation.Unobservable -> "run-state unobservable"
    }

    /** The run's outcome word, from the book's own vocabulary. */
    fun renderTerminalOutcome(terminal: TerminalObservation): String = when (terminal) {
        is TerminalObservation.Succeeded -> "succeeded"
        is TerminalObservation.Failed -> "failed"
        is TerminalObservation.Unstable -> "unstable"
        is TerminalObservation.Aborted -> "aborted"
        is TerminalObservation.Cancelled -> "cancelled"
        is TerminalObservation.Other -> terminal.rawOutcome
    }

    /** The refusal, in the operator's words. */
    fun renderIntrospectionRefusal(refusal: IntrospectionRefusal, runId: String): String = when (refusal) {
        is IntrospectionRefusal.UnknownRun -> "this instance does not know run '$runId'."
        IntrospectionRefusal.NoControlRoot -> "there is no control root to read the run's state from."
        IntrospectionRefusal.NoEventStore -> "the event plane is not configured for this instance."
        IntrospectionRefusal.NoOutputPlane -> "the output plane is not configured for this instance."
        is IntrospectionRefusal.LeaseHeldByAnother ->
            "run $runId is being published by '${refusal.heldBy}' (fencing token ${refusal.fencingToken})."

        is IntrospectionRefusal.StorageError -> "the run's storage failed: ${refusal.cause}"
        is IntrospectionRefusal.InconsistentLeaseState ->
            "the run's lease state is inconsistent: ${refusal.details}"

        is IntrospectionRefusal.UnknownOperation ->
            "this instance does not know operation '${refusal.operationId}' (attempt ${refusal.attempt})."
    }

    /** The JSON string body of one field, escaped. */
    private fun escapeJson(s: String): String = escapeJsonText(s)
}
