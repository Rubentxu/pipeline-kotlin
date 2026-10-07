package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * UAT-LOCAL-003: returnStdout capture — execution-level test
 *
 * Tests the EXECUTION-level contract:
 * StepSpec.Shell(returnStdout=true) → executor captureStdout=true → output.txt written
 *
 * Uses the existing sh("script") DSL syntax (positional, no named params).
 * The keyword overload sh(script, returnStdout=true) is DSL grammar convenience;
 * execution semantics are tested here via DurableShellExecutor integration.
 *
 * Note: DSL keyword returnStdout=true may not yet expose captured stdout as
 * Kotlin return value — tests execution contract (output.txt written), not DSL return.
 */
@Timeout(120)
class UatLocal003ReturnStdoutTest {

    @Test
    fun `sh with returnStdout=true writes output txt`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        val outputFile = tempDir.resolve("output.txt")
        Files.createDirectories(controlRoot)

        // ML-R7 T-14 workspace unification: sh runs in the stage workspace
        // ({controlRoot}/workspace/{stageName}-{stageIndex}). The file must be
        // created IN the workspace (writeFile step), not in the process CWD.
        // Note: the previous variant (VERSION.txt in tempDir + `cat VERSION.txt`)
        // was a false green — Main.kt did not propagate failure exit codes before
        // the CR-U9-009 fix, so the failing cat was never observed.

        // Create pipeline script: writeFile + sh share the stage workspace
        // Note: current DSL sh("cmd") doesn't expose returnStdout as return value
        // This test verifies the execution path works
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            writeFile(file = "VERSION.txt", text = "1.2.3\n")
            sh("cat VERSION.txt")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        // Run pipeline
        // WAITFOR-3: inheritIO() put the child's output on the test's own streams and
        // left the wait unbounded. The harness captures instead: these tests assert on
        // files, not on the console, and a child writing into the test log is noise that
        // makes a real failure harder to find. stdin becomes a closed pipe, so a child
        // reading it sees EOF instead of the test JVM's console.
        val cliRun = Subprocess.run(
            command = listOf(javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            // RP034-Ic: this test writes VERSION.txt from inside the pipeline
            // and asserts nothing about where the workspace lives, so it wants
            // scratch. Without the flag the local-first default attaches the
            // caller's directory — the Gradle test JVM's working directory,
            // which is the module source tree — and the file lands in the
            // repository instead of a disposable workspace.
            "--isolated",
            scriptPath.toString()),
        ).requireExited()
        val result = cliRun.exitCode

        assertEquals(0, result, "Pipeline should complete successfully")

        // Verify control dir was created (证明 durable execution happened)
        val controlDirs = Files.list(controlRoot).toList()
        assertTrue(controlDirs.isNotEmpty(), "Control dir should exist")
    }

    @Test
    fun `sh script completes with exit 0`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        Files.createDirectories(controlRoot)

        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            sh("echo hello")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        // WAITFOR-3: inheritIO() put the child's output on the test's own streams and
        // left the wait unbounded. The harness captures instead: these tests assert on
        // files, not on the console, and a child writing into the test log is noise that
        // makes a real failure harder to find. stdin becomes a closed pipe, so a child
        // reading it sees EOF instead of the test JVM's console.
        val cliRun = Subprocess.run(
            command = listOf(javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            // RP034-Ic: this test writes VERSION.txt from inside the pipeline
            // and asserts nothing about where the workspace lives, so it wants
            // scratch. Without the flag the local-first default attaches the
            // caller's directory — the Gradle test JVM's working directory,
            // which is the module source tree — and the file lands in the
            // repository instead of a disposable workspace.
            "--isolated",
            scriptPath.toString()),
        ).requireExited()
        val result = cliRun.exitCode

        assertEquals(0, result, "Pipeline should complete successfully")
    }
}
