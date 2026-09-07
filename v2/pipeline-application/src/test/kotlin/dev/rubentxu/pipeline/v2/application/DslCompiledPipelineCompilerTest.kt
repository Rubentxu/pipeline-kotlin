package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `warnError compiles to typed UNSTABLE catchError metadata and StageMarkedUnstable`() {
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

        // warnError is catchError(UNSTABLE) and must visibly mark the stage unstable.
        val emitSteps = body.steps.filter { it.pluginStepId.value == "core.emit.event" }
        assertTrue(emitSteps.any { it.payload.encoded.contains("\"stageResult\":\"UNSTABLE\"") },
            "warnError should preserve an unquoted UNSTABLE stage result")
        assertTrue(emitSteps.any { it.payload.encoded.contains("\"kind\":\"StageMarkedUnstable\"") },
            "warnError should emit StageMarkedUnstable after closing catchError scope")
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

    @Test
    fun `catchError lifts inner unstable after its trigger while retaining the shell wrapper`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError(buildResult = "FAILURE") {
                        unstable("inner unstable")
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "build.pipeline.kts",
            "catchError unstable pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val plugins = body.steps.map { it.pluginStepId.value }
        val triggerIndex = body.steps.indexOfFirst {
            it.id.value.contains("catch-error-trigger-")
        }
        val unstableIndex = body.steps.indexOfFirst {
            it.payload.encoded.contains("\"kind\":\"StageMarkedUnstable\"")
        }

        assertEquals(
            listOf("core.emit.event", "core.sh", "core.emit.event", "core.emit.event", "core.sh"),
            plugins,
        )
        assertTrue(triggerIndex >= 0, "catchError must retain its trigger marker")
        assertTrue(unstableIndex > triggerIndex, "inner unstable must follow CatchErrorTriggered")
        assertTrue(
            (body.steps[1] as OpaqueStepNode).payload.encoded.contains("set +e"),
            "catchError must retain an empty shell wrapper for its heredoc",
        )
    }

    @Test
    fun `nested catchError inside warnError compiles inside the parent scope without shell comments`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    warnError("outer section") {
                        catchError {
                            sh("exit 1")
                        }
                    }
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "nested-catch.pipeline.kts",
            "nested catchError pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val plugins = body.steps.map { it.pluginStepId.value }

        // parent enter, nested enter, nested shell, nested trigger, parent trigger, StageMarkedUnstable
        assertEquals(
            listOf(
                "core.emit.event",
                "core.emit.event",
                "core.sh",
                "core.emit.event",
                "core.emit.event",
                "core.emit.event",
            ),
            plugins,
            "Nested catch groups must be rewritten inside the parent scope, not compiled into its heredoc",
        )
        body.steps.forEach { step ->
            assertFalse(
                step.payload.encoded.contains("legacy step"),
                "No workflow-control child may compile into a shell comment: ${step.payload.encoded}",
            )
        }
        val shells = body.steps.filter { it.pluginStepId.value == "core.sh" }
        assertEquals(1, shells.size, "Only the nested catch body shell may exist")
        assertTrue(shells.single().payload.encoded.contains("exit 1"))

        // The nested scope must sit between the parent enter and trigger markers so it
        // inherits the parent overlay while it is active.
        val parentEnter = body.steps.indexOfFirst { it.id.value.contains("warn-error-enter-") }
        val nestedEnter = body.steps.indexOfFirst { it.id.value.contains("catch-error-enter-") }
        val nestedTrigger = body.steps.indexOfFirst { it.id.value.contains("catch-error-trigger-") }
        val parentTrigger = body.steps.indexOfFirst { it.id.value.contains("warn-error-trigger-") }
        assertTrue(parentEnter in 0..nestedEnter, "Parent enter must precede the nested scope")
        assertTrue(nestedEnter in 0..nestedTrigger, "Nested enter must precede its trigger")
        assertTrue(nestedTrigger < parentTrigger, "Nested scope must close before the parent scope closes")
    }

    @Test
    fun `catchError with a non-embeddable inner step fails compilation instead of a shell comment`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    catchError {
                        sleep(2)
                    }
                }
            }
        }
        val error = assertThrows(IllegalStateException::class.java) {
            DslCompiledPipelineCompiler.compile(
                spec,
                "loud-fallback.pipeline.kts",
                "loud fallback pipeline",
                Digest("lock-v1"),
            )
        }
        assertTrue(
            error.message!!.contains("sleep"),
            "The typed compile error must name the offending step kind: ${error.message}",
        )
        assertFalse(
            error.message!!.contains("legacy step"),
            "The compiler must never fall back to a silent shell comment: ${error.message}",
        )
    }

    @Test
    fun `milestone step compiles to canonical core milestone payload with ordinal and label`() {
        val spec = pipeline {
            stages {
                stage("Build") {
                    milestone(ordinal = 1, label = "post-error")
                }
            }
        }
        val compiled = DslCompiledPipelineCompiler.compile(
            spec,
            "milestone.pipeline.kts",
            "milestone pipeline",
            Digest("lock-v1"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        val step = body.steps.single() as OpaqueStepNode
        assertEquals("core.milestone", step.pluginStepId.value)
        assertTrue(step.payload.encoded.contains("\"kind\":\"milestone\""))
        assertTrue(step.payload.encoded.contains("\"ordinal\":1"))
        assertTrue(step.payload.encoded.contains("\"label\":\"post-error\""))
    }
}
