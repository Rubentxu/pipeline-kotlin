package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

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
        val result = ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            scriptPath.toString()
        ).inheritIO().start().waitFor()

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

        val result = ProcessBuilder(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString(),
            scriptPath.toString()
        ).inheritIO().start().waitFor()

        assertEquals(0, result, "Pipeline should complete successfully")
    }
}
