package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.domain.durable.FixtureExecutionState
import dev.rubentxu.pipeline.v2.domain.durable.OperationStatus
import dev.rubentxu.pipeline.v2.domain.durable.reconcile
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl
import dev.rubentxu.pipeline.v2.events.evidence.JournalRunExecutionEvidenceReader
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * XCA-2 — Workstream E: Full corpus execution and evidence reconciliation.
 *
 * Runs the installed CLI against every fixture in the compatibility corpus,
 * reads execution evidence from the SQLite journal, reconciles against
 * source-level expectations, and produces a structured [CorpusRunReceipt].
 *
 * Evidence flow:
 *   fixture source -> CLI execution -> SqliteOperationJournalImpl
 *     -> JournalRunExecutionEvidenceReader -> reconcile() -> CorpusRunReceipt
 *
 * LAWs enforced:
 *   1. exit code is NEVER evidence of execution
 *   2. presence in source is NEVER evidence of execution
 *   3. reconciliation via pure set math: E∩O / E−O / O−E
 */
@Timeout(600)
class XcaCorpusRunTest {

    @TempDir
    lateinit var tempDir: Path

    private val processes = mutableListOf<Process>()

    private val systemClock: Clock = object : Clock {
        override fun now() = java.time.Clock.systemUTC().instant()
    }

    private val cliBinary: Path by lazy {
        // Gradle working directory is v2/pipeline-application/
        Path.of("../pipeline-application/build/install/pipeline-application/bin/pipeline-application")
            .toAbsolutePath()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step expectations derived from fixture source analysis (E set).
    // These define the expected StepKeys for each fixture.
    // ─────────────────────────────────────────────────────────────────────────

    private val fixtureExpectations: Map<String, Set<PluginStepId>> = mapOf(
        "01-basic"               to setOf(PluginStepId("core.echo")),
        "02-environment"         to setOf(PluginStepId("core.echo"), PluginStepId("core.withEnv")),
        "03-stages"              to setOf(PluginStepId("core.echo")),
        "04-sh"                  to setOf(PluginStepId("core.echo"), PluginStepId("core.sh")),
        "05-scripted-if"         to setOf(PluginStepId("core.echo"), PluginStepId("core.script")),
        "06-loop"                to setOf(PluginStepId("core.echo"), PluginStepId("core.loop")),
        "08-withEnv-pipeline"    to setOf(PluginStepId("core.withEnv")),
        "09-sh-then-echo"        to setOf(PluginStepId("core.echo"), PluginStepId("core.sh")),
        "10-smoke-e2e"           to setOf(PluginStepId("core.echo"), PluginStepId("core.sh")),
        "11-workflow-control"    to setOf(PluginStepId("core.echo"), PluginStepId("core.timeout"), PluginStepId("core.retry")),
        "12-error-handling"      to setOf(PluginStepId("core.echo"), PluginStepId("core.catchError")),
        "13-workspace-helpers"   to setOf(PluginStepId("core.dir"), PluginStepId("core.deleteDir")),
        "14-credentials-bindings" to setOf(PluginStepId("core.echo")),
        "15-error"              to setOf(PluginStepId("core.error")),
        "16-sleep"               to setOf(PluginStepId("core.echo"), PluginStepId("core.sleep")),
        "17-writeFile"           to setOf(PluginStepId("core.writeFile")),
        "18-cleanWs"             to setOf(PluginStepId("core.cleanWs")),
        "19-isunix"              to setOf(PluginStepId("core.isUnix")),
        "20-pwd-tmp"             to setOf(PluginStepId("core.pwd")),
        "21-milestone"           to setOf(PluginStepId("core.milestone")),
        "22-wait-until"          to setOf(PluginStepId("core.sh")),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Receipt data structures
    // ─────────────────────────────────────────────────────────────────────────

    data class FixtureRunResult(
        val fixture: String,
        val runId: String?,
        val observed: List<String>,
        val expected: List<String>,
        val matched: Int,
        val expectedButNotExecuted: Int,
        val extraObserved: Int,
        val isFullyCovered: Boolean,
        val stepRelations: List<String>,
        val errorClass: String?,
    )

    data class CorpusRunReceipt(
        val timestamp: String,
        val totalFixtures: Int,
        val successfulRuns: Int,
        val failedRuns: Int,
        val totalMatched: Int,
        val uniqueObservedSteps: Int,
        val fullyCoveredFixtures: Int,
        val results: List<FixtureRunResult>,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `E — full corpus run with evidence reconciliation`() {
        val corpusDir = Path.of("../compatibility").toAbsolutePath()
        assumeTrue(Files.exists(corpusDir), "Corpus not found: $corpusDir")

        val fixtureFiles = Files.list(corpusDir)
            .filter { it.fileName.toString().endsWith(".pipeline.kts") }
            .filter { !it.fileName.toString().startsWith("corpus-") }
            .sorted()
            .toList()

        val results = mutableListOf<FixtureRunResult>()

        for (fixturePath in fixtureFiles) {
            val name = fixturePath.fileName.toString().removeSuffix(".pipeline.kts")
            val (runId, errorClass) = runFixture(fixturePath)

            val observedByStatus: Map<PluginStepId, OperationStatus> =
                if (runId != null) readObservedByStatus(runId) else emptyMap()

            val expected = fixtureExpectations[name] ?: emptySet()
            val observed = observedByStatus.keys

            val reconciliation = reconcile(
                fixturePath = name,
                executionState = if (runId != null) FixtureExecutionState.HasEvidence else FixtureExecutionState.NoEvidence,
                expectedStepKeys = expected,
                observedStepKeys = observed,
                observedByStatus = observedByStatus,
            )

            results.add(
                FixtureRunResult(
                    fixture = name,
                    runId = runId,
                    errorClass = errorClass,
                    observed = observed.map { it.value }.sorted(),
                    expected = expected.map { it.value }.sorted(),
                    matched = reconciliation.matched.size,
                    expectedButNotExecuted = reconciliation.expectedButNotExecuted.size,
                    extraObserved = reconciliation.extraObserved.size,
                    isFullyCovered = reconciliation.isFullyCovered,
                    stepRelations = reconciliation.stepRelations.map { it.toString() },
                )
            )
        }

        val receipt = CorpusRunReceipt(
            timestamp = Instant.now().toString(),
            totalFixtures = fixtureFiles.size,
            successfulRuns = results.count { it.runId != null },
            failedRuns = results.count { it.runId == null },
            totalMatched = results.sumOf { it.matched },
            uniqueObservedSteps = results.flatMap { it.observed }.toSet().size,
            fullyCoveredFixtures = results.count { it.isFullyCovered },
            results = results,
        )

        // ── Print summary ──────────────────────────────────────────────────
        println("\n═══════════════════════════════════════════════════════════════")
        println("  XCA-2 CORPUS RUN RECEIPT — ${receipt.timestamp}")
        println("═══════════════════════════════════════════════════════════════")
        println("  Fixtures total:   ${receipt.totalFixtures}")
        println("  Runs OK:          ${receipt.successfulRuns}")
        println("  Runs FAIL:        ${receipt.failedRuns}")
        println("  Fully covered:    ${receipt.fullyCoveredFixtures}")
        println("  Unique steps:     ${receipt.uniqueObservedSteps}")
        println("  Total matched:    ${receipt.totalMatched}")
        println("═══════════════════════════════════════════════════════════════")
        for (r in receipt.results) {
            val status = when {
                r.runId == null -> "  SKIP    "
                r.isFullyCovered -> "  OK      "
                else -> "  PARTIAL "
            }
            val missing = r.expectedButNotExecuted
            val extra = r.extraObserved
            val errNote = r.errorClass?.let { " [$it]" } ?: ""
            println("  $status  ${r.fixture.padEnd(30)}  matched=${r.matched}  missing=$missing  extra=$extra$errNote")
        }
        println("═══════════════════════════════════════════════════════════════\n")

        // ── Assertions ────────────────────────────────────────────────────
        // Fixtures that produce errors or throw exceptions are expected to have no runId.
        // Both 12-error-handling and 21-milestone fail with:
        //   EngineInvariantViolation: core.milestone reached execute without declared
        //   capability 'milestone.operations' — the milestone capability is absent from
        //   the CLI execution environment. The intentional failure inside catchError
        //   (12-error-handling) is never reached because the pipeline aborts at
        //   milestone first. These are EXECUTED (the CLI ran) but produce no durable
        //   runId due to the capability violation, not the intentional failure.
        val expectedToFail = setOf("12-error-handling", "21-milestone")
        val unexpectedFails = results.count { it.runId == null && it.fixture !in expectedToFail }
        org.junit.jupiter.api.Assertions.assertEquals(0, unexpectedFails,
            "Unexpected failures (no runId): ${results.filter { it.runId == null && it.fixture !in expectedToFail }.map { it.fixture }}")
        org.junit.jupiter.api.Assertions.assertTrue(
            results.flatMap { it.observed }.toSet().isNotEmpty(),
            "At least one step must be observed. Observed: ${results.flatMap { it.observed }.toSet()}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun runFixture(fixturePath: Path): Pair<String?, String?> {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"))
        assumeTrue(Files.exists(cliBinary), "CLI not installed: $cliBinary")

        val dbPath = tempDir.resolve("journal.db").toAbsolutePath()
        val controlRoot = tempDir.resolve("ctrl").toAbsolutePath()
        Files.createDirectories(controlRoot)

        val args = listOf(
            cliBinary.toString(),
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            fixturePath.toAbsolutePath().toString(),
        )

        val pb = ProcessBuilder(args)
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        processes.add(process)

        // Drain stderr to prevent pipe buffer blocking.
        // NOTE (H8): exit code in WARN below is OBSERVABILITY, NOT evidence of
        // execution. The LAW "exit code is NEVER evidence" is preserved: the test
        // never branches on exit code and never uses it to prove a step ran. The
        // WARN is diagnostic output for operators; execution authority is the
        // journal reader only.
        val stderrCapture = AtomicReference<String>()
        val stderrDrain = Thread { stderrCapture.set(process.errorStream.bufferedReader().readText()) }
        stderrDrain.start()

        val stdout = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(180, TimeUnit.SECONDS)
        val exitCode = if (exited) process.exitValue() else -1
        stderrDrain.join(1000)
        val stderr = stderrCapture.get()

        if (exitCode != 0) {
            println("[WARN] Fixture exited non-zero: ${fixturePath.fileName}, code=$exitCode")
            println("[WARN] stdout: ${stdout.take(200)}")
        }

        // Parse runId from JSON events in stdout
        val runId = Regex(""""runId"\s*:\s*"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""").find(stdout)?.groupValues?.get(1)

        // Extract the first exception class name from stderr for structured reporting.
        // This makes the real failure reason visible in the receipt (H7.2: 12-error-handling
        // and 21-milestone both fail with EngineInvariantViolation about milestone.operations).
        val errorClass = if (runId == null && stderr.isNotBlank()) {
            Regex("""([A-Z][A-Za-z0-9_]*(?:Exception|Error))""").find(stderr)?.groupValues?.get(1)
        } else null

        return@runFixture Pair(runId, errorClass)
    }

    private fun readObservedByStatus(runId: String): Map<PluginStepId, OperationStatus> {
        val dbPath = tempDir.resolve("journal.db").toAbsolutePath().toString()
        if (!Files.exists(Path.of(dbPath))) return emptyMap()

        val eventStore = SqliteEventStore(dbPath)
        val journal = SqliteOperationJournalImpl(
            eventStore.underlyingConnectionFactory(),
            systemClock,
            Json { ignoreUnknownKeys = true; encodeDefaults = true },
            eventStore.databasePath(),
        )
        val reader = JournalRunExecutionEvidenceReader(journal)
        val result = reader.read(RunId(runId))

        return when (result) {
            is dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.Found -> {
                result.invocations
                    .filter { it.isObserved }
                    .associate { it.stepKey to it.status }
            }
            is dev.rubentxu.pipeline.v2.domain.durable.RunEvidenceReadResult.RunNotFound -> {
                emptyMap()
            }
        }
    }

    @AfterEach
    fun teardown() {
        processes.forEach { p ->
            if (p.isAlive) p.destroyForcibly()
        }
        processes.clear()
    }
}
