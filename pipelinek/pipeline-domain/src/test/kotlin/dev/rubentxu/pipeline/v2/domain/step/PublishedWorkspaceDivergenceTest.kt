package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * What the catalogue publishes about the workspace of a stage.
 *
 * ## The guarantee under trial
 *
 * That the published text says what the product DOES with the workspace of a
 * stage: that the stages of a run SHARE one by default, that the run which
 * gives every stage its own is the one that asks for it, and that the stash
 * and the unstash exist in both products.
 *
 * ## Why this is a row and not a review
 *
 * Because the reader who migrates a Jenkinsfile asks exactly this question
 * before anything else: does the second stage see what the first one wrote?
 * The text said the opposite of what the product does, and a pipeline written
 * against that text behaves differently from the one the reader had.
 *
 * ## What this instrument does not freeze
 *
 * The wording. The row asks for the FACTS the text has to carry — what the
 * default is, which run isolates, and who owns the stash — and not for a
 * phrasing.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublishedWorkspaceDivergenceTest {

    /**
     * The published divergence of the workspace says that the stages share it
     * by default, and names the run that gives each stage its own.
     */
    @Test
    @DisplayName("the published divergence of the workspace says that the stages share it by default")
    fun thePublishedDivergenceOfTheWorkspaceSaysThatTheStagesShareItByDefault() {
        val reason = reasonOf(DIVERGENCE)

        assertTrue(
            reason.contains("by default"),
            "the published divergence does not say what the DEFAULT is: $reason",
        )
        assertTrue(
            reason.contains(SHARES, ignoreCase = true),
            "the published divergence does not say that the stages share the workspace: $reason",
        )
        assertTrue(
            reason.contains(ISOLATING_FLAG),
            "the published divergence does not name the run that gives each stage its own workspace: " +
                reason,
        )
        assertFalse(
            reason.contains(OLD_CLAIM),
            "the published divergence still says that every stage runs in its own workspace: $reason",
        )
        assertFalse(
            reason.contains(JENKINS_ONLY),
            "the published divergence still credits the stash and the unstash to Jenkins alone: $reason",
        )
    }

    /**
     * The reason the catalogue publishes for the divergence of the workspace.
     */
    private fun reasonOf(name: String): String =
        requireNotNull(JenkinsDivergences.global().firstOrNull { it.name == name }) {
            "the catalogue publishes no divergence named '$name'"
        }.reason

    private companion object {
        /** The divergence that publishes what the workspace of a stage is. */
        const val DIVERGENCE = "the workspace of a stage"

        /** The word that says that the stages share the workspace. */
        const val SHARES = "share"

        /** The flag of the run that gives each stage a workspace of its own. */
        const val ISOLATING_FLAG = "--isolated"

        /** The claim the text must not carry any more: it is the opposite of what the code does. */
        const val OLD_CLAIM = "Every stage runs in its OWN workspace"

        /** The attribution the text must not carry any more: the stash exists here too. */
        const val JENKINS_ONLY = "exist there"
    }
}
