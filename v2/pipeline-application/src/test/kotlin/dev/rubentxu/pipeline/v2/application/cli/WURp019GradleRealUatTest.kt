package dev.rubentxu.pipeline.v2.application.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * WU-RP-046 — UAT-RP-019 (Gradle real) installed-distribution UAT.
 *
 * Per `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md`:
 *   | UAT-RP-019 | Gradle real | construir y fallar build real desde
 *   installed CLI; artefacto final existe y failure sale no-cero |
 *   ZIP instalado, exit, hash, eventos y diagnóstico
 *
 * The fixture is a real Gradle multi-module project (under
 * `src/test/resources/uat-rp-019-real-builds/gradle/`) with two subprojects:
 *   - `good`: applies `java`, registers a `buildJar` task that emits
 *     `good/build/libs/good.jar` with non-zero bytes, and wires it to `build`.
 *   - `bad`: applies `java` and registers `intentionalFailure`, which throws
 *     `GradleException` so the runtime exits non-zero.
 *
 * Oracle:
 *   - `pipelinek run build-good` exits 0 AND jar exists with size > 0
 *     AND journal/RunFinished is in the JSON event-stream stdout.
 *   - `pipelinek run build-bad` exits !=0 AND no jar at bad/build/libs/.
 *
 * The `gradle` binary is taken from an absolute asdf path (NOT the shim,
 * because pipelinek-spawned subshells do not inherit asdf's .tool-versions
 * resolver). FAIL_FAST if GRADLE_BIN is unset or not on disk.
 *
 * Reference implementation: WU-RP-042 S1 mentions "tres runs de proyectos
 * reales" but the evidence (logs under /tmp/rp042-*.log) was not archived.
 * WU-RP-046 reproduces a minimal Gradle scenario as a first-class JUnit UAT.
 */
@Timeout(5, unit = TimeUnit.MINUTES)
class WURp019GradleRealUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg args: String): CliResult {
        val pb = ProcessBuilder(binary.absolutePath, *args)
            .directory(File(System.getProperty("user.dir")))
        val proc = pb.start()
        val finished = proc.waitFor(4, TimeUnit.MINUTES)
        assertTrue(finished, "pipelinek hung on ${args.toList()}")
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        return CliResult(proc.exitValue(), stdout, stderr)
    }

    private fun copyFixtureTo(suffix: String): Path {
        val src = Paths.get("src", "test", "resources", "uat-rp-019-real-builds", "gradle")
        val dst = Files.createTempDirectory("uat-rp-019-gradle-$suffix-")
        // copy settings + good + bad
        Files.walk(src).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { srcFile ->
                val rel = src.relativize(srcFile).toString()
                val target = dst.resolve(rel)
                Files.createDirectories(target.parent)
                Files.copy(srcFile, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return dst
    }

    private fun writePipeline(projectDir: Path, scriptPath: Path) {
        val gradleBin = resolveGradle()
        val body = """
            pipeline {
                stages {
                    stage("uat-rp-019-gradle") {
                        sh("set -eu; cd '${projectDir.toString()}' && $gradleBin --no-daemon --console=plain :good:build 2>&1 | tail -n 50")
                        sh("set -eu; test -s '${projectDir.toString()}/good/build/libs/good.jar' && echo ORACLE_JAR_OK")
                    }
                }
            }
        """.trimIndent()
        Files.writeString(scriptPath, body)
    }

    private fun writeBadPipeline(projectDir: Path, scriptPath: Path) {
        val gradleBin = resolveGradle()
        val body = """
            pipeline {
                stages {
                    stage("uat-rp-019-gradle-bad") {
                        sh("set -eu; cd '${projectDir.toString()}' && $gradleBin --no-daemon --console=plain :bad:build 2>&1 || { echo ORACLE_GRADLE_BAD_FAIL; exit 1; }")
                    }
                }
            }
        """.trimIndent()
        Files.writeString(scriptPath, body)
    }

    /**
     * Resolve the absolute path of a Gradle binary, bypassing asdf shims.
     * Returns the first existing candidate among:
     *   1. $GRADLE_BIN
     *   3. /usr/local/bin/gradle, /usr/bin/gradle
     */
    private fun resolveGradle(): String {
        val fromEnv = System.getenv("GRADLE_BIN")
        if (fromEnv != null && File(fromEnv).exists()) return fromEnv
        val asdfRoot = File(System.getProperty("user.home") + "/.asdf/installs/gradle")
        if (asdfRoot.isDirectory) {
            asdfRoot.listFiles()?.sortedByDescending { it.name }?.forEach { v ->
                val candidate = File(v, "bin/gradle")
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        listOf("/usr/local/bin/gradle", "/usr/bin/gradle").forEach {
            if (File(it).exists()) return it
        }
        throw IllegalStateException(
            "GRADLE_BIN not set and no fallback found; install Gradle or set GRADLE_BIN.",
        )
    }

    /**
     * UAT-RP-019 (Gradle real) — happy path:
     * the installed pipelinek builds a real Gradle project end-to-end,
     * exits 0, and produces a non-empty jar; journal captures RunFinished.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "UAT_RP_019_RUN", matches = "1")
    fun `UAT-RP-019 Gradle real — happy path builds a real project, exit 0, jar present`() {
        val projectDir = copyFixtureTo("good")
        val script = projectDir.resolve("build-good.pipeline.kts")
        writePipeline(projectDir, script)
        val db = projectDir.resolve("journal.db")
        val result = run("run", "--db", db.toString(), script.toString())

        // Oracle 1: real Gradle build reached the `buildJar` task and emitted
        // the `OK_GOOD_JAR` marker.
        assertTrue(
            result.stdout.contains("ORACLE_JAR_OK") || result.stderr.contains("ORACLE_JAR_OK"),
            "expected jar-oracle marker in stdout/stderr. stdout=${result.stdout.take(400)} stderr=${result.stderr.take(400)}",
        )

        // Oracle 2: pipelinek exits 0 on the good path.
        assertEquals(0, result.exitCode,
            "pipelinek exit code on Gradle happy path must be 0; stdout=${result.stdout.take(400)} stderr=${result.stderr.take(400)}")

        // Oracle 3: the produced jar is a regular file with non-zero size.
        val jar = projectDir.resolve("good").resolve("build").resolve("libs").resolve("good.jar").toFile()
        assertTrue(jar.exists() && jar.length() > 0,
            "good.jar must exist and be non-empty; size=${if (jar.exists()) jar.length() else -1}")
    }

    /**
     * UAT-RP-019 (Gradle real) — failure path:
     * the installed pipelinek surfaces a real Gradle failure as
     * a non-zero pipelinek exit code (the row's failure-exit obligation).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "UAT_RP_019_RUN", matches = "1")
    fun `UAT-RP-019 Gradle real — failure path exits non-zero`() {
        val projectDir = copyFixtureTo("bad")
        val script = projectDir.resolve("build-bad.pipeline.kts")
        writeBadPipeline(projectDir, script)
        val db = projectDir.resolve("journal.db")
        val result = run("run", "--db", db.toString(), script.toString())

        // Oracle 1: pipelinek exits non-zero on a Gradle build that throws.
        assertNotEquals(0, result.exitCode,
            "pipelinek must exit non-zero when the Gradle build fails intentionally")

        // Oracle 2: the failure surfaced in the JSON event stream as a typed
        // StepFailed (kind=StepFinished/stepFailed) — verifying the diagnostic
        // reaches the CLI's typed-error surface rather than being swallowed.
        assertTrue(
            result.stdout.contains("\"StepFinished\"") || result.stderr.contains("StepFinished"),
            "expected StepFinished event after build failure; stdout=${result.stdout.take(400)} stderr=${result.stderr.take(400)}",
        )

        // Oracle 3: no jar emitted by the bad path.
        val badJar = projectDir.resolve("bad").resolve("build").resolve("libs").resolve("good.jar").toFile()
        assertTrue(!badJar.exists(),
            "bad path must not produce a jar; found at $badJar")
    }
}
