package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import dev.rubentxu.pipeline.v2.dsl.StageScope
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WHEN the control flow of a body decides, as the catalogue publishes it.
 *
 * ## The guarantee under trial
 *
 * That the reason the catalogue publishes names every block whose body is
 * evaluated while the PLAN is built, and that every block it names is a block
 * the DSL really has.
 *
 * ## Why the reader of a `steps { }` block was being misled
 *
 * The reason used to name the block `script { }` alone, and it ended saying
 * that "the Groovy of a Jenkinsfile runs during the run". A reader who writes
 * the block a Jenkinsfile writes — `steps { }` — read that sentence about his
 * own block and concluded that his `if` decides DURING THE RUN. In this
 * product it decides while the plan is built, before the first step runs.
 * A catalogue that leaves a reader with the wrong conclusion is worse than one
 * that says nothing, because the reader acts on it.
 *
 * ## Why the instrument asks the PRODUCT
 *
 * Because the fact is a fact about TWO artifacts at once: the text the
 * catalogue publishes and the surface the DSL has. A row that compared the
 * text with a hand-written copy of the block names would pass with a block the
 * DSL does not have, and it would keep passing the day the block changes its
 * name. So the block is looked up in the DSL, and the names are read out of
 * the published reason.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm WHEN the body is evaluated: that is a fact of the
 * runtime, and the row that pins it is the one that runs a pipeline. This file
 * affirms that the catalogue SAYS it, and that what it says names blocks the
 * product has.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublishedControlFlowReasonTest {

    /**
     * The reason that says when the control flow of a body decides.
     */
    @Test
    @DisplayName("the published reason names the block a Jenkinsfile writes")
    fun thePublishedReasonNamesTheBlockAJenkinsfileWrites() {
        val reason = publishedControlFlow().reason

        assertTrue(
            reason.contains(JENKINS_BLOCK),
            "the reason does not name the block `steps { }` that a Jenkinsfile writes, so the " +
                "reader of that block cannot tell when his `if` decides. The reason was $reason",
        )
        assertNotNull(
            blockInTheDsl(BLOCK_NAME),
            "the reason names a `$BLOCK_NAME { }` block that the DSL does not have",
        )
    }

    /**
     * Every block the reason names is a block the DSL has.
     *
     * The names are READ out of the published reason, and not written down
     * here: a row that carried its own list of blocks would compare the text
     * with itself, and it would pass with a block the DSL lost.
     */
    @Test
    @DisplayName("every block the published reason names is a block the DSL has")
    fun everyBlockThePublishedReasonNamesIsABlockTheDslHas() {
        val reason = publishedControlFlow().reason
        val named = BLOCK.findAll(reason).map { it.groupValues[1] }.distinct().toList()

        assertTrue(
            named.isNotEmpty(),
            "the reason names no block at all, so this rule proves nothing",
        )
        named.forEach { name ->
            assertNotNull(
                blockInTheDsl(name),
                "the reason names a `$name { }` block that the DSL does not have: $reason",
            )
        }
    }

    /**
     * The difference the catalogue publishes about the control flow.
     */
    private fun publishedControlFlow() = requireNotNull(
        StepCatalogue.divergences().singleOrNull { divergence -> divergence.name == CONTROL_FLOW },
    ) {
        "the catalogue publishes no difference about the control flow of a body: " +
            StepCatalogue.divergences().map { divergence -> divergence.name }
    }

    /**
     * The block of the DSL named [name], or null when the DSL has no such block.
     *
     * A block is a function of the stage scope that takes a block of code: the
     * `script { }` of the DSL and the `steps { }` of Jenkins are both of them.
     * The accessor that hands the steps of a scope over is not a block, and it
     * is told apart by the number of arguments it takes.
     */
    private fun blockInTheDsl(name: String): java.lang.reflect.Method? =
        StageScope::class.java.methods.firstOrNull { method ->
            method.name == name && method.parameterCount == 1
        }

    private companion object {
        /** The name the difference carries in the catalogue. */
        const val CONTROL_FLOW = "the control flow of the body of a script"

        /** The block a Jenkinsfile writes, as the reason has to name it. */
        const val JENKINS_BLOCK = "`steps { }`"

        /** The name of that block, without the marks that quote it. */
        const val BLOCK_NAME = "steps"

        /**
         * A block named in the published reason, with the name it carries.
         */
        val BLOCK = Regex("""`(\w+) \{ \}`""")
    }
}
