package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.RunFinished
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-LOCAL-010 OFFLINE: Offline canary scenarios for ML-R8 L7 smoke E2E sandbox.
 *
 * **Execution order**: This class MUST run AFTER [UatLocal010SmokeE2ESandboxTest]
 * (online class, `V2_SMOKE_E2E_OK=true`) has warmed the Gradle/Maven caches.
 * Online class produces cached Gradle distributions and compiled jars that these
 * offline scenarios re-use without network access.
 *
 * Run order:
 *   1. Online first:  `V2_SMOKE_E2E_OK=true`  → `UatLocal010SmokeE2ESandboxTest`
 *   2. Offline second: `V2_SMOKE_E2E_OFFLINE_OK=true` → `UatLocal010SmokeE2ESandboxOfflineTest`
 *
 * Class-level `@Timeout(2400)` allows adequate wall-clock for cache-warm offline builds
 * (the outer wrapper `timeout` is 2400 s per AGENTS.md rule 4 for long-running UAT suites).
 *
 * @see <a href="ADR-0053">ADR-0053 — ML-R8 L7 smoke E2E sandbox</a>
 * @see <a href="tasks-amendment-2.md">tasks-amendment-2.md §Class Split</a>
 */
@Timeout(2400)
class UatLocal010SmokeE2ESandboxOfflineTest {

    private val processes = mutableListOf<Process>()

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun teardown() {
        // AGENTS.md §8: destroyForcibly() + SIGKILL process group for setsid children
        processes.forEach { p ->
            if (p.isAlive) {
                p.destroyForcibly()
            }
        }
        processes.clear()

        val selfPid = ProcessHandle.current().pid()
        try {
            val pb = ProcessBuilder("pgrep", "-P", selfPid.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
            val childProcs = pb.start().inputStream.bufferedReader().readText().trim()
            if (childProcs.isNotEmpty()) {
                childProcs.lines().filter { it.isNotBlank() }.forEach { pid ->
                    try {
                        ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
    }

    private fun assumeLinux() {
        assumeTrue(
            System.getProperty("os.name", "").lowercase().contains("linux"),
            "UAT-LOCAL-010 requires Linux"
        )
    }

    // Pinned SHA for picocli (same as online class — used for offline re-run)
    companion object {
        private const val gradle_picocli = "10509c0af89aa3254ca14ba90d9b3b7168e57994" // v4.7.6
    }

    /**
     * Runs a pipeline script and returns stdout + decoded events.
     * DUPLICATED verbatim from UatLocal009TopStepsTest.kt:74-111 (D8/D16 — rule of three: extract on 3rd caller)
     */
    private fun runPipeline(scriptPath: Path, extraArgs: Array<String> = emptyArray()): L7Result {
        assumeLinux()
        val javaHome = System.getProperty("java.home")
        val classpath = System.getProperty("java.class.path")
        val dbPath = tempDir.resolve("journal.db").toAbsolutePath()
        val controlRoot = tempDir.resolve("ctrl").toAbsolutePath()
        Files.createDirectories(controlRoot)

        val args = mutableListOf(
            javaHome + "/bin/java",
            "-cp", classpath,
            "dev.rubentxu.pipeline.v2.application.MainKt",
            "run",
            "--db", dbPath.toString(),
            "--control-root", controlRoot.toString()
        )
        args.addAll(extraArgs.toList())
        args.add(scriptPath.toAbsolutePath().toString())

        val pb = ProcessBuilder(args)
            .directory(tempDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        processes.add(process)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exited = process.waitFor(300, TimeUnit.SECONDS)
        val exitCode = if (exited) process.exitValue() else -1
        val combined = if (stderr.isNotBlank()) "$stdout\nSTDERR:\n$stderr" else stdout
        return L7Result(
            stdout = combined,
            exitCode = exitCode,
            events = try { JsonEventLog.decode(stdout) } catch (_: Exception) { emptyList() }
        )
    }

    data class L7Result(
        val stdout: String,
        val exitCode: Int,
        val events: List<DomainEvent>
    )

    // ─── SC-010-08: offline canary — warm cache satisfies build ──────────────────
    // REQUIRES: online class has warmed Gradle cache via UatLocal010SmokeE2ESandboxTest.
    // This scenario runs with `--offline` to verify the cache is sufficient without network.

    @Test
    @EnabledIfEnvironmentVariable(named = "V2_SMOKE_E2E_OFFLINE_OK", matches = "true")
    fun `SC-010-08 picocli offline re-run exits 0 with warm cache`() {
        assumeLinux()

        val script = tempDir.resolve("sc-010-08.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("build") {
                        sh("git clone https://github.com/remkop/picocli.git . && git checkout $gradle_picocli")
                        sh("./gradlew assemble --no-daemon --offline")
                        archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Offline run must exit 0 when cache is warm. stdout: ${result.stdout}")

        val archiveEvents = result.events.filterIsInstance<ArtifactArchived>()
        assertTrue(archiveEvents.isNotEmpty(),
            "ArtifactArchived must be emitted on offline run. Events: ${result.events.map { it::class.simpleName }}")
    }

    // ─── SC-010-12: __artefact_canary__ zero occurrences round-gate ─────────────────
    // REQUIRES: online class has produced archived artefacts via UatLocal010SmokeE2ESandboxTest.
    // This scenario verifies no canary artefacts leak into the event stream or stdout.

    @Test
    @EnabledIfEnvironmentVariable(named = "V2_SMOKE_E2E_OFFLINE_OK", matches = "true")
    fun `SC-010-12 artefact canary zero occurrences in output`() {
        assumeLinux()

        val canary = "__artefact_canary__"

        // Pipeline that writes, archives, and echoes the canary value
        val script = tempDir.resolve("sc-010-12.pipeline.kts")
        Files.writeString(script, """
            pipeline {
                stages {
                    stage("canary") {
                        writeFile(file = "settings.xml", text = "API_KEY=$canary")
                        archiveArtifacts(artifacts = "settings.xml", allowEmptyArchive = false)
                        withEnv(["PATH+HACK=/hack/with/$canary"]) {
                            sh("echo ${'$'}PATH")
                        }
                    }
                }
            }
        """.trimIndent())

        val result = runPipeline(script)

        assertEquals(0, result.exitCode,
            "Pipeline should exit 0. stdout: ${result.stdout}")

        // Encode all events to JSON and scan for canary
        val eventsJson = JsonEventLog.encode(result.events)

        // Check 1: events JSON — no literal canary
        assertFalse(eventsJson.contains(canary),
            "Canary must NOT appear in events JSON. Events: ${result.events.map { it::class.simpleName }}")

        // Check 2: events JSON — no base64 std encoding
        val canaryBase64 = java.util.Base64.getEncoder().encodeToString(canary.toByteArray())
        assertFalse(eventsJson.contains(canaryBase64),
            "Canary (base64 std) must NOT appear in events JSON. Base64: $canaryBase64")

        // Check 3: events JSON — no base64 url-safe encoding
        val canaryBase64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(canary.toByteArray())
        assertFalse(eventsJson.contains(canaryBase64Url),
            "Canary (base64 url-safe) must NOT appear in events JSON. Base64Url: $canaryBase64Url")

        // Check 4: stdout — no literal canary
        assertFalse(result.stdout.contains(canary),
            "Canary must NOT appear in stdout. stdout: ${result.stdout.take(500)}")
    }
}
