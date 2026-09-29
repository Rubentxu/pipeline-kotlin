package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.StageDirective
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * S1-B — the `directives { }` DSL block lowers to declarative IR data and
 * nothing else.
 *
 * Contract under test:
 * - each `directive(key, args)` call becomes one StageDirective carrier, in
 *   declaration order;
 * - construction performs NO registry lookup and NO decoding (the arguments
 *   reach the IR opaquely, whatever their content);
 * - a blank key is rejected at construction (fail closed at the carrier).
 */
class DirectivesDslLoweringTest {

    @Test
    fun `the directives block lowers to ordered StageDirective carriers`() {
        val spec = pipeline {
            stages {
                stage("deploy") {
                    directives {
                        directive("acme.lock", """{"resource":"prod"}""")
                        directive("acme.notify")
                    }
                    sh("echo deploying")
                }
            }
        }

        val stage = spec.stages.single()
        assertEquals(
            listOf(
                StageDirective("acme.lock", """{"resource":"prod"}"""),
                StageDirective("acme.notify", "{}"),
            ),
            stage.directives,
            "each declaration lowers to one carrier, in order, arguments opaque",
        )
        assertEquals(1, stage.steps.size, "directives do not consume the step list")
    }

    @Test
    fun `a stage without the directives block carries no directives`() {
        val spec = pipeline {
            stages {
                stage("build") {
                    echo("hello")
                }
            }
        }

        assertTrue(
            spec.stages.single().directives.isEmpty(),
            "absence of the block must be indistinguishable from an empty block",
        )
    }

    @Test
    fun `a blank directive key is rejected at construction time`() {
        val error = assertThrows<IllegalArgumentException> {
            pipeline {
                stages {
                    stage("build") {
                        directives {
                            directive("   ")
                        }
                    }
                }
            }
        }

        assertTrue(
            error.message!!.contains("blank"),
            "the rejection must name the rule, got '${error.message}'",
        )
    }

    @Test
    fun `directive arguments are carried opaquely, never validated by the DSL`() {
        // Garbage payload on purpose: the DSL is not the codec. Only the
        // definition that owns the key may reject or decode arguments.
        val spec = pipeline {
            stages {
                stage("build") {
                    directives {
                        directive("acme.strict", "this is not even json")
                    }
                }
            }
        }

        assertEquals(
            listOf(StageDirective("acme.strict", "this is not even json")),
            spec.stages.single().directives,
            "the DSL must not know the codec of any directive",
        )
    }
}
