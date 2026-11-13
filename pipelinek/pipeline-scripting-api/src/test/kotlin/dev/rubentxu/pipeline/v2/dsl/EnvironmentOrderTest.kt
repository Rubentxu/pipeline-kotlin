package dev.rubentxu.pipeline.v2.dsl

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WHEN a read sees a declaration, as the DSL decides it.
 *
 * ## The guarantee under trial
 *
 * That a read sees the variables declared BEFORE it, and that the declaration
 * of the PIPELINE reaches a read inside a stage only when the block that
 * declares it is written before the `stages { }` block.
 *
 * ## Why the order decides this
 *
 * Because a read happens WHILE THE PLAN IS BUILT, and the plan is built while
 * the block that declares the variables runs: a declaration written after the
 * read has not happened yet when the read happens. The variables still REACH
 * the steps either way —that is the other view, the one the steps RUN WITH—,
 * and the two views answer different questions.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the catalogue publishes this rule: that is a fact
 * about the published text, and the row that pins it lives with the
 * publication.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EnvironmentOrderTest {

    /**
     * A read after the declaration sees it.
     */
    @Test
    @DisplayName("a read after the declaration sees the variable")
    fun aReadAfterTheDeclarationSeesTheVariable() {
        var seen: String? = null

        pipeline {
            stages {
                stage("s") {
                    environment { env(PROBE, "late") }
                    seen = env(PROBE)
                }
            }
        }

        assertEquals(
            "late",
            seen,
            "the read that comes after the declaration sees the variable the declaration declares",
        )
    }

    /**
     * A read before the declaration sees nothing.
     *
     * The row that says the rule has an ORDER: without it, the row above
     * would pass with a DSL that reads everything at the end.
     */
    @Test
    @DisplayName("a read before the declaration sees nothing")
    fun aReadBeforeTheDeclarationSeesNothing() {
        var seen: String? = "unset"

        pipeline {
            stages {
                stage("s") {
                    seen = env(PROBE)
                    environment { env(PROBE, "late") }
                }
            }
        }

        assertNull(
            seen,
            "the read that comes before the declaration sees nothing: the declaration has not " +
                "happened yet when the read happens",
        )
    }

    /**
     * The declaration of the pipeline reaches a read inside a stage only when
     * it is written before the `stages { }` block.
     */
    @Test
    @DisplayName("the declaration of the pipeline reaches a read inside a stage only when it comes first")
    fun theDeclarationOfThePipelineReachesAReadInsideAStageOnlyWhenItComesFirst() {
        var seen: String? = "unset"

        pipeline {
            stages {
                stage("s") {
                    seen = env(PROBE)
                }
            }
            environment { env(PROBE, "after") }
        }

        assertNull(
            seen,
            "the declaration written after the stages block has not happened when the read inside " +
                "the stage happens",
        )
    }

    private companion object {
        /**
         * The name of the variable the rows declare.
         *
         * It is a name nobody has: a row that reads a variable the PROCESS
         * happens to carry would measure the process and not the DSL.
         */
        const val PROBE = "PIPELINEK_ORDER_PROBE"
    }
}
