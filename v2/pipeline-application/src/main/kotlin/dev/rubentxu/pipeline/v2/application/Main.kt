package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.PipelineOrchestrator
import dev.rubentxu.pipeline.v2.credentials.api.RedactingEventSink
import dev.rubentxu.pipeline.v2.credentials.api.SecretPatternRegistry
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.credentials.local.MainCredentialsCli
import dev.rubentxu.pipeline.v2.credentials.local.PassphraseResolver
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.events.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.domain.durable.DivergenceDetector
import kotlinx.serialization.json.Json
import dev.rubentxu.pipeline.v2.domain.durable.StrictFingerprintDivergenceDetector
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.events.durable.OperationJournal
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import dev.rubentxu.pipeline.v2.events.durable.SqliteReplayCursorStoreImpl
import dev.rubentxu.pipeline.v2.events.durable.ReplayCursorStore
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.EffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfileUnsupportedException
import dev.rubentxu.pipeline.v2.scripting.Kotlin24ScriptingHost
import dev.rubentxu.pipeline.v2.scripting.ScriptDefinition
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

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
    val controlRoot: String? = null,
    val sandboxProfile: SandboxProfile = SandboxProfile.NONE,
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

    // Parse --db, --resume, --control-root, and --sandbox-profile flags.
    var dbPath: String? = null
    var resumeFlag = false
    var controlRoot: String? = null
    var sandboxProfile: SandboxProfile = SandboxProfile.NONE
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
            "--control-root" -> {
                if (i + 1 >= args.size) {
                    return null
                }
                controlRoot = args[i + 1]
                i += 2
            }
            "--sandbox-profile" -> {
                if (i + 1 >= args.size) {
                    return null
                }
                val profileValue = args[i + 1]
                sandboxProfile = when (profileValue) {
                    "none" -> SandboxProfile.NONE
                    "local" -> SandboxProfile.LOCAL
                    "os" -> throw SandboxProfileUnsupportedException(
                        "sandbox-profile 'os' requires ADR-0016 M5/M9; rejected in L3. Accepted: {none, local}. Got: 'os'."
                    )
                    else -> throw SandboxProfileUnsupportedException(
                        "sandbox-profile '$profileValue' invalid. Accepted: {none, local}."
                    )
                }
                i += 2
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
        controlRoot = controlRoot,
        sandboxProfile = sandboxProfile,
    )
}

fun main(args: Array<String>) {
    // Credentials subcommand — delegated to MainCredentialsCli
    if (args.firstOrNull() == "credentials") {
        val exitCode = MainCredentialsCli.main(args.drop(1).toTypedArray())
        System.exit(exitCode)
        return
    }

    val config = parseCliArgs(args) ?: run {
        System.err.println("Usage: pipeline <validate|run> [--db <path>] [--resume] [--control-root <path>] <script>")
        System.exit(1)
        return
    }

    val command = config.command

    // Shared secret pattern registry for redaction (T6)
    // Both InMemoryEventStore and SqliteEventStore are wrapped at construction time
    // so all downstream consumers receive already-sanitized events.
    val secretPatternRegistry = SecretPatternRegistry()

    // CR-RD-008 canary: synthetic secret registered at engine startup for round-gate verification.
    // The canary value GHS6_CANARY_7f3a9c2e1b4d5e6f is never used in any real credential.
    secretPatternRegistry.addSecret(SecretHandle.plain("GHS6_CANARY_7f3a9c2e1b4d5e6f"))

    // CR-RD-021 ssh canary: synthetic secret for SSH channel round-gate verification.
    // The canary value __ssh_canary__ is never used in any real SSH credential.
    secretPatternRegistry.addSecret(SecretHandle.plain("__ssh_canary__"))

    // ARC-CANARY-001 / CR-RD-022 artefact canary: synthetic secret for artefact step round-gate.
    // The canary value __artefact_canary__ is never used in any real artefact.
    secretPatternRegistry.addSecret(SecretHandle.plain("__artefact_canary__"))

    val scriptPath = Paths.get(config.scriptPath!!)

    if (command == "validate") {
        val rawStore = InMemoryEventStore()
        val store = RedactingEventSink(rawStore, secretPatternRegistry)
        val events = execute(scriptPath, store)
        println(JsonEventLog.encode(events))
        return
    }

    // "run" command.
    if (config.dbPath == null) {
        // Default: in-memory store for backwards compatibility (M2-R2 behavior).
        val rawStore = InMemoryEventStore()
        val store = RedactingEventSink(rawStore, secretPatternRegistry)
        val events = execute(scriptPath, store)
        println(JsonEventLog.encode(events))
        return
    }

    // Durable mode: SqliteEventStore + PipelineOrchestrator for replay/divergence gating.
    // Both stores are wrapped with RedactingEventSink at construction time (design §Data Flow).
    val rawEventStore = SqliteEventStore(config.dbPath)
    // Call raw methods BEFORE wrapping — RedactingEventSink delegates these to the inner store
    val factory = rawEventStore.underlyingConnectionFactory()
    val dbPathStr = rawEventStore.databasePath()
    val eventStore = RedactingEventSink(rawEventStore, secretPatternRegistry)

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
    val clock: Clock = SystemClock()
    val journal: OperationJournal = SqliteOperationJournalImpl(factory, clock, Json { ignoreUnknownKeys = true; encodeDefaults = true }, dbPathStr)
    val cursorStore: ReplayCursorStore = SqliteReplayCursorStoreImpl(factory, clock)
    val divergenceDetector: DivergenceDetector = StrictFingerprintDivergenceDetector()
    val effectPolicy: EffectReplayPolicy = DefaultEffectReplayPolicy()

    // ML-R1: controlDirRoot is the parent directory of the SQLite db file (default).
    // Can be overridden via --control-root flag for testing.
    // Each step gets a subdirectory: $controlDirRoot/$runId-$stageIndex-$stepIndex/
    val dbPath = Paths.get(config.dbPath!!)
    val controlDirRoot: Path = if (config.controlRoot != null) {
        Paths.get(config.controlRoot)
    } else {
        dbPath.parent.resolve("durable-shell")
    }

    // T12: Resolve SecretStore for credential injection in withCredentials blocks.
    //
    // Design: Option A — env var PIPELINE_CREDENTIALS_STORE for store path,
    // PIPELINE_STORE_PASSPHRASE for passphrase.  Default path is
    // <controlDirRoot>/../credentials.bin (sibling to the journal db).
    //
    // Behavior:
    // - Store file does not exist → pass null (user runs `pipeline credentials add`
    //   first to create it; no error at startup)
    // - Store file exists + passphrase available → inject credentials
    // - Store file exists + passphrase wrong/missing → fail fast with actionable error
    val credentialsStorePath: Path = System.getenv("PIPELINE_CREDENTIALS_STORE")?.let { Paths.get(it) }
        ?: controlDirRoot.parent.resolve("credentials.bin")

    val secretStore: dev.rubentxu.pipeline.v2.credentials.api.SecretStore? =
        if (!credentialsStorePath.toFile().exists()) {
            // File doesn't exist yet — user must create it via `pipeline credentials add`
            null
        } else {
            // File exists — resolve passphrase and open the store
            try {
                val passphraseChars = PassphraseResolver.resolve()
                val store = LocalSecretStore(credentialsStorePath, passphraseChars)
                // Immediately wipe the passphrase from memory after use
                passphraseChars.fill('\u0000')
                store
            } catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
                System.err.println("Error: ${e.message}")
                System.err.println("Hint: set PIPELINE_STORE_PASSPHRASE env var, or run interactively in a TTY.")
                System.exit(3)
                null // unreachable
            } catch (e: LocalSecretStore.SecretStorePassphraseMismatchException) {
                System.err.println("Error: $e.message")
                System.err.println("Hint: the passphrase does not match. Check PIPELINE_STORE_PASSPHRASE.")
                System.exit(3)
                null // unreachable
            } catch (e: LocalSecretStore.SecretStoreTamperException) {
                System.err.println("Error: credentials store tampered: ${e.message}")
                System.exit(4)
                null // unreachable
            }
        }

    val orchestrator = PipelineOrchestrator(
        journal = journal,
        cursorStore = cursorStore,
        divergenceDetector = divergenceDetector,
        effectReplayPolicy = effectPolicy,
        eventSink = eventStore,
        clock = clock,
        controlDirRoot = controlDirRoot,
        sandboxProfile = config.sandboxProfile,
        redactingEventSink = eventStore,
        secretStore = secretStore,
    )

    // Run via orchestrator (fresh run or resume based on --resume flag)
    if (pipelineSpec != null) {
        runBlocking {
            orchestrator.run(pipelineSpec, runId, startFromCursor = config.resumeFlag)
        }
    }

    val events = eventStore.eventsFor(runId).toList()
    val lastEvent = events.lastOrNull()
    val runOutcome = if (lastEvent is RunFinished) lastEvent.outcome else "success"
    // Jenkins verbatim: print events first, then propagate failure to OS exit code
    println(JsonEventLog.encode(events))
    // D5: 3-state outcome widening — unstable exits 0 like success, failure exits 1
    when (runOutcome) {
        "success" -> {
            System.err.println("Pipeline finished with SUCCESS")
        }
        "unstable" -> {
            System.err.println("Pipeline finished with UNSTABLE")
        }
        else -> {
            System.err.println("Pipeline finished with FAILURE")
            System.exit(1)
        }
    }
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
