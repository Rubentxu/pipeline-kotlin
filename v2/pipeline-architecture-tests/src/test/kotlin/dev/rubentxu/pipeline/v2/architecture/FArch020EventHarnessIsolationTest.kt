package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * EVT-3 architecture fitness: the Event Harness is a READ-ONLY verifier over
 * ports/models. It must never reach coordinator mutation, journal authority,
 * step execution, or JDBC directly; nothing inward may depend on it.
 */
class FArch020EventHarnessIsolationTest {

    private fun v2Root(): Path = ScannerSupport.v2Root()

    private fun harnessSources(): List<Path> =
        v2Root().resolve("pipeline-event-harness")
            .walk().filter { it.toString().endsWith(".kt") && it.toString().contains("/src/main/") }
            .toList()

    @Test
    fun `harness never imports coordinator, journal authority, step execution or JDBC`() {
        val forbidden = listOf(
            "PipelineOrchestrator", "PipelineRun", "CanonicalDurableRunCoordinator",
            "OperationJournal", "ReplayCursorStore", "ProcessBuilder", "Runtime.getRuntime",
            "java.sql", "StepHandler", "StepRegistry", "CompiledPipeline",
        )
        val violations = mutableListOf<String>()
        for (f in harnessSources()) {
            val text = f.readText()
            for (needle in forbidden) {
                if (text.contains(needle)) violations += "${f.fileName}: $needle"
            }
        }
        assertTrue(violations.isEmpty(), "EventHarness isolation violations: $violations")
    }

    @Test
    fun `harness depends only inward (domain, events) plus snakeyaml`() {
        val build = v2Root().resolve("pipeline-event-harness/build.gradle.kts")
        assertTrue(build.exists(), "pipeline-event-harness module must exist")
        val text = build.readText()
        assertTrue(text.contains("""project(":pipeline-domain")"""), "harness must depend on pipeline-domain")
        assertTrue(text.contains("""project(":pipeline-events")"""), "harness must depend on pipeline-events")
        val projectDeps = Regex("""project\("([^"]+)"\)""").findAll(text).map { it.groupValues[1] }.toList()
        val allowed = setOf(":pipeline-domain", ":pipeline-events")
        val bad = projectDeps - allowed
        assertTrue(bad.isEmpty(), "harness must not depend on modules beyond domain/events: $bad")
    }

    @Test
    fun `nothing inward depends on the harness`() {
        // domain, events must not reference the harness; harness is a leaf consumed
        // only by application (CLI) and tests.
        for (module in listOf("pipeline-domain", "pipeline-events")) {
            val src = v2Root().resolve(module)
            if (!src.exists()) continue
            val hits = src.walk()
                .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".kts") }
                .filter { it.readText().contains("pipeline.v2.harness") || it.readText().contains("\":pipeline-event-harness\"") }
                .toList()
            assertTrue(hits.isEmpty(), "$module must not depend on the event harness: $hits")
        }
    }

    @Test
    fun `harness emits no DomainEvents (no verifier loops)`() {
        val violations = harnessSources()
            .filter { it.readText().contains(Regex("""EventSink\.append|sink\.append\(""")) }
            .map { it.fileName }
        assertTrue(violations.isEmpty(), "harness must not append events: $violations")
    }
}
