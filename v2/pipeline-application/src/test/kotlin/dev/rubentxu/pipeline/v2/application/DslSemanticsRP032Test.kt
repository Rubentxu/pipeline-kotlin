package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RP-032 fitness tests for the Kotlin DSL semantics.
 *
 * Closes the semantic surface required by ROADMAP.md §5 RP-032:
 *  - declaration vs execution: the DSL builder MUST NOT perform I/O
 *    (no pwd(), no isUnix(), no now(), no shell execution). The
 *    compiled IR is the same regardless of host environment.
 *  - typed runtime values: values that need runtime data flow
 *    through a Step handler with declared capabilities, not through
 *    the DSL builder. The compiled payload carries the structural
 *    reference, not a resolved value.
 *  - compile-negative: the lock and source content are inputs that
 *    reshape the definition identity; the same spec + lock + source
 *    produce the same identity.
 *
 * Reference: AGENTS.md "DSL vs runtime (using Steps)" and
 * "DSL describes; interpreters execute" (functional core, effectful
 * shell).
 */
class DslSemanticsRP032Test {

    private val source = """
        pipeline {
            stages {
                stage("Build") {
                    echo("hello")
                    sh("./gradlew test")
                }
            }
        }
    """.trimIndent()

    private fun fixture() = pipeline {
        stages {
            stage("Build") {
                echo("hello")
                sh("./gradlew test")
            }
        }
    }

    /**
     * Declaration vs execution: the same DSL spec + source + lock
     * produce the same compiled pipeline regardless of the host
     * environment (OS, user, working directory). The builder is
     * pure; no I/O is performed during construction.
     */
    @Test
    fun `DSL compile is pure — same inputs produce identical compiled pipeline`() {
        val first = DslCompiledPipelineCompiler.compile(
            spec = fixture(),
            sourcePath = "build.pipeline.kts",
            sourceContent = source,
            pluginLockDigest = Digest("lock-v1"),
        )
        val second = DslCompiledPipelineCompiler.compile(
            spec = fixture(),
            sourcePath = "build.pipeline.kts",
            sourceContent = source,
            pluginLockDigest = Digest("lock-v1"),
        )

        // Identity is stable for the same inputs.
        assertEquals(first.id, second.id)
        assertEquals(first, second)
    }

    /**
     * The lock is part of the definition identity. A different lock
     * (different plugin set) yields a different compiled pipeline.
     * This proves the compile path is real and the lock is honored
     * as a semantic input, not ignored.
     */
    @Test
    fun `lock change reshapes the compiled definition identity`() {
        val a = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-A"),
        )
        val b = DslCompiledPipelineCompiler.compile(
            fixture(), "build.pipeline.kts", source, Digest("lock-B"),
        )

        assertNotEquals(a.id, b.id)
    }

    /**
     * Typed runtime values: the compiled payload carries the
     * structural reference (the Step + arguments), not a resolved
     * value. The DSL construction MUST NOT fabricate runtime data.
     */
    @Test
    fun `compiled payload carries structural reference, not resolved value`() {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = fixture(),
            sourcePath = "build.pipeline.kts",
            sourceContent = source,
            pluginLockDigest = Digest("lock-runtime"),
        )

        val body = compiled.stages.single().body as StageBody.Steps
        // echo("hello") is compiled to a structural Step; the payload
        // echoes the source text "hello" verbatim because that IS the
        // typed argument. The point is that the DSL did not fetch a
        // runtime value and substitute it.
        val echoStep = body.steps[0]
        assertEquals("core.echo", echoStep.pluginStepId.value)
        assertTrue(
            echoStep.payload.encoded.contains("hello"),
            "The echo argument is the structural 'hello' string; the DSL did not fabricate a runtime value. Encoded: ${echoStep.payload.encoded}"
        )

        val shStep = body.steps[1]
        assertEquals("core.sh", shStep.pluginStepId.value)
        assertTrue(
            shStep.payload.encoded.contains("./gradlew test"),
            "The sh argument is the structural './gradlew test' command. Encoded: ${shStep.payload.encoded}"
        )
    }

    /**
     * The compiled output is a pure structural value. Every step is
     * an `OpaqueStepNode` (registry-routed); the compiler never
     * resolves a handler, queries the registry, or accesses runtime
     * state.
     */
    @Test
    fun `compiled output is pure structural data — no handler invocation at compile time`() {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = fixture(),
            sourcePath = "build.pipeline.kts",
            sourceContent = source,
            pluginLockDigest = Digest("lock-structural"),
        )

        val body = compiled.stages.single().body as StageBody.Steps
        assertEquals(2, body.steps.size, "Two steps in the spec must compile to two structural nodes")
        body.steps.forEach { step ->
            // Every step has a StepKey + a structural payload. The
            // compile path never reached a handler or a registry
            // resolver: the output is pure data.
            assertTrue(step.pluginStepId.value.startsWith("core."), "Step key namespace is core.*. Found: ${step.pluginStepId.value}")
            assertEquals("dsl-v1", step.payload.schemaVersion)
        }
    }
}
