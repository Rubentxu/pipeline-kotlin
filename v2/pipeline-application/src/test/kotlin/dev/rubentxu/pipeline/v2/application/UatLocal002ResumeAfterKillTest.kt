package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * UAT-LOCAL-002: Journal-level resume — no re-execution
 *
 * Proves that PipelineRun.kt:787-822 resume logic works:
 * - JVM1 runs pipeline with sh step, we kill it mid-step
 * - Shell step completes in background (detached from JVM)
 * - JVM2 resumes with --resume flag
 * - Result: exactly 1 SUCCEEDED row in journal (no re-execution)
 *
 * Uses: MainKt run + run --resume (full orchestration, not MinMainKt)
 */
class UatLocal002ResumeAfterKillTest {

    @Test
    fun `resume after kill reads result txt no reexec`(@TempDir tempDir: Path) {
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val markerPath = tempDir.resolve("marker.txt")
        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")

        // Ensure control root exists
        Files.createDirectories(controlRoot)

        // Create pipeline script with one sh step that writes to marker
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        val markerPathStr = markerPath.toString()
        val scriptContent = "pipeline {\n    stages {\n        stage(\"TestStage\") {\n            sh(\"echo started >> '$markerPathStr'; sleep 8; echo done >> '$markerPathStr'; exit 0\")\n        }\n    }\n}\n"
        Files.writeString(scriptPath, scriptContent)

        println("DEBUG: markerPathStr=$markerPathStr")
        println("DEBUG: scriptContent=$scriptContent")
        println("DEBUG: controlRoot=$controlRoot")
        println("DEBUG: dbPath=$dbPath")

        // ---- JVM1: Fresh run ----
        val jvm1 = ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            scriptPath.toString()
        )
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val jvm1ErrReader = jvm1.errorStream.bufferedReader()
        val jvm1OutReader = jvm1.inputStream.bufferedReader()

        // Poll until marker contains 'started' (max 60s - pipeline compilation takes time)
        val startedDeadline = System.currentTimeMillis() + 60_000
        var jvm1Exited = false
        var jvm1ExitCode: Int? = null
        while (System.currentTimeMillis() < startedDeadline) {
            if (Files.exists(markerPath) && Files.readString(markerPath).contains("started")) {
                break
            }
            if (!jvm1.isAlive) {
                jvm1Exited = true
                jvm1ExitCode = jvm1.exitValue()
                break
            }
            Thread.sleep(500)
        }

        if (jvm1Exited) {
            val stderr = jvm1ErrReader.readText()
            val stdout = jvm1OutReader.readText()
            println("DEBUG: JVM1 exited early. exitCode=$jvm1ExitCode, stderr=$stderr, stdout=$stdout")
        }

        assertTrue(
            Files.exists(markerPath) && Files.readString(markerPath).contains("started"),
            "Marker should contain 'started' within 60s"
        )

        // Kill JVM1 while sh step is running (during sleep 8)
        jvm1.destroyForcibly().waitFor()

        // ---- Wait for detached script to complete alone ----
        // The detached shell should finish: sleep 8 completes, writes 'done', exits 0
        val doneDeadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < doneDeadline) {
            if (Files.exists(markerPath) && Files.readString(markerPath).contains("done")) {
                break
            }
            Thread.sleep(500)
        }
        assertTrue(
            Files.exists(markerPath) && Files.readString(markerPath).contains("done"),
            "Marker should contain 'done' within 30s of kill"
        )

        // ---- Scan controlRoot to find actual control dir ----
        // List ALL dirs in controlRoot
        val allDirs = Files.list(controlRoot).use { it.toList() }
        val allDirNames = allDirs.map { it.fileName.toString() }
        println("DEBUG: controlRoot=$controlRoot, allDirs=$allDirNames")

        // Find the control dir by searching for result.txt recursively
        // Control dir structure: {controlRoot}/workspace/stage-N/{opId}/result.txt
        val resultFile = Files.find(controlRoot, 5,
            { path, attrs -> path.fileName.toString() == "result.txt" }
        ).findFirst().orElse(null)
        val actualOpIdDir = resultFile?.parent

        assertTrue(actualOpIdDir != null, "Should find a control dir containing result.txt. controlRoot=$controlRoot, dirs=$allDirNames")

        val resultExists = Files.exists(resultFile)
        println("DEBUG: resultFile=$resultFile, exists=$resultExists")
        if (resultExists) {
            val resultContent = Files.readString(resultFile).trim()
            println("DEBUG: resultContent=$resultContent")
        }

        assertTrue(Files.exists(resultFile), "result.txt should exist at $resultFile")
        val resultContent = Files.readString(resultFile).trim()
        assertEquals("0", resultContent, "result.txt should contain '0'")

        // ---- JVM2: Resume ----
        val jvm2 = ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            "--resume",
            scriptPath.toString()
        )
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val jvm2ExitCode = jvm2.waitFor()
        val jvm2Stderr = jvm2.errorStream.bufferedReader().readText()
        val jvm2Stdout = jvm2.inputStream.bufferedReader().readText()

        println("DEBUG: JVM2 exitCode=$jvm2ExitCode, stderr=$jvm2Stderr, stdout=$jvm2Stdout")

        assertEquals(0, jvm2ExitCode, "Resume should succeed with exit 0")

        // Verify marker still shows 2 lines (no re-execution)
        val markerContent = Files.readString(markerPath)
        val markerLines = markerContent.lines().filter { it.isNotBlank() }
        assertEquals(2, markerLines.size, "Marker should still have 2 lines (no re-exec). marker=$markerContent")
        assertEquals(listOf("started", "done"), markerLines)

        // ---- Verify journal: exactly 1 SUCCEEDED row, ended_at not null ----
        // Use production SqliteOperationJournalImpl API to open the existing db read-only
        val opId = actualOpIdDir!!.fileName.toString()
        val journal: dev.rubentxu.pipeline.v2.events.durable.OperationJournal =
            dev.rubentxu.pipeline.v2.events.durable.SqliteOperationJournalImpl(
                { java.sql.DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}") },
                dev.rubentxu.pipeline.v2.application.SystemClock(),
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true },
                dbPath.toAbsolutePath().toString()
            )

        // (a) Exactly ONE operation row exists for this opId
        val op = journal.get(opId)
        assertNotNull(op, "Journal should have an entry for opId=$opId. controlDir=$actualOpIdDir")
        val runId = opId.substringBeforeLast("-s")
        val runOps = journal.listForRun(runId)
        assertEquals(1, runOps.size, "Journal should have exactly 1 operation row for runId=$runId")
        assertEquals(opId, runOps.first().id, "Operation id should match control dir name")

        // (b) Status is SUCCEEDED (terminal)
        assertEquals(
            dev.rubentxu.pipeline.v2.domain.durable.OperationStatus.SUCCEEDED,
            op!!.status,
            "Operation status should be SUCCEEDED. got=${op.status}"
        )
        assertTrue(op.status.isTerminal, "SUCCEEDED must be terminal")

        // (c) ended_at is NOT NULL
        val endedAt = journal.getEndedAt(opId, op.attempt)
        assertNotNull(endedAt, "ended_at should not be null for SUCCEEDED operation")
        assertTrue(endedAt!! > 0, "ended_at should be a positive timestamp. got=$endedAt")
    }
}
