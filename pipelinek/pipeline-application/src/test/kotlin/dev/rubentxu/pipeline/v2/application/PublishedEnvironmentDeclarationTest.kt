package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import dev.rubentxu.pipeline.v2.dsl.EnvironmentScope
import dev.rubentxu.pipeline.v2.dsl.PipelineEnvironmentScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * How a variable is DECLARED, as the catalogue publishes it.
 *
 * ## The guarantee under trial
 *
 * That the catalogue publishes the difference an author who comes from Jenkins
 * meets when it DECLARES a variable —the assignment of Jenkins does not compile
 * here, and this product declares with a call— and that the form the catalogue
 * publishes is a form the DSL really takes.
 *
 * ## Why the second row asks the PRODUCT and not the text
 *
 * Because a published form that the DSL does not take is a promise the product
 * breaks, and a promise in a catalogue is worse than no promise: the author
 * writes it and it fails. So the row does not compare the published text with
 * a hand-written copy of it: it READS the declaration out of the published
 * reason and DECLARES it with the DSL, at both of the levels where Jenkins
 * allows an `environment { }` block.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the compiler refuses the assignment of Jenkins: that
 * is a fact of the COMPILER, and the row that pins it lives with the compiler.
 * This file affirms what the catalogue SAYS, and that what it says is a form
 * the product has.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublishedEnvironmentDeclarationTest {

    /**
     * The catalogue publishes the difference that says how a variable is declared.
     *
     * The three facts a migrating author needs: the form they are going to
     * write, the form this product takes instead, and what the compiler says
     * to the first one.
     */
    @Test
    @DisplayName("the catalogue publishes the difference that says how a variable is declared")
    fun theCataloguePublishesTheDifferenceThatSaysHowAVariableIsDeclared() {
        val reason = publishedDeclaration().reason

        assertTrue(
            reason.contains(JENKINS_FORM),
            "the reason names the form of Jenkins, so the author recognises the form they " +
                "wrote. The reason was $reason",
        )
        assertTrue(
            reason.contains(PIPELINEK_FORM),
            "the reason names the form of this product that replaces it. The reason was $reason",
        )
        assertTrue(
            reason.contains(REFUSAL),
            "the reason says what the compiler answers to the assignment of Jenkins, so the " +
                "reader knows what to expect. The reason was $reason",
        )
    }

    /**
     * The form the catalogue publishes is a form the DSL has.
     *
     * The declaration is READ from the published reason and DECLARED with the
     * DSL, at the two levels where Jenkins allows an `environment { }` block.
     */
    @Test
    @DisplayName("the form the catalogue publishes is a form the DSL has")
    fun theFormTheCataloguePublishesIsAFormTheDslHas() {
        val reason = publishedDeclaration().reason
        val call = requireNotNull(DECLARATION_CALL.find(reason)) {
            "the reason does not name a declaration the DSL can take: $reason"
        }
        val name = call.groupValues[1]
        val value = call.groupValues[2]

        assertEquals(
            mapOf(name to value),
            EnvironmentScope().apply { env(name, value) }.build(),
            "the scope of a stage declares the variable the published form names",
        )
        assertEquals(
            mapOf(name to value),
            PipelineEnvironmentScope().apply { env(name, value) }.build(),
            "the scope of the pipeline declares the variable the published form names",
        )
    }

    /**
     * The difference the catalogue publishes about the declaration of a variable.
     */
    private fun publishedDeclaration() = requireNotNull(
        StepCatalogue.divergences().singleOrNull { divergence -> divergence.name == DECLARATION },
    ) {
        "the catalogue publishes no difference about the declaration of a variable: " +
            StepCatalogue.divergences().map { divergence -> divergence.name }
    }

    private companion object {
        /** The name the difference carries in the catalogue. */
        const val DECLARATION = "the declaration of a variable"

        /** How Jenkins declares a variable. */
        const val JENKINS_FORM = "FOO = 'x'"

        /** How this product declares a variable. */
        const val PIPELINEK_FORM = "env(\"FOO\", \"x\")"

        /** What the compiler answers to the assignment of Jenkins. */
        const val REFUSAL = "Unresolved reference"

        /**
         * The declaration the published reason names.
         *
         * It is read out of the TEXT and not written down here: a row that
         * carried its own copy of the declaration would compare the text with
         * itself and would pass with a form the DSL does not take.
         */
        val DECLARATION_CALL = Regex("""env\("([^"]+)", "([^"]+)"\)""")
    }
}
