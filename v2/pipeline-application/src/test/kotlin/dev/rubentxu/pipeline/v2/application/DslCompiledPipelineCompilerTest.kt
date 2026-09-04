package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DslCompiledPipelineCompilerTest {

    private val source = """
        pipeline {
            stages {
                stage("Build") {
                    agent("linux")
                    environment { env("CI", "true") }
                    options { timeout(30) }
                    echo("hello")
                    sh("./gradlew test")
                }
            }
        }
    """.trimIndent()

    private fun fixture() = pipeline {
        stages {
            stage("Build") {
                agent("linux")
                environment { env("CI", "true") }
                options { timeout(30) }
                echo("hello")
                sh("./gradlew test")
            }
        }
    }

    @Test
    fun `DSL fixture compiles directly to canonical IR`() {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = fixture(),
            sourcePath = "build.pipeline.kts",
            sourceContent = source,
            pluginLockDigest = Digest("lock-v1"),
        )

        assertEquals("build.pipeline.kts", compiled.source.path)
        assertEquals("linux", compiled.stages.single().agent?.label)
        assertEquals(mapOf("CI" to "true"), compiled.stages.single().environment.values)
        assertEquals(listOf("timeout=30"), compiled.stages.single().options.map { "${it.name}=${it.value}" })

        val body = compiled.stages.single().body as StageBody.Steps
        assertEquals(listOf("build/echo-0", "build/sh-0"), body.steps.map { it.id.value })
        assertEquals(listOf("core.echo", "core.sh"), body.steps.map { it.pluginStepId.value })
        assertTrue(body.steps.all { it.payload.schemaVersion == "dsl-v1" })
        assertTrue(body.steps.first().payload.encoded.contains("hello"))
        assertTrue(body.steps[1].payload.encoded.contains("./gradlew test"))
    }

    @Test
    fun `same source and lock produce the same definition and source identity`() {
        val first = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )
        val second = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )

        assertEquals(first, second)
        assertTrue(first.id.value.isNotBlank())
    }

    @Test
    fun `changing the plugin lock changes the definition identity`() {
        val first = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )
        val second = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v2"),
        )

        assertTrue(first.id != second.id)
    }

    @Test
    fun `adding a different step does not renumber existing step identities`() {
        val baseline = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-v1"),
        )
        val changed = DslCompiledPipelineCompiler.compile(
            pipeline {
                stages {
                    stage("Build") {
                        echo("hello")
                        error("diagnostic")
                        sh("./gradlew test")
                    }
                }
            },
            "build.pipeline.kts",
            source,
            Digest("lock-v1"),
        )

        val baselineIds = (baseline.stages.single().body as StageBody.Steps).steps.map { it.id.value }
        val changedIds = (changed.stages.single().body as StageBody.Steps).steps.map { it.id.value }
        assertEquals(listOf("build/echo-0", "build/sh-0"), baselineIds)
        assertEquals(listOf("build/echo-0", "build/error-0", "build/sh-0"), changedIds)
    }
}
