package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * LFC0-003 / UAT-GOV-002.
 *
 * The local-first V2 build must not include or depend on deferred
 * protocol/controller scope. The source directory may remain on disk as a
 * quarantine artifact; this test protects the active Gradle build only.
 */
class Lfc0ProtocolScopeFitnessTest {

    private val protocolProject = ":pipeline-protocol"

    @Test
    fun `active V2 settings exclude the deferred protocol project`() {
        val settings = ScannerSupport.v2Root().resolve("settings.gradle.kts").toFile().readText()

        assertFalse(
            settings.contains("\"$protocolProject\""),
            "Deferred protocol project must not be included in the active V2 build"
        )
    }

    @Test
    fun `active V2 build files do not depend on the deferred protocol project`() {
        val v2Root = ScannerSupport.v2Root()
        val dependencyReference = "project(\"$protocolProject\")"
        val findings = Files.walk(v2Root).use { paths ->
            paths
                .filter { path -> path.name == "build.gradle.kts" && path.extension == "kts" }
                .filter { path -> path.toFile().readText().contains(dependencyReference) }
                .map { it.toString() }
                .toList()
        }

        assertTrue(findings.isEmpty(), "Active V2 build files must not depend on protocol: $findings")
    }
}
