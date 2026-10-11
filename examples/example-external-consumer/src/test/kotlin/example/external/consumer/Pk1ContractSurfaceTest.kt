package example.external.consumer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path

/**
 * PK-1 / ADR-PK1-IDENTITY — end-to-end test of the new
 * machine-verifiable contract surfaces.
 *
 * The test runs the published `pipeline` binary (resolved from the
 * v2 build's `installDist` output) and exercises the two new
 * subcommands:
 *
 *  - `pipeline capabilities` — must emit one line of
 *    `pipelinek.capabilities/1` JSON with the documented 11 entries.
 *  - `pipeline sdk-info` — must emit one line of `pipelinek.sdk/1`
 *    JSON with the 5 SDK coordinates + the BOM.
 *
 * The test is gated on the env var `PIPELINEK_BIN` pointing at the
 * `pipelinek` binary; if absent the test is skipped, since not every
 * CI lane runs `installDist`. The expected path under this worktree
 * is `../../v2/pipeline-application/build/install/pipelinek/bin/pipelinek`.
 */
@EnabledIfEnvironmentVariable(named = "PIPELINEK_BIN", matches = ".+")
class Pk1ContractSurfaceTest {

    private val binary: String = System.getenv("PIPELINEK_BIN") ?: error("PIPELINEK_BIN not set")

    @Test
    fun `pipeline capabilities emits a single line of pipelinek_capabilities_v1 with 11 entries`() {
        val (exit, out, err) = run("--sdk-version-shape", arrayOf("capabilities"))
        assertEquals(0, exit, "pipeline capabilities must exit 0; stderr: $err")
        val line = out.lineSequence().first { it.isNotBlank() }
        assertTrue(
            line.startsWith("{\"schema\":\"pipelinek.capabilities/1\""),
            "expected pipelinek.capabilities/1 schema, got: $line",
        )
        // Crude but stable: count `"id":"` occurrences — there should be
        // exactly 11 capabilities in v0.56.0-rc1.
        val idCount = Regex("\"id\":\"").findAll(line).count()
        assertEquals(11, idCount, "expected 11 capability ids, got $idCount in line: $line")
        // Every status must be either PUBLICADA or EXPERIMENTAL.
        val statuses = Regex("\"status\":\"([A-Z]+)\"").findAll(line).map { it.groupValues[1] }.toList()
        assertTrue(
            statuses.all { it == "PUBLICADA" || it == "EXPERIMENTAL" || it == "DEPRECATED" },
            "unexpected status values: $statuses",
        )
        // The two M1 PUBLICADA entries must be present.
        assertTrue(line.contains("\"id\":\"output.follow.v1\",\"status\":\"PUBLICADA\""))
        assertTrue(line.contains("\"id\":\"events.follow.v1\",\"status\":\"PUBLICADA\""))
    }

    @Test
    fun `pipeline sdk-info emits a single line of pipelinek_sdk_v1 with 5 coords and BOM`() {
        val (exit, out, err) = run("--sdk-version-shape", arrayOf("sdk-info"))
        assertEquals(0, exit, "pipeline sdk-info must exit 0; stderr: $err")
        val line = out.lineSequence().first { it.isNotBlank() }
        assertTrue(
            line.startsWith("{\"schema\":\"pipelinek.sdk/1\""),
            "expected pipelinek.sdk/1 schema, got: $line",
        )
        // The 5 SDK coordinates + the BOM.
        val artifacts = Regex("\"artifact\":\"([^\"]+)\"").findAll(line).map { it.groupValues[1] }.toList()
        assertEquals(
            setOf(
                "pipeline-domain",
                "pipeline-events",
                "pipeline-output",
                "pipeline-runtime-api",
                "pipeline-scripting-api",
                "pipeline-sdk-bom",
            ),
            artifacts.toSet(),
            "unexpected artifacts: $artifacts",
        )
    }

    @Test
    fun `CorrelationId is a value class accessible from the SDK`() {
        // The consumer compiles against the SDK at -PsdkVersion=0.56.0.
        // If this test compiles, the SDK exposes CorrelationId.
        val id = dev.rubentxu.pipeline.v2.domain.CorrelationId(
            "00000000-0000-4000-8000-000000000001"
        )
        assertEquals("00000000-0000-4000-8000-000000000001", id.value)
    }

    @Test
    fun `RunCorrelated event is part of the SDK sealed hierarchy`() {
        // Reflection check: RunCorrelated must be a sealed subclass of
        // DomainEvent. If a future SDK release ever removes it, this
        // test will fail and force the consumer author to revalidate.
        val cls = Class.forName("dev.rubentxu.pipeline.v2.events.RunCorrelated")
        val domainEventCls = Class.forName("dev.rubentxu.pipeline.v2.events.DomainEvent")
        assertTrue(
            domainEventCls.isAssignableFrom(cls),
            "RunCorrelated must implement DomainEvent",
        )
    }

    @Test
    fun `pipeline run with --correlation-id emits a LaunchReceipt on stdout`() {
        // Use a tmpdir for control-root so the test is self-contained.
        val tmp = Files.createTempDirectory("pk1-consumer-test-")
        try {
            val script = Files.createFile(tmp.resolve("hello.pipeline.kts"))
            Files.writeString(
                script,
                """
                pipeline {
                    stages {
                        stage("echo") {
                            steps { echo("hello PK-1 consumer test") }
                        }
                    }
                }
                """.trimIndent(),
            )
            val correlation = "00000000-0000-4000-8000-${"%012d".format(System.currentTimeMillis() % 1_000_000_000_000)}"
            val args = arrayOf(
                "run",
                "--correlation-id", correlation,
                "--db", tmp.resolve("test.db").toString(),
                script.toString(),
            )
            val (exit, out, err) = run("pk1-idem", args)
            // The run may fail for plugin-range reasons; what matters
            // here is that the LaunchReceipt was emitted on stdout
            // BEFORE the failure (proving durable state was established).
            val receiptLine = out.lineSequence().firstOrNull { it.startsWith("{\"schema\":\"pipelinek.launch/1\"") }
            assertNotNull(receiptLine, "expected a LaunchReceipt line on stdout, got: stdout=$out\nstderr=$err")
            // Verify the correlationId is the one we passed in.
            assertTrue(
                receiptLine!!.contains("\"correlationId\":\"$correlation\""),
                "LaunchReceipt must echo the supplied correlationId; got: $receiptLine",
            )
            // The status must be Launched (refused is also acceptable
            // in admission, but the contract test focuses on the
            // schema and the correlationId binding).
            assertTrue(
                receiptLine.contains("\"status\":\"Launched\"") || receiptLine.contains("\"status\":\"Refused:"),
                "LaunchReceipt must carry a status; got: $receiptLine",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private object Files {
        fun createTempDirectory(prefix: String): Path = java.nio.file.Files.createTempDirectory(prefix)
        fun createFile(path: Path): Path = java.nio.file.Files.createFile(path)
        fun writeString(path: Path, content: String) {
            java.nio.file.Files.writeString(path, content)
        }
    }

    /**
     * Run the [binary] with the given [args], returning (exitCode, stdout, stderr).
     * Stdout is captured as a String for stable line-based parsing.
     */
    private fun run(@Suppress("UNUSED_PARAMETER") marker: String, args: Array<String>): Triple<Int, String, String> {
        val process = ProcessBuilder(binary, *args)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        return Triple(exit, out, err)
    }
}
