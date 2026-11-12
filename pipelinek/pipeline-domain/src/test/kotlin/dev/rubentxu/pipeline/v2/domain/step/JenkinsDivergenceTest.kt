package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The divergences that are the same for every script.
 *
 * ## The guarantee under trial
 *
 * That the product says out loud the differences a reader coming from Jenkins
 * is going to meet, and that it tells apart the two kinds of difference: the
 * ones the LANGUAGE imposes and the ones the PRODUCT decides.
 *
 * ## Why this instrument
 *
 * These facts live in code and not in a document, so the cheapest instrument
 * that can fail is the code itself: the declarations are read and compared with
 * the differences of each kind that the card names, and with the one the map
 * adds. A divergence that loses its motive, or that changes kind, fails here
 * and not in a reader's terminal.
 *
 * ## What is NOT under trial
 *
 * The divergence of a SINGLE entry: that one lives in the entry, and the
 * catalogue publishes it. What is under trial here is the section the catalogue
 * carries: the differences that are the same for every script.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JenkinsDivergenceTest {

    @Test
    fun `the four differences the language imposes are declared`() {
        val language = divergencesOf(JenkinsDivergence.Scope.LANGUAGE)

        assertEquals(
            listOf(
                "when is a reserved word",
                "the command of a shell step",
                "the named arguments of a step",
                "the dollar of a string",
            ),
            language.map { it.name },
            "the language imposes four differences, and they are the four the card names",
        )
    }

    @Test
    fun `the differences the product decides are declared`() {
        val product = divergencesOf(JenkinsDivergence.Scope.PRODUCT)

        assertEquals(
            listOf(
                "the workspace of a stage",
                "the control flow of the body of a script",
                "the gate compares against what is declared",
                "the predicate of the branch",
                "the environment a script reads",
            ),
            product.map { it.name },
            "the product decides five differences: the four the card names and the one the " +
                "map adds, the environment a script reads",
        )
    }

    @Test
    fun `every divergence says why the two products differ`() {
        val blank = JenkinsDivergences.global().filter { it.reason.isBlank() }

        assertEquals(
            emptyList<String>(),
            blank.map { it.name },
            "a divergence without a motive is a difference the reader has to discover by failing",
        )
    }

    @Test
    fun `the catalogue publishes the global divergences`() {
        val published = StepCatalogue.divergences()

        assertEquals(
            JenkinsDivergences.global(),
            published,
            "the catalogue publishes the divergences that are declared, and not a copy of them",
        )
    }

    /**
     * The divergences of one kind, in the order they are declared.
     */
    private fun divergencesOf(scope: JenkinsDivergence.Scope): List<JenkinsDivergence> =
        JenkinsDivergences.global().filter { it.scope == scope }
}
