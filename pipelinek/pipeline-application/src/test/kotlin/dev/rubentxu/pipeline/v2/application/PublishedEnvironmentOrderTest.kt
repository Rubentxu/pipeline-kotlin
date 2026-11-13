package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * WHEN a read sees a declaration, as the catalogue PUBLISHES it.
 *
 * ## The guarantee under trial
 *
 * That the catalogue publishes the RULE of the order: a read sees the
 * variables declared before it, and the declaration of the pipeline reaches a
 * read inside a stage only when the block that declares it comes before the
 * `stages { }` block.
 *
 * ## Why the catalogue has to publish it
 *
 * Because the catalogue exists so that nobody discovers a fact by failing: a
 * read that answers `null` for a variable the author declared is a fact that
 * costs a debugging session, and the author who migrates reads the catalogue
 * and not the README. The rule was in the README and in the KDoc; it was not
 * in the list a migrating author reads.
 *
 * ## Why the marker is a BLOCK
 *
 * The reason names the `stages { }` block, whose POSITION decides: a rule
 * published without the block it talks about is a rule nobody can apply. The
 * marker is therefore a name of a block, not a phrase of prose.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the DSL behaves that way: that is a fact of the
 * DSL, and the row that pins it lives with the DSL.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublishedEnvironmentOrderTest {

    /**
     * The published reason names the block whose position decides the read.
     */
    @Test
    @DisplayName("the published reason names the block whose position decides the read")
    fun thePublishedReasonNamesTheBlockWhosePositionDecidesTheRead() {
        val reason = publishedRead().reason

        assertTrue(
            reason.contains(ORDER_MARKER),
            "the reason does not name the `stages { }` block, whose position decides whether a " +
                "read inside a stage sees the declaration of the pipeline: $reason",
        )
    }

    /**
     * The difference the catalogue publishes about what a script reads.
     */
    private fun publishedRead() = requireNotNull(
        StepCatalogue.divergences().singleOrNull { divergence -> divergence.name == READ },
    ) {
        "the catalogue publishes no difference about what a script reads: " +
            StepCatalogue.divergences().map { divergence -> divergence.name }
    }

    private companion object {
        /** The name the difference carries in the catalogue. */
        const val READ = "the environment a script reads"

        /** The block whose position decides the read, as the reason quotes it. */
        const val ORDER_MARKER = "`stages { }`"
    }
}
