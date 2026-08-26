package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * UAT-LOCAL-004: Timeout — FAILED_TIMEOUT classification (TMO-S-001/002/013)
 *
 * Behavioral tests that exercise the real watchdog:
 * - TMO-S-001: timeoutMs honoured from StepSpec.timeoutMillis
 * - TMO-S-002: timeoutMs from OptionsSpec.timeout cascades
 * - TMO-S-013: no timeout when timeoutMs absent/null
 *
 * NOTE: Main.kt always exits JVM with 0 regardless of pipeline outcome.
 * Assertions must check the JSON event stream (RunFinished.outcome) not process exit code.
 *
 * Uses DurableShellExecutor timeout watchdog (SIGKILL via setsid).
 * All tests use --control-root to enable durable execution.
 */
@Timeout(120)
class UatLocal004TimeoutTest {

    /**
     * TMO-S-013: no timeout when timeoutMs absent — long-ish sleep completes successfully.
     * Uses the DSL positional sh(command) (no timeoutMs), verifying TMO-S-013 semantics.
     */
    @Test
    fun `TMO-S-013 no timeout when absent long sleep completes successfully`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        // TMO-S-013: no timeout when timeoutMs absent — sleep 2s completes successfully
        // Uses positional sh(command) — no timeoutMs set (TMO-S-013 semantics)
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            sh("sleep 2; echo done")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        // TMO-S-013: no timeout when absent — pipeline completes successfully
        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Pipeline with no timeout should complete successfully (TMO-S-013). stdout=$stdout")
    }

    /**
     * TMO-S-001: timeout honoured from stage-level options { timeout }.
     * Uses options { timeout = 2L } around sh("sleep 30") — watchdog fires → FAILED_TIMEOUT.
     * Verifies: RunFinished.outcome=failure, timeout.flag exists, journal status FAILED_TIMEOUT.
     */
    @Test
    fun `TMO-S-001 stage timeout fires and classifies FAILED_TIMEOUT`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        // TMO-S-001: stage options { timeout=2 } with sleep 30 → watchdog fires at 2s
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            options {
                timeout = 2L
            }
            sh("sleep 30; echo done")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        // Verify pipeline failed
        val runFinished = findRunFinished(stdout)
        assertEquals("failure", runFinished,
            "Pipeline with timeout should fail (watchdog fires). stdout=$stdout")

        // Find the step control directory (same as run directory in this implementation)
        val runIdPath: Path = Files.list(controlRoot)
            .filter { Files.isDirectory(it) }
            .findFirst()
            .orElseThrow {
                val contents = try { Files.list(controlRoot).toList() } catch (_: Exception) { emptyList() }
                AssertionError("No run directory found in controlRoot. Contents: $contents. stdout=$stdout")
            }
        // Step directory == run directory in this structure
        val stepPath: Path = runIdPath

        // Verify timeout.flag was written (TMO-S-005: flag written BEFORE kill)
        val timeoutFlag = stepPath.resolve("timeout.flag")
        assertTrue(Files.exists(timeoutFlag), "timeout.flag must exist when watchdog fires (TMO-S-005)")

        // Verify journal records FAILED_TIMEOUT (not FAILED, not LOST)
        val opId = stepPath.fileName.toString()
        val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal =
            dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl(
                { java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}") },
                dev.rubentxu.pipeline.v2.application.SystemClock(),
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true },
                dbPath.toAbsolutePath().toString()
            )

        val op = journal.get(opId)
        assertNotNull(op, "Journal should have an entry for opId=$opId")
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.FAILED_TIMEOUT,
            op!!.status,
            "Operation status should be FAILED_TIMEOUT (watchdog fired), got=${op.status}"
        )
        assertTrue(op.status.isTerminal, "FAILED_TIMEOUT must be terminal (TMO-S-006)")
    }

    /**
     * TMO-S-013 + TMO-S-011: FAILED_TIMEOUT is distinct from FAILED.
     * A step that times out has FAILED_TIMEOUT (not plain FAILED).
     * A step that exits non-zero without timeout has plain FAILED.
     */
    @Test
    fun `TMO-S-011 FAILED_TIMEOUT distinct from FAILED non-zero without timeout is FAILED`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        // Non-zero exit without timeout → FAILED (not FAILED_TIMEOUT)
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            sh("exit 1")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        // Verify pipeline failed via JSON event stream
        val runFinished = findRunFinished(stdout)
        assertEquals("failure", runFinished,
            "Pipeline with non-zero exit should fail (RunFinished.outcome). stdout=$stdout")

        // Find the step control directory (step dir == run dir in this structure)
        val runIdPath: Path = Files.list(controlRoot)
            .filter { Files.isDirectory(it) }
            .findFirst()
            .orElseThrow { AssertionError("No run directory") }
        // Step directory == run directory in this implementation
        val stepPath: Path = runIdPath
        val opId = stepPath.fileName.toString()

        // Verify status is FAILED (not FAILED_TIMEOUT)
        val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal =
            dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl(
                { java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}") },
                dev.rubentxu.pipeline.v2.application.SystemClock(),
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true },
                dbPath.toAbsolutePath().toString()
            )

        val op = journal.get(opId)
        assertNotNull(op, "Journal should have entry for opId=$opId")
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.FAILED,
            op!!.status,
            "Non-zero exit without timeout should be FAILED (not FAILED_TIMEOUT). got=${op.status}"
        )
    }

    /**
     * TMO-S-002: timeoutMs cascades from stage-level options.
     * Stage has options { timeout = 3L }, step sleeps 20s → stage-level timeout fires.
     * Verifies: timeout.flag exists, journal status FAILED_TIMEOUT.
     */
    @Test
    fun `TMO-S-002 timeoutMs cascades from stage-level options fires at stage level`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        // TMO-S-002: stage-level timeout cascades to step (no step-level timeout set)
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            options {
                timeout = 3L
            }
            sh("sleep 20; echo done")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        // Find the step control directory
        val runIdPath: Path = Files.list(controlRoot)
            .filter { Files.isDirectory(it) }
            .findFirst()
            .orElseThrow { AssertionError("No run directory") }
        // Step directory == run directory in this implementation
        val stepPath: Path = runIdPath

        // Verify timeout.flag was written
        val timeoutFlag = stepPath.resolve("timeout.flag")
        assertTrue(Files.exists(timeoutFlag), "timeout.flag must exist (TMO-S-005)")

        // Verify journal status is FAILED_TIMEOUT
        val opId = stepPath.fileName.toString()
        val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal =
            dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl(
                { java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}") },
                dev.rubentxu.pipeline.v2.application.SystemClock(),
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true },
                dbPath.toAbsolutePath().toString()
            )

        val op = journal.get(opId)
        assertNotNull(op, "Journal should have entry for opId=$opId")
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.FAILED_TIMEOUT,
            op!!.status,
            "Stage-level timeout should produce FAILED_TIMEOUT, got=${op.status}"
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Runs the pipeline and returns the JSON event stream from stdout.
     * Main.kt always exits JVM with 0 regardless of pipeline outcome,
     * so we must parse the JSON event stream to determine pipeline result.
     */
    private fun runPipeline(
        javaHome: String,
        classpath: String,
        dbPath: Path,
        controlRoot: Path,
        scriptPath: Path,
    ): String {
        val pb = ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            scriptPath.toString()
        )
            .directory(scriptPath.parent.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val stdout = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return stdout
    }

    /**
     * Parses the JSON event stream and returns the RunFinished.outcome field.
     */
    private fun findRunFinished(jsonText: String): String {
        val events = JsonEventLog.decode(jsonText)
        val runFinished = events.filterIsInstance<RunFinished>().firstOrNull()
            ?: throw AssertionError("No RunFinished event in output: $jsonText")
        return runFinished.outcome
    }
}
