package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventStoreAdmission
import dev.rubentxu.pipeline.v2.events.durable.EventStorePurpose
import dev.rubentxu.pipeline.v2.events.durable.admitEventStore
import dev.rubentxu.pipeline.v2.harness.codec.YamlEventContractCodec
import dev.rubentxu.pipeline.v2.harness.model.EventContract
import dev.rubentxu.pipeline.v2.harness.model.VerificationReport
import dev.rubentxu.pipeline.v2.harness.model.VerificationResult
import dev.rubentxu.pipeline.v2.harness.verify.EventHarness
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal post-run acceptance surface
 *   pipeline events verify --db <path> --run <runId> --contract <events.yaml>
 *                          [--from-sequence N] [--scope last-segment|whole]
 * Verifies persisted history through the EventHarness (typed contract) WITHOUT
 * re-executing the pipeline. Exit 0 = PASSED, 1 = FAILED, 2 = usage/decode error.
 * Prints a VerificationReport summary to stdout; violations detail on FAILED.
 *
 * ## Why the request is read by the shared admission
 *
 * This verb used to read its options with a hand-written loop whose `when` had
 * no branch for anything it did not recognise, and every option it did not
 * recognise was dropped without a word. `events verify --db <db> --run <r>
 * --contract <c> --bogus` printed EXACTLY what the same invocation without
 * `--bogus` printed. The verb that decides whether a run satisfied its
 * contract was answering about a request nobody had made.
 *
 * It also read two values it could not read, and read them as something else:
 * `--from-sequence abc` became the sequence 0, and `--scope anything` became
 * the scope WHOLE. A value the verb cannot read is a value it has to refuse,
 * because the answer it gives instead is about a different request.
 *
 * The refusals are issued before the store is touched: a request that cannot
 * be read never reaches the filesystem.
 *
 * ## Why the argument this verb does not own is refused by the admission
 *
 * This verb used to check the positional arguments itself, after the
 * admission had already answered. It does not any more: the admission owns the
 * WHOLE shape of the request — the options and the positional arguments — and
 * this verb owns none of the second kind, so it says so by not claiming one.
 * One place decides whether a request is one of this verb's requests, and the
 * refusal it renders is the same one every verb of this family renders.
 *
 * ## The shape of the verb
 *
 * The verb is four steps and each one is its own function: read the request by
 * the shared admission, refuse what cannot be read, verify the run against the
 * contract, render the verdict. `main` is the reading; `verify` is the only
 * function that opens anything. That split is what keeps the refusals
 * observable from the outside — every refusal happens in `main`, before
 * `verify` is reached, and a reader of `main` sees all of them in one place.
 */
object MainEventsVerifyCli {

    private const val USAGE: String =
        "Usage: pipeline events verify --db <path> --run <runId> --contract <events.yaml> " +
            "[--from-sequence N] [--scope last-segment|whole]\n"

    /** The scope that reads the whole history, and the one that reads the last segment only. */
    private const val SCOPE_LAST_SEGMENT: String = "last-segment"

    private const val SCOPE_WHOLE: String = "whole"

    fun main(args: Array<String>): Int {
        val admission = CliOptionAdmission.admit(args, EVENTS_VERIFY_REQUEST)
        val accepted = when (admission) {
            is CliAdmission.Rejected -> {
                refuse(admission.error.refusalLines("events verify").joinToString("\n"))
                return 2
            }
            is CliAdmission.Admitted -> admission
        }

        val db = accepted.values["--db"]
        val runId = accepted.values["--run"]
        val contractPath = accepted.values["--contract"]
        if (db.isNullOrBlank()) {
            refuse("events verify: --db is required: the event store lives under it.")
            return 2
        }
        if (runId.isNullOrBlank()) {
            refuse("events verify: --run is required: it names the run to verify.")
            return 2
        }
        if (contractPath.isNullOrBlank()) {
            refuse("events verify: --contract is required: it names the contract to verify against.")
            return 2
        }

        val fromSequence = readFromSequence(accepted.values["--from-sequence"])
        if (fromSequence == null) {
            refuse("events verify: --from-sequence must be a whole number.")
            return 2
        }
        val scopeAfterLastStart = readScope(accepted.values["--scope"])
        if (scopeAfterLastStart == null) {
            refuse("events verify: --scope must be $SCOPE_LAST_SEGMENT or $SCOPE_WHOLE.")
            return 2
        }

        return verify(db, runId, contractPath, fromSequence, scopeAfterLastStart)
    }

    /**
     * A refusal, and the usage that says what this verb accepts.
     *
     * The usage is printed after every refusal because the refusal itself
     * says what was wrong, and the usage says what would have been right.
     */
    private fun refuse(message: String) {
        System.err.println(message)
        System.err.println(USAGE)
    }

    /**
     * The sequence the history starts after.
     *
     * `null` when the option was given a value that is not a whole number: the
     * verb used to read that value as the sequence 0, which is the beginning
     * of the history — an answer about a request nobody made.
     */
    private fun readFromSequence(raw: String?): Long? = when {
        raw == null -> 0L
        else -> raw.toLongOrNull()
    }

    /**
     * Whether the verification reads only the last segment.
     *
     * `null` when the option was given a scope this verb does not know: the
     * verb used to read any scope as the whole history, which is an answer
     * about a different request.
     */
    private fun readScope(raw: String?): Boolean? = when (raw?.lowercase()) {
        null, SCOPE_WHOLE -> false
        SCOPE_LAST_SEGMENT -> true
        else -> null
    }

    /**
     * The verification itself: the only part of this verb that opens anything.
     *
     * It is reached only by a request the verb could read in full.
     */
    private fun verify(
        db: String,
        runId: String,
        contractPath: String,
        fromSequence: Long,
        scopeAfterLastStart: Boolean,
    ): Int {
        // The second of the two copies of this rule. Both now consult `admitEventStore`.
        val dbAdmission = admitEventStore(db, EventStorePurpose.Read)
        if (dbAdmission is EventStoreAdmission.Refused) {
            System.err.println(dbAdmission.refusal.render())
            return 2
        }
        val contractFile = Path.of(contractPath)
        if (!Files.exists(contractFile)) {
            System.err.println("Error: contract not found: $contractPath")
            return 2
        }

        val contract = try {
            YamlEventContractCodec.decode(Files.readString(contractFile))
        } catch (e: Exception) {
            System.err.println("Error: cannot decode contract: ${e.message}")
            return 2
        }

        val store = (dbAdmission as EventStoreAdmission.Admitted).store
        try {
            val history = EventHarness.typedHistory(store, runId, fromSequence)
            val scope = if (scopeAfterLastStart) {
                EventHarness.Scope.AFTER_LAST_RUN_STARTED
            } else {
                EventHarness.Scope.WHOLE
            }
            val report = EventHarness.report(history, contract, scope)
            val outcome = outcomeOf(report, contract)
            println("contract: ${report.contract}")
            println("observed-terminal-outcome: ${report.observedTerminalOutcome ?: "none"}")
            println("acceptance: $outcome")
            printViolations(report.result)
            return if (outcome == "PASSED") 0 else 1
        } finally {
            store.close()
        }
    }

    /**
     * The verdict about the run, in the two words this verb prints.
     *
     * A valid result whose terminal outcome is not the one the contract
     * expects is a FAILURE: the contract was not met, whatever the history
     * says about itself.
     */
    private fun outcomeOf(report: VerificationReport, contract: EventContract): String =
        when (report.result) {
            is VerificationResult.Valid ->
                if (report.observedTerminalOutcome?.name == contract.expect.name) "PASSED" else "FAILED"
            is VerificationResult.Invalid -> "FAILED"
        }

    /** The counterexamples of a failed verification, one block per violation. */
    private fun printViolations(result: VerificationResult) {
        when (result) {
            is VerificationResult.Invalid -> result.violations.forEach { violation ->
                println("violation: [${violation.rule}] ${violation.message}")
                violation.missing?.let { println("  missing: $it") }
                violation.unexpected?.let { println("  unexpected: $it") }
                violation.relevantTrace.forEach { trace ->
                    println("  #${trace.sequence} ${trace.kind} ${trace.summary}")
                }
            }
            is VerificationResult.Valid -> Unit
        }
    }
}
