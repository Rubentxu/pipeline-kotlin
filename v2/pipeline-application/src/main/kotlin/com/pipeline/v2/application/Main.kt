package com.pipeline.v2.application

import com.pipeline.v2.events.InMemoryEventStore
import com.pipeline.v2.events.JsonEventLog
import com.pipeline.v2.events.SqliteEventStore
import java.nio.file.Paths

/**
 * CLI entry point for the V2 pipeline runner.
 *
 * Usage:
 *   pipeline validate <script>                            — validate script, emit events to stdout
 *   pipeline run [--db <path>] <script>                 — run script with durable journal
 *   pipeline run --db <path> <script>                   — run with explicit SQLite db path
 *
 * ## M3-R1 durable execution
 *
 * When `--db <path>` is provided, the runner uses [SqliteEventStore] which:
 * - Operates in WAL journal mode (durability + concurrent readers)
 * - Creates `operation_journal` and `replay_cursor` tables alongside `events`
 * - The full durable orchestration (fingerprint, replay, divergence detection)
 *   is wired into [PipelineRun] in M3-R2.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: pipeline <validate|run> [--db <path>] <script>")
        System.exit(1)
    }

    val command = args[0]

    if (command != "validate" && command != "run") {
        System.err.println("Usage: pipeline <validate|run> [--db <path>] <script>")
        System.exit(1)
    }

    // Parse --db flag.
    var dbPath: String? = null
    var scriptArgIndex = 1
    if (args.size >= 3 && args[1] == "--db") {
        dbPath = args[2]
        scriptArgIndex = 3
    }

    if (args.size < scriptArgIndex + 1) {
        System.err.println("Usage: pipeline <validate|run> [--db <path>] <script>")
        System.exit(1)
    }

    val scriptPath = Paths.get(args[scriptArgIndex])

    if (command == "validate") {
        val store = InMemoryEventStore()
        val events = execute(scriptPath, store)
        println(JsonEventLog.encode(events))
        return
    }

    // "run" command.
    if (dbPath == null) {
        // Default: in-memory store for backwards compatibility (M2-R2 behavior).
        val store = InMemoryEventStore()
        val events = execute(scriptPath, store)
        println(JsonEventLog.encode(events))
        return
    }

    // Durable mode: SqliteEventStore with WAL, also creates operation_journal
    // and replay_cursor tables. Full durable orchestration is wired in M3-R2.
    val eventStore = SqliteEventStore(dbPath)
    val events = execute(scriptPath, eventStore)
    println(JsonEventLog.encode(events))
}
