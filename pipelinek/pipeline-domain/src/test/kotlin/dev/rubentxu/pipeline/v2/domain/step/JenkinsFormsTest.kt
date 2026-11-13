package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The table of the forms of Jenkins is the only place where the suggestion is
 * written.
 *
 * ## The guarantee
 *
 * The compiler prints a suggestion and the catalogue publishes the form it
 * comes from, and both read this table. A shape the recognition can name has
 * exactly one form, and every form says what it is, what replaces it, why the
 * two differ and what the compiler prints.
 *
 * ## Why each row is here
 *
 * - The table and the vocabulary of the shapes are two authorities of the same
 *   fact unless a row says they agree: a shape with no form would produce no
 *   suggestion, and a form with no shape would publish a row nobody can ask
 *   for.
 * - A form that does not say why the two differ is a form that names a
 *   difference the reader has to discover by failing.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class JenkinsFormsTest {

    @Test
    fun `the table carries one form per shape, and no shape twice`() {
        val forms = JenkinsForms.global()

        assertEquals(
            JenkinsFormShape.entries.toSet(),
            forms.map { form -> form.shape }.toSet(),
            "the table carries a form for every shape the recognition can name",
        )
        assertEquals(
            forms.size,
            forms.map { form -> form.shape }.toSet().size,
            "a shape is described once: two forms of the same shape could say different things",
        )
    }

    @Test
    fun `every form says what it is, what replaces it, why and what the compiler prints`() {
        JenkinsForms.global().forEach { form ->
            assertTrue(
                form.jenkinsForm.isNotBlank(),
                "the form ${form.shape} does not say how Jenkins writes it",
            )
            assertTrue(
                form.pipelineKForm.isNotBlank(),
                "the form ${form.shape} does not say how this product writes it",
            )
            assertTrue(
                form.reason.isNotBlank(),
                "the form ${form.shape} does not say why the two differ",
            )
            assertTrue(
                form.suggestion.isNotBlank(),
                "the form ${form.shape} does not say what the compiler prints",
            )
        }
    }

    @Test
    fun `the form of a shape is the one the table declares`() {
        JenkinsFormShape.entries.forEach { shape ->
            assertNotNull(
                JenkinsForms.of(shape),
                "the shape $shape has no form, so the recognition would find nothing to say",
            )
            assertEquals(
                shape,
                JenkinsForms.of(shape)?.shape,
                "the form of $shape is not the one the table declares",
            )
        }
    }

    @Test
    fun `the template of a form names the value the suggestion carries`() {
        val forms = JenkinsForms.global()

        assertTrue(
            forms.all { form -> form.suggestion.contains(JenkinsForms.VALUE_PLACEHOLDER) },
            "a form whose suggestion names no value says nothing the author can write",
        )
    }
}
