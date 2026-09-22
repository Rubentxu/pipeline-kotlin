package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.dsl.OptionsScope
import dev.rubentxu.pipeline.v2.dsl.OptionsSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-RP-032 / DSL-008: stage `options` carries ONLY the options with a runtime
 * interpreter. Stage-level `retry`/`skip` were removed from the surface (unrepresentable)
 * rather than accepted-and-dropped (fake fallback ban). Retry semantics live in the
 * retry Block Step (durable control row); timeout projects via projectShellOptions.
 */
class UatDsl008StageOptionsFailClosedTest {

    @Test
    fun `OptionsScope has no retry or skip member`() {
        val fields = OptionsScope::class.java.declaredFields.map { it.name }
        assertTrue("timeout" in fields, "timeout must remain: $fields")
        assertTrue("retry" !in fields, "stage retry removed (use retry Block Step): $fields")
        assertTrue("skip" !in fields, "stage skip removed: $fields")
    }

    @Test
    fun `OptionsSpec has no skip boolean field`() {
        val fields = OptionsSpec::class.java.declaredFields.map { it.name }.filter { it != "Companion" && !it.startsWith("\$") }
        assertTrue("timeout" in fields, "timeout must remain: $fields")
        assertTrue("skip" !in fields, "skip must be gone: $fields")
        assertTrue("retry" !in fields, "retry must be gone: $fields")
    }

    @Test
    fun `stage with timeout-only options compiles`() {
        assertDoesNotThrow {
            pipeline {
                stages {
                    stage("s") {
                        options { timeout(30) }
                        echo("hi")
                    }
                }
            }
        }
    }
}
