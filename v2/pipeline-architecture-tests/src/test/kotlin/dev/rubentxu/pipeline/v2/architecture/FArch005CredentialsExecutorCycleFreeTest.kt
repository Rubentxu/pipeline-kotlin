package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * FArch005: Credentials Executor Cycle-Free Test
 *
 * Design (design §5):
 *
 * Verifies that `:pipeline-credentials-executor` does NOT depend on forbidden modules:
 * - Layer 1 (text scan): build.gradle.kts does NOT contain forbidden project deps
 * - Layer 2 (source scan): source files do NOT import forbidden packages
 *
 * ## Forbidden modules (design E-9)
 *
 * Executor MUST NOT depend on:
 * - :pipeline-application
 * - :pipeline-credentials-local
 * - :pipeline-credentials-multipart
 * - :pipeline-binding-factory
 * - :pipeline-step-sdk:runtime
 * - :pipeline-step-sdk:scm-git
 * - :pipeline-step-sdk:files
 * - :pipeline-step-sdk:workflow-control
 * - :pipeline-scripting-kotlin24
 *
 * ## Allowed modules (design §5.3)
 *
 * Executor MAY depend on:
 * - :pipeline-domain
 * - :pipeline-events
 * - :pipeline-credentials-api
 * - :pipeline-scripting-api
 * - :pipeline-step-sdk:api
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FArch005CredentialsExecutorCycleFreeTest {

    private val forbiddenModules = setOf(
        ":pipeline-application",
        ":pipeline-credentials-local",
        ":pipeline-credentials-multipart",
        ":pipeline-binding-factory",
        ":pipeline-step-sdk:runtime",
        ":pipeline-step-sdk:scm-git",
        ":pipeline-step-sdk:files",
        ":pipeline-step-sdk:workflow-control",
        ":pipeline-scripting-kotlin24"
    )

    private val forbiddenImportPrefixes = listOf(
        "dev.rubentxu.pipeline.v2.application.",
        "dev.rubentxu.pipeline.v2.credentials.local.",
        "dev.rubentxu.pipeline.v2.credentials.multipart.",
        "dev.rubentxu.pipeline.v2.binding.",
        "dev.rubentxu.pipeline.v2.sdk.runtime.durable.",
        "dev.rubentxu.pipeline.v2.scripting.kotlin24."
    )

    @Test
    fun layer1BuildGradleHasNoForbiddenModuleDependencies() {
        val root = ScannerSupport.v2Root()
        val buildFile = root.resolve("pipeline-credentials-executor/build.gradle.kts")

        if (!buildFile.toFile().exists()) {
            // Module doesn't exist yet - skip
            return
        }

        val content = Files.readString(buildFile)

        for (forbidden in forbiddenModules) {
            assertFalse(
                content.contains("project(\"$forbidden\")"),
                "Executor build.gradle.kts must NOT contain project(\"$forbidden\")"
            )
        }
    }

    @Test
    fun layer2SourceFilesHaveNoForbiddenImportPrefixes() {
        val root = ScannerSupport.v2Root()
        val executorSourceDir = root.resolve("pipeline-credentials-executor/src/main/kotlin")

        if (!executorSourceDir.toFile().exists()) {
            // Module doesn't exist yet - skip
            return
        }

        val findings = ScannerSupport.findForbiddenImportPrefixes(
            executorSourceDir,
            forbiddenImportPrefixes
        )

        assertTrue(
            findings.isEmpty(),
            "Executor source files must NOT import forbidden packages: $findings"
        )
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `scanner detects forbidden build dependency`() {
            val fixture = tempDir.resolve("build.gradle.kts")
            fixture.toFile().writeText("""
                plugins { kotlin("jvm") }
                dependencies {
                    implementation(project(":pipeline-credentials-local"))
                }
            """.trimIndent())

            val content = Files.readString(fixture)
            val hasViolation = forbiddenModules.any { content.contains("project(\"$it\")") }

            assertTrue(hasViolation, "Scanner must detect forbidden project dependency in fixture")
        }
    }
}
