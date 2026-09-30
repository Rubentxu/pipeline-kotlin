package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * S2-B: `post {}` is REAL declarative surface now (was WU-RP-032/DSL-008
 * fail-closed rejection). The compiled StageSpec must carry every declared
 * condition with its steps, so the canonical coordinator can select finalizers
 * by the stage's real outcome. Silent omission would still be the lie the old
 * test guarded against; the guard is now that the data SURVIVES compilation.
 */
class PostDslCompilesToSpecTest {

    @Test
    fun `post block compiles to a StageSpec carrying every declared condition`() {
        val spec = assertDoesNotThrow {
            pipeline {
                stages {
                    stage("s") {
                        echo("hi")
                        post {
                            always { echo("cleanup") }
                            success { echo("on success") }
                            failure { echo("on failure") }
                        }
                    }
                }
            }
        }
        val stage = spec.stages.single { it.name == "s" }
        val post = stage.post
        assertNotNull(post, "post must survive compilation as data")

        val conditions = post.conditions.keys
        assertTrue(
            conditions.containsAll(
                setOf(
                    dev.rubentxu.pipeline.v2.domain.post.PostCondition.ALWAYS,
                    dev.rubentxu.pipeline.v2.domain.post.PostCondition.SUCCESS,
                    dev.rubentxu.pipeline.v2.domain.post.PostCondition.FAILURE,
                ),
            ),
            "every declared condition must be present: $conditions",
        )
        assertEquals(1, post.conditions[dev.rubentxu.pipeline.v2.domain.post.PostCondition.ALWAYS]?.size)
        assertEquals(1, post.conditions[dev.rubentxu.pipeline.v2.domain.post.PostCondition.SUCCESS]?.size)
    }

    @Test
    fun `a stage without post carries no post data`() {
        val spec = pipeline {
            stages {
                stage("s") { echo("hi") }
            }
        }
        val stage = spec.stages.single { it.name == "s" }
        assertTrue(stage.post == null || stage.post.conditions.isEmpty())
    }

    @Test
    fun `two blocks of the same condition accumulate in declaration order`() {
        val spec = pipeline {
            stages {
                stage("s") {
                    echo("hi")
                    post {
                        always { echo("first") }
                        always { echo("second") }
                    }
                }
            }
        }
        val post = spec.stages.single { it.name == "s" }.post
        assertNotNull(post)
        assertEquals(
            2,
            post.conditions[dev.rubentxu.pipeline.v2.domain.post.PostCondition.ALWAYS]?.size,
            "Jenkins allows several blocks of one condition; the spec must keep them in order",
        )
    }
}
