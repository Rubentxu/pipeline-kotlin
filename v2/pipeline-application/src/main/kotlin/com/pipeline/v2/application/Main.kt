package com.pipeline.v2.application

import com.pipeline.v2.application.durable.PipelineOrchestrator
import com.pipeline.v2.dsl.PipelineSpec
import com.pipeline.v2.events.InMemoryEventStore
import com.pipeline.v2.events.JsonEventLog
import com.pipeline.v2.events.SqliteEventStore
import com.pipeline.v2.domain.durable.DivergenceDetector
import com.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import com.pipeline.v2.domain.durable.Clock
import com.pipeline.v2.events.durable.OperationJournal
import com.pipeline.v2.events.durable.SqliteOperationJournalImpl
import com.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import com.pipeline.v2.events.durable.ReplayCursorStore
import com.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import com.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import com.pipeline.v2.scripting.Kotlin24ScriptingHost
import com.pipeline.v2.scripting.ScriptDefinition
import java.nio.file.Paths
import java.security.MessageDigest

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
 * When `--db <path>` is provided, the runner uses [PipelineOrchestrator] with
 * [SqliteEventStore] which journals operations, computes fingerprints, gates
 * step replay, and detects divergence fail-closed.
 */
/**
 * Parsed CLI arguments for the pipeline runner.
 */
data class PipelineCliConfig(
    val command: String,
    val dbPath: String?,
    val resumeFlag: Boolean,
    val scriptPath: String?,
)

/**
 * Parses CLI arguments for the pipeline runner.
 *
 * @param args The command-line arguments.
 * @return The parsed configuration, or null if parsing failed.
 */
fun parseCliArgs(args: Array<String>): PipelineCliConfig? {
    if (args.size < 2) {
        return null
    }

    val command = args[0]

    if (command != "validate" && command != "run") {
        return null
    }

    // Parse --db and --resume flags.
    var dbPath: String? = null
    var resumeFlag = false
    var scriptArgIndex = 1
    var i = 1
    while (i < args.size && args[i].startsWith("--")) {
        when (args[i]) {
            "--db" -> {
                if (i + 1 >= args.size) {
                    return null
                }
                dbPath = args[i + 1]
                i += 2
            }
            "--resume" -> {
                resumeFlag = true
                i++
            }
            else -> break
        }
    }
    scriptArgIndex = i

    if (args.size < scriptArgIndex + 1) {
        return null
    }

    val scriptPath = args[scriptArgIndex]

    return PipelineCliConfig(
        command = command,
        dbPath = dbPath,
        resumeFlag = resumeFlag,
        scriptPath = scriptPath,
    )
}

fun main(args: Array<String>) {
    val config = parseCliArgs(args) ?: run {
        System.err.println("Usage: pipeline <validate|run> [--db <path>] [--resume] <script>")
        System.exit(1)
        return
    }

    val command = config.command

    val scriptPath = Paths.get(config.scriptPath!!)

    if (command == "validate") {
        val store = InMemoryEventStore()
        val events = execute(scriptPath, store)
        println(JsonEventLog.encode(events))
        return
    }

    // "run" command.
    if (config.dbPath == null) {
        // Default: in-memory store for backwards compatibility (M2-R2 behavior).
        val store = InMemoryEventStore()
        val events = execute(scriptPath, store)
        println(JsonEventLog.encode(events))
        return
    }

    // Durable mode: SqliteEventStore + PipelineOrchestrator for replay/divergence gating.
    val eventStore = SqliteEventStore(config.dbPath)

    // Compile script → PipelineSpec (same approach as execute())
    val scriptContent = scriptPath.toFile().readText()
    val runId = deriveRunId(scriptPath.toString(), scriptContent)
    val host = Kotlin24ScriptingHost(eventStore, runId)
    val dslJar = ScriptDefinition.dslApiJar()
    val dslClasspath = if (dslJar != null) listOf(dslJar) else emptyList()
    val definition = ScriptDefinition.file(scriptPath, classpath = dslClasspath)
    val result = host.compile(definition)

    val pipelineSpec: PipelineSpec? = if (result.isSuccess) {
        val scriptInstance = result.value
        scriptInstance?.let { inst ->
            try {
                val resultMethod = inst.javaClass.getMethod("get\$\$result")
                @Suppress("UNCHECKED_CAST")
                resultMethod.invoke(inst) as? PipelineSpec
            } catch (_: Exception) {
                null
            }
        }
    } else null

    // Build orchestrator with all durable dependencies
    val factory = eventStore.underlyingConnectionFactory()
    val clock: Clock = SystemClock()
    val journal: OperationJournal = SqliteOperationJournalImpl(factory, clock)
    val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(factory, clock)
    val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector()
    val effectPolicy: EffectReplayPolicy = DefaultEffectReplayPolicy()
    val orchestrator = PipelineOrchestrator(
        journal = journal,
        cursorStore = cursorStore,
        divergenceDetector = divergenceDetector,
        effectReplayPolicy = effectPolicy,
        eventSink = eventStore,
        clock = clock,
    )

    // Run via orchestrator (fresh run or resume based on --resume flag)
    if (pipelineSpec != null) {
        orchestrator.run(pipelineSpec, runId, startFromCursor = config.resumeFlag)
    }

    val events = eventStore.eventsFor(runId).toList()
    println(JsonEventLog.encode(events))
}

/**
 * Derives a deterministic runId from the script path and content.
 * Two invocations of the same script produce the same runId.
 */
private fun deriveRunId(scriptPath: String, scriptContent: String): String {
    val input = "$scriptPath|$scriptContent"
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }.take(36)
}
