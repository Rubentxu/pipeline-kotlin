package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * PipelineProtocolBoundaryTest — F-ARCH-013
 *
 * Verifies the protocol module boundary: no transport-layer dependencies leak into
 * the schema-declaration-only module (E5-01 scope).  Implements D7 from the M4-R1
 * cycle design at the canonical path.
 *
 * Three functional legs:
 *  1. Module dependency guard (no application/scripting/testkit)
 *  2. Proto schema surface (all 8 files exist, required options declared)
 *  3. Transport-neutral import scan (src/main/kotlin AND src/test/kotlin)
 *
 * Three synthetic violation fixtures:
 *  - Module-dep scanner
 *  - Proto-options scanner
 *  - Import-prefix effectiveness (11 real-world imports, all must be found)
 */
class PipelineProtocolBoundaryTest {

    private val forbiddenDependencyModules = setOf(
        ":pipeline-application",
        ":pipeline-scripting-kotlin24",
        ":pipeline-testkit"
    )

    private val topicProtoFiles = listOf(
        "worker_hello.proto",
        "negotiated_session.proto",
        "commands.proto",
        "events.proto",
        "ack_replay.proto",
        "leases.proto",
        "heartbeat.proto"
    )

    private val requiredProtoFiles = topicProtoFiles + "common.proto"

    /**
     * Forbidden transport prefixes.
     * Covers:
     *  - java.net.Socket, java.net.ServerSocket (exact, JDK network primitives)
     *  - java.net.http (JDK 11+ HTTP client — package exists in JDK, not a class)
     *  - okhttp3 / okhttp (versioned root okhttp3, unversioned okhttp token)
     *  - io.ktor.client / io.ktor.server (Ktor client and server)
     *  - io.grpc (gRPC managed channel)
     *  - javax.websocket / jakarta.websocket (JSR-356 WebSocket)
     *  - org.java_websocket (Java-WebSocket library)
     *  - org.springframework.web.socket (Spring WebSocket)
     *
     *  Note: java.net.WebSocket does NOT exist in the JDK. The correct package
     *  is java.net.http.WebSocket.  This was the root cause of the prior
     *  token-list miss (okhttp token cannot match okhttp3. prefix).
     */
    private val forbiddenTransportPrefixes = listOf(
        "java.net.Socket",
        "java.net.ServerSocket",
        "java.net.http",
        "okhttp3",
        "okhttp",
        "io.ktor.client",
        "io.ktor.server",
        "io.grpc",
        "javax.websocket",
        "jakarta.websocket",
        "org.java_websocket",
        "org.springframework.web.socket"
    )

    // ──────────────────────────────────────────────────────────────────────
    // Leg 1 — Module dependency guard
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `protocol module depends only on allowed modules`() {
        val root = ScannerSupport.v2Root()
        val protocolBuildFile = root.resolve("pipeline-protocol/build.gradle.kts")

        val findings = if (protocolBuildFile.toFile().exists()) {
            val unallowedPattern = Regex("""implementation\s*\(\s*project\(["']([^"']+)["']\s*\)""")
            val lines = protocolBuildFile.toFile().readLines()
            lines.mapIndexedNotNull { idx, line ->
                val match = unallowedPattern.find(line.trim())
                if (match != null) {
                    val module = match.groupValues[1]
                    if (module in forbiddenDependencyModules) {
                        Finding(protocolBuildFile, idx + 1, module, line)
                    } else null
                } else null
            }
        } else {
            emptyList()
        }

        assertTrue(findings.isEmpty(), "Protocol module must not depend on forbidden modules: $findings")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Leg 2 — Proto schema surface
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `all required proto schema files exist`() {
        val root = ScannerSupport.v2Root()
        val protoDir = root.resolve("pipeline-protocol/src/main/proto")

        val missing = requiredProtoFiles.filter { file ->
            !protoDir.resolve(file).toFile().exists()
        }

        assertTrue(missing.isEmpty(), "Required proto files must exist: $missing")
    }

    @Test
    fun `each proto file declares required package and options`() {
        val root = ScannerSupport.v2Root()
        val protoDir = root.resolve("pipeline-protocol/src/main/proto")

        val violations = mutableListOf<String>()

        for (fileName in requiredProtoFiles) {
            val file = protoDir.resolve(fileName)
            if (!file.toFile().exists()) continue

            val content = file.toFile().readText()

            if (!content.contains("""option java_package = "dev.rubentxu.pipeline.v2.protocol";""")) {
                violations.add("$fileName: missing java_package option")
            }
            if (!content.contains("option java_multiple_files = true;")) {
                violations.add("$fileName: missing java_multiple_files option")
            }
            if (!content.contains("syntax = \"proto3\";")) {
                violations.add("$fileName: missing syntax declaration")
            }
        }

        assertTrue(violations.isEmpty(), "Proto files must have required package/options: $violations")
    }

    @Test
    fun `each topic proto file has a corresponding golden fixture and test`() {
        val root = ScannerSupport.v2Root()
        val fixtureDir = root.resolve("pipeline-protocol/src/test/resources/fixtures")
        val testDir = root.resolve("pipeline-protocol/src/test/kotlin/dev/rubentxu/pipeline/v2/protocol")

        val missingCoverage = mutableListOf<String>()

        for (protoFile in topicProtoFiles) {
            val topicName = protoFile.removeSuffix(".proto")
            val pbFixture = fixtureDir.resolve("$topicName.pb")
            val pbtxtFixture = fixtureDir.resolve("$topicName.pbtxt")

            if (!pbFixture.toFile().exists()) {
                missingCoverage.add("$protoFile: missing $topicName.pb fixture")
            }
            if (!pbtxtFixture.toFile().exists()) {
                missingCoverage.add("$protoFile: missing $topicName.pbtxt fixture")
            }
        }

        assertTrue(
            missingCoverage.isEmpty(),
            "All topic proto files must have golden fixtures: $missingCoverage"
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Leg 3 — Transport-neutral import scan (main + test sources)
    // Scans BOTH src/main/kotlin (production) and src/test/kotlin to enforce
    // transport neutrality beyond the design minimum (which only required test).
    // Uses prefix-matching to catch versioned roots (okhttp3) and packages
    // (java.net.http) that exact-token matching misses.
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `no forbidden transport imports in protocol sources`() {
        val root = ScannerSupport.v2Root()
        val protocolMainDir = root.resolve("pipeline-protocol/src/main/kotlin")
        val protocolTestDir = root.resolve("pipeline-protocol/src/test/kotlin")

        val mainFindings = if (protocolMainDir.toFile().exists()) {
            ScannerSupport.findForbiddenImportPrefixes(protocolMainDir, forbiddenTransportPrefixes)
        } else {
            emptyList()
        }

        val testFindings = if (protocolTestDir.toFile().exists()) {
            ScannerSupport.findForbiddenImportPrefixes(protocolTestDir, forbiddenTransportPrefixes)
        } else {
            emptyList()
        }

        val allFindings = mainFindings + testFindings
        assertTrue(
            allFindings.isEmpty(),
            "Protocol sources must not import forbidden transport prefixes: $allFindings"
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Synthetic violation fixtures
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class ImportViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        /**
         * Effectiveness test — not a presence test.
         * Writes a fixture containing ALL 11 representative real-world imports
         * and asserts that every single one is detected by findForbiddenImportPrefixes.
         * This is the decisive difference from the prior fake test that only checked
         * for ANY finding.
         *
         * Note: java.net.http appears on 2 lines (WebSocket + HttpClient) and
         * okhttp3 appears on 2 lines (OkHttpClient + WebSocket), so the total
         * finding count is 12 (not 11) since each line produces a separate finding.
         */
        @Test
        fun `scanner detects all 11 real-world forbidden imports`() {
            val fixture = tempDir.resolve("RealImports.kt")
            fixture.toFile().writeText("""
                package test.fixture

                import java.net.Socket
                import java.net.ServerSocket
                import java.net.http.WebSocket
                import java.net.http.HttpClient
                import okhttp.OkHttpClient
                import okhttp3.OkHttpClient
                import okhttp3.WebSocket
                import io.ktor.client.HttpClient
                import io.ktor.server.Server
                import io.grpc.ManagedChannel
                import javax.websocket.WebSocketContainer
                import jakarta.websocket.Session
                import org.java_websocket.WebSocket
                import org.springframework.web.socket.WebSocketHandler
            """.trimIndent())

            val root = tempDir
            val findings = ScannerSupport.findForbiddenImportPrefixes(root, forbiddenTransportPrefixes)

            // All 12 prefixes must be found (14 total findings: java.net.http appears twice, okhttp3 appears twice)
            val foundPrefixes = findings.map { it.token }.toSet()
            for (prefix in forbiddenTransportPrefixes) {
                assertTrue(
                    foundPrefixes.contains(prefix),
                    "Prefix '$prefix' was NOT detected — scanner missed it. Found: ${findings.map { it.token }}"
                )
            }
            assertEquals(14, findings.size, "Expected 14 findings (12 prefixes, java.net.http x2, okhttp3 x2)")
        }
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects forbidden module dependency in fixture`() {
            val fixture = tempDir.resolve("build.gradle.kts")
            fixture.toFile().writeText("""
                dependencies {
                    implementation(project(":pipeline-application"))
                }
            """.trimIndent())

            val unallowedPattern = Regex("""implementation\s*\(\s*project\(["']([^"']+)["']\s*\)""")
            val lines = fixture.toFile().readLines()
            val findings = lines.mapIndexedNotNull { idx, line ->
                val match = unallowedPattern.find(line.trim())
                if (match != null) {
                    val module = match.groupValues[1]
                    if (module in setOf(":pipeline-application")) {
                        Finding(fixture, idx + 1, module, line)
                    } else null
                } else null
            }

            assertTrue(findings.isNotEmpty(), "Scanner must detect forbidden module dependency in fixture")
            val finding = findings.first()
            assertEquals(":pipeline-application", finding.token)
        }
    }

    @Nested
    inner class ProtoViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner rejects proto file missing required package option`() {
            val fixture = tempDir.resolve("bad_schema.proto")
            fixture.toFile().writeText("""
                syntax = "proto3";

                package dev.rubentxu.pipeline.v2.protocol;

                // Missing java_package and java_multiple_files options
                message BadMessage {
                    string name = 1;
                }
            """.trimIndent())

            val content = fixture.toFile().readText()
            val hasRequiredOptions = content.contains("""option java_package = "dev.rubentxu.pipeline.v2.protocol";""") &&
                content.contains("option java_multiple_files = true;")

            assertTrue(!hasRequiredOptions, "Fixture must lack required options for test validity")
        }
    }
}
