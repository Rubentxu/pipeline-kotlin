package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `writeFile step compiles to core file writeFile with typed payload`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    writeFile("out.txt", "hello world", "utf-8")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "writeFile pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val step = body.steps.single() as OpaqueStepNode
        assertEquals("core.file.writeFile", step.pluginStepId.value)
        assertTrue(step.payload.encoded.contains("\"kind\":\"writeFile\""))
        assertTrue(step.payload.encoded.contains("\"file\":\"out.txt\""))
        assertTrue(step.payload.encoded.contains("\"text\":\"hello world\""))
        assertTrue(step.payload.encoded.contains("\"encoding\":\"utf-8\""))
    }

    @Test
    fun `catchError step compiles to emit-event enter marker + inner steps + shell + trigger marker`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError(buildResult = "FAILURE", stageResult = "FAILURE") {
                        sh("exit 1")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "catchError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val ids = body.steps.map { it.id.value }
        val plugins = body.steps.map { it.pluginStepId.value }

        // Must include the entry marker and trigger marker for CatchErrorTriggered
        assertTrue(ids.any { it.contains("catch-error-enter-") }, "Should have catch-error-enter marker")
        assertTrue(ids.any { it.contains("catch-error-trigger-") }, "Should have catch-error-trigger marker")
        assertTrue(plugins.any { it == "core.emit.event" }, "Should have emit.event markers")
        assertTrue(plugins.any { it == "core.sh" }, "Should have core.sh wrapper")
    }

    @Test
    fun `unstable step compiles to StageMarkedUnstable event + exit-0 shell`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    unstable("something went wrong")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "unstable pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val ids = body.steps.map { it.id.value }
        val plugins = body.steps.map { it.pluginStepId.value }

        assertEquals(2, body.steps.size, "unstable should produce exactly 2 nodes")
        assertTrue(ids.any { it.contains("unstable-") }, "Should have unstable marker node")
        assertTrue(ids.any { it.contains("unstable-exit-0-") }, "Should have exit-0 shell node")
        assertTrue(plugins.any { it == "core.emit.event" }, "Should emit StageMarkedUnstable event")
        assertTrue(plugins.any { it == "core.sh" }, "Should emit exit-0 shell")
    }

    @Test
    fun `warnError step compiles to CatchErrorTriggered with UNSTABLE buildResult`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    warnError("deprecation warning") {
                        sh("exit 1")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "warnError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val plugins = body.steps.map { it.pluginStepId.value }

        assertTrue(plugins.any { it == "core.emit.event" }, "Should have emit.event markers")
        assertTrue(plugins.any { it == "core.sh" }, "Should have core.sh wrapper")

        // The emit event payload for warnError should contain UNSTABLE
        val emitSteps = body.steps.filter { it.pluginStepId.value == "core.emit.event" }
        assertTrue(emitSteps.any { it.payload.encoded.contains("UNSTABLE") },
            "warnError should force UNSTABLE buildResult")
    }

    @Test
    fun `catchError inlines inner steps into single shell wrapper`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError {
                        echo("inner")
                        sh("echo hello")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "catchError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps

        // LFC1-007 remediation: catchError must emit exactly 3 nodes (enter marker,
        // single shell wrapper, trigger marker). The previous implementation emitted
        // inner steps as separate canonical nodes AND a shell wrapper containing the
        // same steps, double-running the inner block. That broke catchError semantics
        // (FIND-DV-DUPL-01).
        assertEquals(3, body.steps.size, "catchError must produce exactly 3 nodes")
        val plugins = body.steps.map { it.pluginStepId.value }
        assertEquals(listOf("core.emit.event", "core.sh", "core.emit.event"), plugins,
            "catchError must emit exactly: enter-marker + shell-wrapper + trigger-marker")

        // The inner step commands must be inlined into the shell wrapper's script body
        val shellNode = body.steps.single { it.pluginStepId.value == "core.sh" }
        val shellPayload = shellNode.payload.encoded
        assertTrue(shellPayload.contains("echo hello"),
            "Shell wrapper must contain inner sh() command. Payload: $shellPayload")
    }
}
