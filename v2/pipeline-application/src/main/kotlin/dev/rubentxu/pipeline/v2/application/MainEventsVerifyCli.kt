package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.harness.codec.YamlEventContractCodec
import dev.rubentxu.pipeline.v2.harness.verify.EventHarness

/**
 * EVT-3 minimal post-run acceptance surface:
 *
 *   pipeline events verify --db <path> --run <runId> --contract <events.yaml> [--from-cursor TOKEN]
 *
 * Verifies persisted history through the EventHarness (typed contract) WITHOUT
 * re-executing the pipeline. Exit 0 = PASSED, 1 = FAILED, 2 = usage/decode error.
 * Prints a VerificationReport summary to stdout; violations detail on FAILED.
 */
object MainEventsVerifyCli {

    fun main(args: Array<String>): Int {
        var db: String? = null
        var runId: String? = null
        var contractPath: String? = null
        var fromCursor: Long = 0L
        var scopeAfterLastStart = false

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--db" -> db = args.getOrNull(++i)
                "--run" -> runId = args.getOrNull(++i)
                "--contract" -> contractPath = args.getOrNull(++i)
                "--from-sequence" -> fromCursor = args.getOrNull(++i)?.toLongOrNull() ?: 0L
                "--scope" -> scopeAfterLastStart = args.getOrNull(++i) == "last-segment"
            }
            i++
        }
        if (db == null || runId == null || contractPath == null) {
            System.err.println("Usage: pipeline events verify --db <path> --run <runId> --contract <events.yaml> [--from-sequence N] [--after-last-run-started]")
            return 2
        }
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(db))) {
            System.err.println("Error: db not found: $db")
            return 2
        }
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(contractPath))) {
            System.err.println("Error: contract not found: $contractPath")
            return 2
        }

        val contract = try {
            YamlEventContractCodec.decode(java.nio.file.Files.readString(java.nio.file.Path.of(contractPath)))
        } catch (e: Exception) {
            System.err.println("Error: cannot decode contract: ${e.message}")
            return 2
        }

        val store = SqliteEventStore(db)
        try {
            val history = EventHarness.typedHistory(store, runId, fromCursor)
            val scope = if (scopeAfterLastStart) EventHarness.Scope.AFTER_LAST_RUN_STARTED else EventHarness.Scope.WHOLE
            val report = EventHarness.report(history, contract, scope)
            val outcome = when (report.result) {
                is dev.rubentxu.pipeline.v2.harness.model.VerificationResult.Valid ->
                    if (report.observedTerminalOutcome?.name == contract.expect.name) "PASSED" else "FAILED"
                is dev.rubentxu.pipeline.v2.harness.model.VerificationResult.Invalid -> "FAILED"
            }
            println("contract: ${report.contract}")
            println("observed-terminal-outcome: ${report.observedTerminalOutcome ?: "none"}")
            println("acceptance: $outcome")
            val result = report.result
            if (result is dev.rubentxu.pipeline.v2.harness.model.VerificationResult.Invalid) {
                result.violations.forEach { v ->
                    println("violation: [${v.rule}] ${v.message}")
                    v.missing?.let { println("  missing: $it") }
                    v.unexpected?.let { println("  unexpected: $it") }
                    v.relevantTrace.forEach { t -> println("  #${t.sequence} ${t.kind} ${t.summary}") }
                }
            }
            return if (outcome == "PASSED") 0 else 1
        } finally {
            store.close()
        }
    }
}
