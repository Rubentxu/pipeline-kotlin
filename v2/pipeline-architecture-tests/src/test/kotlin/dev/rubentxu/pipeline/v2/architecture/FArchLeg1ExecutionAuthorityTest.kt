package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * LEG-1.4 fitness: production execution authority == `CanonicalDurableRunCoordinator` ONLY.
 *
 * Mechanical proof that the legacy second execution algorithm
 * (`PipelineRun` / `PipelineOrchestrator` / `walkPipelineSpecDurable`) cannot
 * come back (LEG-1 burn-down):
 *
 * 1. the legacy engine source files do not exist;
 * 2. no production source code references their symbols (comments excluded);
 * 3. Main routes durable execution ONLY through the canonical coordinator,
 *    keeping the fail-closed non-canonical exit;
 * 4. StepSpec appears in production only as structural IR (compiler lowering +
 *    structural traversal + typed credential projection) — never as a runtime
 *    command interpreter.
 *
 * NOTE: `StepExecutors.kt` SURVIVES by design: it holds ONLY the typed
 * primitives (echo/sh/error/sleep) consumed by `CoreEchoStep` and
 * `ShExecution` on the canonical path. The legacy ENGINE files are banned.
 */
class FArchLeg1ExecutionAuthorityTest {

    private val v2Root: Path = FitnessPaths.v2Root()

    private val moduleSrcRoots = listOf(
        "pipeline-application/src/main",
        "pipeline-domain/src/main",
        "pipeline-step-sdk/api/src/main",
        "pipeline-step-sdk/runtime/src/main",
        "pipeline-events/src/main",
        "pipeline-scripting-api/src/main",
    )

    /** Certified structural seams allowed to match on StepSpec (EP-F2.5 C1 rows). */
    private val structuralSeamFiles = setOf(
        "DslCompiledPipelineCompiler.kt",
        "BlockStepFlattener.kt",
        "PipelineDsl.kt",
        "CredentialProjection.kt",
        "CredentialBindingsPayload.kt",
    )

    private fun prodKotlinFiles(): List<Path> =
        moduleSrcRoots.flatMap { rel ->
            val dir = v2Root.resolve(rel)
            if (Files.exists(dir)) {
                Files.walk(dir).use { stream -> stream.filter { f -> f.toString().endsWith(".kt") }.toList() }
            } else {
                emptyList()
            }
        }

    private fun exists(rel: String): Boolean = Files.exists(v2Root.resolve(rel))

    private fun readIfExists(rel: String): String {
        val p = v2Root.resolve(rel)
        return if (Files.exists(p)) Files.readString(p) else ""
    }

    /** Strips comments so documentation mentions of deleted symbols don't count as code. */
    private fun codeOnly(source: String): String {
        var s = Regex("//[^\n]*").replace(source, "")
        s = Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(s, "")
        return s
    }

    @Test
    fun `legacy execution engine files are deleted`() {
        val deleted = listOf(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PipelineRun.kt",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/PipelineOrchestrator.kt",
            "pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/ParallelFrameExecutors.kt",
        )
        for (rel in deleted) {
            assertFalse(
                exists(rel),
                "$rel must not exist (LEG-1 burn-down): the legacy engine is deleted, not modernized",
            )
        }
        val survivors = codeOnly(
            readIfExists("pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/StepExecutors.kt"),
        )
        assertFalse(
            survivors.contains("class ParallelFrameExecutor"),
            "StepExecutors.kt must not host the legacy direct-exec switch (LEG-1.2/1.3)",
        )
        assertFalse(
            survivors.contains("runBranch"),
            "StepExecutors.kt must not host the legacy branch interpreter (LEG-1.2/1.3)",
        )
    }

    @Test
    fun `no production source code references the deleted engine symbols`() {
        val banned = listOf(
            Regex("""\bwalkPipelineSpecDurable\s*\("""),
            Regex("""\bexecuteDurableStep(Impl)?\s*\("""),
            Regex("""PipelineOrchestrator\s*\("""),
            Regex("""DurableWalkContext\s*\("""),
        )
        for (file in prodKotlinFiles()) {
            val source = codeOnly(Files.readString(file))
            for (pattern in banned) {
                assertFalse(
                    pattern.containsMatchIn(source),
                    "${file.fileName} must not reference deleted legacy engine symbol: $pattern",
                )
            }
        }
    }

    @Test
    fun `Main routes durable execution only through the canonical coordinator`() {
        val main = Files.readString(
            v2Root.resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt"),
        )
        assertTrue(
            main.contains("runCanonicalPipeline"),
            "Main must route through runCanonicalPipeline (canonical coordinator)",
        )
        assertTrue(
            main.contains("CanonicalDurableRunCoordinator"),
            "Main must construct CanonicalDurableRunCoordinator as the production execution authority",
        )
        assertTrue(
            main.contains("NON_CANONICAL_CANONICAL_BRIDGE_ERROR") || main.contains("System.exit(2)"),
            "Main must keep the fail-closed non-canonical exit (exit 2)",
        )
    }

    @Test
    fun `StepSpec in production is structural IR only - no runtime command interpretation`() {
        for (file in prodKotlinFiles()) {
            val source = codeOnly(Files.readString(file))
            val hasSpec = Regex("""\bis\s+StepSpec\.""").containsMatchIn(source)
            if (hasSpec) {
                assertTrue(
                    file.fileName.toString() in structuralSeamFiles,
                    "${file.fileName} interprets StepSpec at runtime but is not a certified structural seam",
                )
            }
        }
    }
}
