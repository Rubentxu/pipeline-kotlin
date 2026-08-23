package com.pipeline.v2.application

import com.pipeline.v2.events.InMemoryEventStore
import com.pipeline.v2.events.JsonEventLog
import java.nio.file.Path
import java.nio.file.Paths

/**
 * CLI entry point for the V2 pipeline runner.
 *
 * Usage:
 *   pipeline validate <script>   — validate script, emit events to stdout
 *   pipeline run <script>        — run script, emit events to stdout
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: pipeline <validate|run> <script>")
        System.exit(1)
    }

    val command = args[0]
    val scriptPath = Paths.get(args[1])

    if (command != "validate" && command != "run") {
        System.err.println("Usage: pipeline <validate|run> <script>")
        System.exit(1)
    }

    val store = InMemoryEventStore()
    val events = execute(scriptPath, store)
    println(JsonEventLog.encode(events))
}
