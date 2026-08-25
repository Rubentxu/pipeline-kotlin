package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FArch012ProtocolModuleStructureTest {

    private val forbiddenDependencyModules = setOf(
        ":pipeline-application",
        ":pipeline-scripting-kotlin24",
        ":pipeline-testkit"
    )

    private val requiredProtoFiles = listOf(
        "worker_hello.proto",
        "negotiated_session.proto",
        "commands.proto",
        "events.proto",
        "ack_replay.proto",
        "leases.proto",
        "heartbeat.proto",
        "common.proto"
    )

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
