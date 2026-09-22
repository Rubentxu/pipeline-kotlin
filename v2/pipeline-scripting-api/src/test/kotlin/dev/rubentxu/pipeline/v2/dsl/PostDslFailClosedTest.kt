package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * WU-RP-032 / UAT_DSL_EXECUTION_HARDENING DSL-008: `post {}` is accepted DSL surface
 * whose semantics are NOT implemented in the compiled execution path. The fallback
 * MUST be fail-closed rejection at compile time, never silent omission (no fake
 * fallback): a script declaring post conditions that would never run is a lie.
 */
class PostDslFailClosedTest {

    @Test
    fun `post block is rejected fail-closed with localized diagnostic`() {
        val ex = assertThrows<IllegalStateException> {
            pipeline {
                stages {
                    stage("s") {
                        echo("hi")
                        post {
                            always { echo("cleanup") }
                        }
                    }
                }
            }
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("post") && msg.contains("not supported"),
            "diagnostic must name 'post' and state non-support, got: $msg",
        )
    }

    @Test
    fun `stage without post compiles unchanged`() {
        assertDoesNotThrow {
            pipeline {
                stages {
                    stage("s") { echo("hi") }
                }
            }
        }
    }
}
