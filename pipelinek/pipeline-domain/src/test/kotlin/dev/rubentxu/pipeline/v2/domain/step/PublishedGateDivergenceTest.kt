package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What the catalogue publishes about the gate, and about the branch.
 *
 * ## The guarantee under trial
 *
 * That the published text says what the product DOES with a gate whose
 * variable nobody declares, and that it does not promise a branch that the
 * product does not have.
 *
 * ## Why this is a row and not a review
 *
 * Because the reader who migrates a Jenkinsfile reads THIS text and writes
 * the gate from it. A text that says the condition is written "with the
 * variable of the branch" sends the reader to a variable the product does not
 * declare: the gate can never be satisfied, the stage never runs, and the run
 * reports success. A text that says what the gate does with a variable nobody
 * declares does not need the reader to guess.
 *
 * ## What this instrument does not freeze
 *
 * The wording. The row asks for the FACTS the text has to carry — that the
 * product has no branch of its own, that the variable is one the script
 * declares, and that a variable nobody declares leaves the stage out — and not
 * for a phrasing.
 */
class PublishedGateDivergenceTest {

    /**
     * The published divergence of the gate says what happens to a stage whose
     * gate names a variable nobody declares.
     */
    @Test
    @DisplayName("the published divergence of the gate says what a variable nobody declares does")
    fun thePublishedDivergenceOfTheGateSaysWhatAVariableNobodyDeclaresDoes() {
        val reason = reasonOf(GATE_DIVERGENCE)

        assertTrue(
            reason.contains("the stage does not run"),
            "the published divergence does not say that the stage does not run: $reason",
        )
    }

    /**
     * The published divergence of the branch says that the product has no
     * branch of its own, and that the variable is one the script declares.
     */
    @Test
    @DisplayName("the published divergence of the branch says that the product has no branch of its own")
    fun thePublishedDivergenceOfTheBranchSaysThatTheProductHasNoBranchOfItsOwn() {
        val reason = reasonOf(BRANCH_DIVERGENCE)

        assertTrue(
            reason.contains("no branch of its own"),
            "the published divergence does not say that the product has no branch of its own: $reason",
        )
        assertTrue(
            reason.contains("the SCRIPT declares"),
            "the published divergence does not say that the variable is one the script declares: $reason",
        )
        assertFalse(
            reason.contains("the variable of the branch"),
            "the published divergence still sends the reader to a branch variable the product does not " +
                "declare: $reason",
        )
    }

    /**
     * The reason the catalogue publishes for the divergence named [name].
     */
    private fun reasonOf(name: String): String =
        requireNotNull(JenkinsDivergences.global().firstOrNull { it.name == name }) {
            "the catalogue publishes no divergence named '$name'"
        }.reason

    private companion object {
        /** The divergence that publishes what a gate compares against. */
        const val GATE_DIVERGENCE = "the gate compares against what is declared"

        /** The divergence that publishes what a branch condition is written with. */
        const val BRANCH_DIVERGENCE = "the predicate of the branch"
    }
}
