package dev.rubentxu.pipeline.v2.domain.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * H2b — the composition rules for runtime capabilities.
 *
 * Three properties, each of which was either violated by the shape this
 * replaced or is what makes the replacement sound:
 *
 * 1. **PREPARE and EXECUTE observe the same set.** The contributor takes no
 *    context, so this is true by construction rather than by discipline: a
 *    context-aware contributor could answer differently on the second call and a
 *    Step would pass admission and then find its capability missing.
 * 2. **A collision is a defect.** First-wins and last-wins both let a plugin
 *    silently shadow a core seam, which is the failure this whole
 *    OFFICIAL_PLUGIN direction exists to make impossible.
 * 3. **Contributors are opaque.** A contributor says which capability it
 *    supplies; nothing about it requires the core to know the protocol it serves.
 */
class CompositeCapabilityContributorTest {

    private val transportKey = StepCapability("test.transport")
    private val secondKey = StepCapability("test.second")

    @Test
    fun `the same answer is produced on every call, with no context to vary it`() {
        var calls = 0
        val contributor = RuntimeCapabilityContributor {
            calls++
            mapOf(transportKey to "value")
        }

        val composite = CompositeCapabilityContributor(listOf(contributor))

        assertEquals(composite.capabilities(), composite.capabilities())
        assertEquals(2, calls, "each consultation is an explicit call, and both must agree")
    }

    @Test
    fun `contributions from several contributors are unioned`() {
        val composite = CompositeCapabilityContributor(
            listOf(
                RuntimeCapabilityContributor { mapOf(transportKey to "a") },
                RuntimeCapabilityContributor { mapOf(secondKey to "b") },
            ),
        )
        val capabilities = composite.capabilities()
        assertEquals(setOf(transportKey, secondKey), capabilities.keys)
    }

    @Test
    fun `a collision fails closed and names both contributors`() {
        val first = object : RuntimeCapabilityContributor {
            override fun capabilities() = mapOf(transportKey to "first")
        }
        val second = object : RuntimeCapabilityContributor {
            override fun capabilities() = mapOf(transportKey to "second")
        }

        val failure = runCatching {
            CompositeCapabilityContributor(listOf(first, second)).capabilities()
        }.exceptionOrNull()

        assertTrue(
            failure is IllegalArgumentException,
            "a duplicate capability must be refused; got $failure",
        )
        val message = failure?.message.orEmpty()
        assertTrue(
            message.contains("test.transport"),
            "the diagnostic must name the colliding capability; got: $message",
        )
        assertTrue(
            message.contains("defect, not a precedence rule"),
            "the diagnostic must say WHY, so a reader does not 'fix' it with first-wins; got: $message",
        )
    }

    @Test
    fun `an empty composition is legal and yields no capabilities`() {
        // A run that bundles no plugin is not an error; it is a run whose Steps
        // declaring plugin capabilities are rejected at admission.
        assertEquals(emptyMap<StepCapability, Any>(), CompositeCapabilityContributor(emptyList()).capabilities())
    }

    @Test
    fun `the contributed value is the very instance, not a copy`() {
        // A capability IS the collaborator. Wrapping or re-creating it would make
        // the identity a Step holds meaningless.
        val instance = Any()
        val composite = CompositeCapabilityContributor(
            listOf(RuntimeCapabilityContributor { mapOf(transportKey to instance) }),
        )
        assertSame(instance, composite.capabilities()[transportKey])
    }
}
