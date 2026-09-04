package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC0-008 / UAT-GOV-003.
 *
 * The V1 build line is quarantined: root settings excludes V1 projects, V2
 * settings excludes deferred protocol scope, documentation is consistent with
 * ADR-0064, and the UAT catalogue tracks all four governance contracts.
 */
class Lfc0V1QuarantineFitnessTest {

    private val v1Projects = listOf(
        ":core",
        ":pipeline-cli",
        ":pipeline-backend",
        ":pipeline-lsp-server",
        ":pipeline-steps-system",
        ":pipeline-steps-system:plugin-annotations",
        ":pipeline-steps-system:compiler-plugin",
        ":pipeline-steps-system:gradle-plugin"
    )

    @Test
    fun `root settings excludes V1 line projects`() {
        val settings = ScannerSupport.v2Root().resolve("..").resolve("settings.gradle.kts").toFile().readText()

        v1Projects.forEach { project ->
            assertFalse(
                settings.contains("\"$project\""),
                "V1 project '$project' must not be included in root settings"
            )
        }
    }

    @Test
    fun `V2 settings excludes pipeline-protocol`() {
        val v2Settings = ScannerSupport.v2Root().resolve("settings.gradle.kts").toFile().readText()

        assertFalse(
            v2Settings.contains("\":pipeline-protocol\""),
            "Deferred protocol project must not be included in V2 settings"
        )
    }

    @Test
    fun `UAT catalogue lists all four governance contracts`() {
        val cataloguePath = ScannerSupport.v2Root().resolve("..").resolve("docs/pipeline-kotlin-local-foundation-consolidation/docs/v2/06-uat/UAT_CATALOG.md")
        val catalogue = cataloguePath.toFile().readText()

        listOf("UAT-GOV-001", "UAT-GOV-002", "UAT-GOV-003", "UAT-GOV-004").forEach { id ->
            assertTrue(
                catalogue.contains(id),
                "UAT catalogue must contain '$id'"
            )
        }
    }

    @Test
    fun `root README names pipeline-kotlin and references the LFC roadmap`() {
        val readmePath = ScannerSupport.v2Root().resolve("..").resolve("README.md")
        val readme = readmePath.toFile().readText()

        assertTrue(
            readme.contains("pipeline-kotlin"),
            "Root README must mention 'pipeline-kotlin'"
        )

        val hasLink = readme.contains("docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md")
        val hasLfcName = readme.contains("LFC") || readme.contains("Local Foundation Consolidation")
        assertTrue(
            hasLink || hasLfcName,
            "Root README must link to LOCAL_FOUNDATION_CONSOLIDATION.md or name LFC/Local Foundation Consolidation"
        )
    }

    @Test
    fun `roadmap first heading references LFC and cites ADR-0064`() {
        val roadmapPath = ScannerSupport.v2Root().resolve("..").resolve("docs/v2/05-roadmap/LOCAL_FOUNDATION_CONSOLIDATION.md")
        val roadmap = roadmapPath.toFile().readText()
        val firstHeadingSection = roadmap.substringBefore("\n## ")

        assertTrue(
            firstHeadingSection.contains("LFC"),
            "Roadmap first heading must reference 'LFC'"
        )
        assertTrue(
            firstHeadingSection.contains("ADR-0064"),
            "Roadmap first heading must cite 'ADR-0064'"
        )
    }
}
