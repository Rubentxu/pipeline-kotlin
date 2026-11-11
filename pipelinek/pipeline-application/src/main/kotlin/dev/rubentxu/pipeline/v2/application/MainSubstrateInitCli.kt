package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.DurableSubstratePreparation
import dev.rubentxu.pipeline.v2.events.durable.DurableSubstrateRefusal
import dev.rubentxu.pipeline.v2.events.durable.prepareDurableSubstrate
import java.io.PrintStream

/**
 * The usage the `substrate init` verb prints when its request is refused.
 */
internal const val SUBSTRATE_INIT_USAGE: String =
    "Usage: pipelinek substrate init --db <path>"

/**
 * The requests `substrate init` accepts.
 *
 * `--db` is REQUIRED and it names the FILE the substrate is. The verb does
 * not invent a store of its own: a substrate at a place the operator did not
 * name is a substrate nobody can compare against the command line, and a
 * service pointed at it would be answering about runs the operator never
 * started.
 */
internal val SUBSTRATE_INIT_REQUEST: CliRequest = CliRequest(
    options = listOf(CliOption("--db", takesValue = true)),
)

/**
 * `substrate init` — prepares the durable substrate the service serves.
 *
 * ## Why this verb exists
 *
 * Because the service SERVES a substrate and never creates one: it reads the
 * store it is given, and it refuses to start when the store is not there. That
 * is the right shape for a service — it must not invent the facts it answers
 * about — and it leaves the operator with a step to take before the first
 * start. This verb is that step, and it is a step of its OWN: a preparation
 * that the service does implicitly would be a service writing to the file it
 * was asked to read.
 *
 * ## What it prints
 *
 * The result, in words an operator can act on: where the substrate is, whether
 * this call created it or found it already there, and which tables it
 * carries. A preparation whose result cannot be read is a preparation whose
 * result nobody can check.
 *
 * ## Why it is idempotent
 *
 * Because the container starts the service again on every restart, and because
 * an operator who prepares a substrate twice must not be told that the second
 * time failed. The preparation changes nothing that is already there, and it
 * says so.
 */
internal fun runSubstrateInitVerb(args: Array<String>, out: PrintStream, err: PrintStream): CliExit {
    val admission = CliOptionAdmission.admit(args, SUBSTRATE_INIT_REQUEST)
    val accepted = when (admission) {
        is CliAdmission.Rejected -> {
            err.println(admission.error.refusalLines("substrate init").joinToString("\n"))
            err.println(SUBSTRATE_INIT_USAGE)
            return admission.error.toCliExit()
        }
        is CliAdmission.Admitted -> admission
    }

    val dbPath = accepted.values["--db"]
        ?: return refuseMissingArgument(err, "--db <path>", "to name the substrate to prepare")

    return when (val preparation = prepareDurableSubstrate(dbPath)) {
        is DurableSubstratePreparation.Ready -> {
            out.println("substrate ${preparation.path}: ready")
            out.println("substrate-state ${if (preparation.created) "created" else "already there"}")
            out.println("tables ${preparation.tables.joinToString(", ")}")
            CliExit.Success()
        }

        is DurableSubstratePreparation.Refused -> {
            val error = CliError.SubstrateNotPrepared(describeRefusal(preparation.refusal))
            err.println(error.refusalLines("substrate init").joinToString("\n"))
            err.println(SUBSTRATE_INIT_USAGE)
            error.toCliExit()
        }
    }
}

/**
 * The refusal of a request that did not name what it needs.
 */
private fun refuseMissingArgument(err: PrintStream, argument: String, why: String): CliExit {
    val error = CliError.MissingRequiredArgument(argument, why)
    err.println(error.refusalLines("substrate init").joinToString("\n"))
    err.println(SUBSTRATE_INIT_USAGE)
    return error.toCliExit()
}

/**
 * The words a refusal of the preparation carries.
 *
 * Each refusal names the thing the operator has to look at, and the DETAIL
 * travels with it so that nothing the engine said is lost.
 */
private fun describeRefusal(refusal: DurableSubstrateRefusal): String = when (refusal) {
    is DurableSubstrateRefusal.InvalidPath ->
        "the path cannot name a file: ${refusal.path}"

    is DurableSubstrateRefusal.NoDirectory ->
        "the directory of the substrate is not there: ${refusal.path}"

    is DurableSubstrateRefusal.NotADatabase ->
        "what is there is not a database: ${refusal.path} (${refusal.detail})"

    is DurableSubstrateRefusal.NotWritable ->
        "the substrate cannot be written: ${refusal.path} (${refusal.detail})"

    is DurableSubstrateRefusal.HeldByAnother ->
        "the substrate is held by another connection: ${refusal.path} (${refusal.detail})"
}
