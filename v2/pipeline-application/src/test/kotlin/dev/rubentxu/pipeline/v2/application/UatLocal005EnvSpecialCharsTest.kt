package dev.rubentxu.pipeline.v2.application

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
 * UAT-LOCAL-005: Env special chars — WS-S-005/008/009/010 behavioral coverage
 *
 * Behavioral tests exercising env via stage-level environment {} block (Jenkins faithful).
 * Env values flow through StageSpec.environment → EnvModel.apply → pb.environment().putAll.
 * P2 invariant: env values NEVER appear in argv (verified by production code path).
 *
 * All tests use durable execution (--control-root) and verify:
 * - WS-S-005: env injection via pb.environment() ONLY (P2 grep-able)
 * - WS-S-008: equals sign in env value preserved verbatim
 * - WS-S-009: newline in env value preserved verbatim
 * - WS-S-010: unicode RTL / backtick / shell metacharacters in env value preserved
 * - WS-S-006: JAVA_HOME prepends /bin to PATH
 * - WS-S-007: M2_HOME prepends /bin to PATH
 *
 * Note: script content uses ${'$'}VAR to escape $ in Kotlin strings so shell receives $VAR literally.
 */
@Timeout(120)
class UatLocal005EnvSpecialCharsTest {

    /**
     * WS-S-008: equals sign in env value — preserved verbatim, not truncated.
     * Uses sh(script, env) DSL keyword with KEY=val=ue and verifies getenv("KEY") returns exact value.
     * Writes to output file (avoids returnStdout complexity) and reads content.
     */
    @Test
    fun `WS-S-008 env value with equals sign preserved verbatim`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        val outputFile = tempDir.resolve("out.txt")
        Files.createDirectories(controlRoot)

        // WS-S-008: env value with = signs — script echoes getenv to output file
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            environment {
                env("MYENV", "value=with=equals")
            }
            sh("printenv MYENV > '$outputFile'")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        // Verify pipeline succeeded
        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Pipeline with env containing = should complete successfully. stdout=$stdout")

        // Verify output file content
        assertTrue(Files.exists(outputFile), "Output file should exist")
        val outputContent = Files.readString(outputFile).trim()
        assertEquals("value=with=equals", outputContent,
            "getenv('MYENV') should return 'value=with=equals' verbatim — equals sign preserved (WS-S-008)")
    }

    /**
     * WS-S-009: newline in env value — preserved verbatim.
     * Verifies that newline in env value is NOT shell-interpreted (no split).
     */
    @Test
    fun `WS-S-009 env value with newline preserved verbatim`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        val outputFile = tempDir.resolve("out.txt")
        Files.createDirectories(controlRoot)

        // WS-S-009: newline in env value — script echoes getenv to output file
        // The Kotlin String "line1\nline2" becomes actual newline; we use printenv to avoid $VAR issues
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            environment {
                env("MYENV", "line1\nline2")
            }
            sh("printenv MYENV > '$outputFile'")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Pipeline with newline in env should complete. stdout=$stdout")

        assertTrue(Files.exists(outputFile), "Output file should exist")
        val outputContent = Files.readString(outputFile)
        // printenv appends \n to the value — assert exact output including trailing newline
        assertEquals("line1\nline2\n", outputContent,
            "getenv('MYENV') should preserve newline literally — got: ${outputContent.toByteArray().joinToString()} (WS-S-009)")
    }

    /**
     * WS-S-010: unicode RTL override (U+202E), backtick, shell metacharacters in env value.
     */
    @Test
    fun `WS-S-010 env value with unicode RTL backtick and shell metachars preserved`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        val outputFile = tempDir.resolve("out.txt")
        Files.createDirectories(controlRoot)

        // WS-S-010: unicode RTL + backtick + shell metacharacters
        // Use printenv to avoid shell variable expansion issues
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            environment {
                env("MYENV", "val\u202Eue`echo x`&&|;")
            }
            sh("printenv MYENV > '$outputFile'")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Pipeline with unicode/metachars in env should complete. stdout=$stdout")

        assertTrue(Files.exists(outputFile), "Output file should exist")
        val outputContent = Files.readString(outputFile)
        // printenv appends \n to the value — assert exact output including trailing newline
        assertEquals("val\u202Eue`echo x`&&|;\n", outputContent,
            "getenv('MYENV') should preserve unicode RTL, backtick, shell metachars verbatim (WS-S-010)")
    }

    /**
     * WS-S-006: JAVA_HOME prepends /bin to PATH at runtime.
     * Sets JAVA_HOME and verifies PATH contains JAVA_HOME/bin.
     */
    @Test
    fun `WS-S-006 JAVA_HOME prepends bin to PATH`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        val outputFile = tempDir.resolve("out.txt")
        Files.createDirectories(controlRoot)

        // WS-S-006: JAVA_HOME/bin prepended to PATH
        // We set JAVA_HOME to the current java.home and verify PATH starts with JAVA_HOME/bin
        // Use printenv to avoid $VAR syntax in shell commands (avoids Kotlin string template issues)
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            environment {
                env("JAVA_HOME", "$javaHome")
            }
            sh("printenv PATH | tr ':' '\n' | head -1 > '$outputFile'")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Pipeline with JAVA_HOME env should complete. stdout=$stdout")

        assertTrue(Files.exists(outputFile), "Output file should exist")
        val firstPathEntry = Files.readString(outputFile).trim()
        assertEquals("$javaHome/bin", firstPathEntry,
            "First PATH entry should be JAVA_HOME/bin (WS-S-006). got=$firstPathEntry")
    }

    /**
     * WS-S-007: M2_HOME prepends /bin to PATH at runtime.
     */
    @Test
    fun `WS-S-007 M2_HOME prepends bin to PATH`(@TempDir tempDir: Path) {
        assumeTrue(System.getProperty("os.name", "").lowercase().contains("linux"),
            "Durable shell is Linux-only")

        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")

        val controlRoot = tempDir.resolve("ctrl")
        val dbPath = tempDir.resolve("journal.db")
        val outputFile = tempDir.resolve("out.txt")
        Files.createDirectories(controlRoot)

        // WS-S-007: M2_HOME/bin prepended to PATH (without JAVA_HOME)
        // Use printenv to avoid $VAR syntax in shell commands
        val scriptContent = """
pipeline {
    stages {
        stage("TestStage") {
            environment {
                env("M2_HOME", "$javaHome")
            }
            sh("printenv PATH | tr ':' '\n' | head -1 > '$outputFile'")
        }
    }
}
"""
        val scriptPath = tempDir.resolve("test.pipeline.kts")
        Files.writeString(scriptPath, scriptContent)

        val stdout = runPipeline(javaHome, classpath, dbPath, controlRoot, scriptPath)

        val runFinished = findRunFinished(stdout)
        assertEquals("success", runFinished,
            "Pipeline with M2_HOME env should complete. stdout=$stdout")

        assertTrue(Files.exists(outputFile), "Output file should exist")
        val firstPathEntry = Files.readString(outputFile).trim()
        assertEquals("$javaHome/bin", firstPathEntry,
            "First PATH entry should be M2_HOME/bin (WS-S-007). got=$firstPathEntry")
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
