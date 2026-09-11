package dev.rubentxu.pipeline.v2.application.scripted

import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * LFC-2R / R4B fitness — installed production wiring.
 *
 * F1. Main selects the frontend FORM (PipelineSpec vs compiled scripted entry
 *     point) but never an execution authority and never a concrete Step: the
 *     durable backend stays the canonical coordinator/registry on every path.
 * F2. No second-runner switch: Main must not `when` full pipelines to
 *     ScriptedArtifactRuntime.execute (R4A: SECOND_RUNNER = REJECTED); the
 *     scripted entry point is executed through the same journal/registry
 *     composition via ScriptedFrontendRunner.
 */
class R4BProductionWiringFitnessTest {

    private val mainSource: String by lazy {
        val candidates = listOf(
            "src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: generateSequence(File(".").absoluteFile) { it.parentFile }
                .map { File(it, candidates.first()) }
                .firstOrNull { it.exists() }
            ?: error("Main.kt not found")
        file.readText()
    }

    @Test
    fun `F1 - Main frontend selection is not step-key routed`() {
        val commentsStripped = mainSource
            .split('\n')
            .filter { !it.trimStart().startsWith("//") }
            .joinToString("\n")
        // Forbidden: dispatching on concrete Step keys in Main's wiring.
        val forbidden = listOf(
            "core.isUnix\"",
            "\"core.echo\"",
            "\"core.sh\"",
        )
        for (token in forbidden) {
            assertTrue(
                !commentsStripped.contains(token),
                "Main.kt must not reference concrete Step key $token in production wiring",
            )
        }
    }

    @Test
    fun `F2 - no second-runner switch to ScriptedArtifactRuntime in Main`() {
        val commentsStripped = mainSource
            .split('\n')
            .filter { !it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            !commentsStripped.contains("ScriptedArtifactRuntime"),
            "Main.kt must not route execution through ScriptedArtifactRuntime (SECOND_RUNNER = REJECTED); " +
                "the scripted frontend must share the canonical journal/registry via ScriptedFrontendRunner",
        )
        assertTrue(
            commentsStripped.contains("ScriptedFrontendRunner"),
            "Main.kt must wire the scripted frontend through ScriptedFrontendRunner",
        )
    }
}
