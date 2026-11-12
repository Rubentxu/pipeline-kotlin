package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The rows the domain declares and the runtime registers, and the facts they
 * both carry.
 *
 * ## The guarantee under trial
 *
 * That a key declared in the domain and registered in the runtime does not have
 * TWO authorities saying different things about its equivalent in Jenkins.
 *
 * ## Why the guarantee matters
 *
 * The domain declares a fallback row for the keys the runtime may not register:
 * a composition without the runtime falls back to it, and the catalogue
 * publishes THAT row. The runtime registers its own descriptor for the same
 * key, and the catalogue publishes THAT one. Two rows, one key, and two places
 * where the equivalent in Jenkins can be written — so the facts have to be
 * checked against each other, or the fallback drifts away from the descriptor
 * that runs the step.
 *
 * ## What this instrument does NOT do
 *
 * It does not decide which of the two is right: it says that they AGREE. The
 * fact itself is declared once, in the row that owns it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FallbackRowJenkinsFactsTest {

    @Test
    fun `the fallback row of a key the runtime registers declares the same Jenkins facts`() {
        val registry = CoreStepRegistryFactory.registry()
        val declared = StepDescriptorRegistry.standard()

        val shadowed = declared.keys().filter { key -> registry.definition(key) != null }
        assertEquals(
            emptyList<String>(),
            shadowed.filter { key ->
                val fallback = declared.get(key)
                val registered = registry.definition(key)?.contract?.descriptor
                fallback?.jenkinsSurface != registered?.jenkinsSurface ||
                    fallback?.jenkinsDivergence != registered?.jenkinsDivergence
            }.map { it.value },
            "a key the domain declares and the runtime registers has ONE answer about Jenkins",
        )
    }
}
