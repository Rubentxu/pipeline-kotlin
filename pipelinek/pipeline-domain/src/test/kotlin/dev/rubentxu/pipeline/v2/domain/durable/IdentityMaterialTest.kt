package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * The identity material is the text that reaches the digest of a durable
 * operation, with the facts of instant projected out.
 *
 * Two guarantees are worth pinning here, and they are of different kinds:
 *
 * - a payload that declares no fact of instant must reach the digest BYTE FOR
 *   BYTE, so the operations that carry no clock keep the identity they had;
 * - a payload that declares one must lose it, and lose it in a way that does
 *   not depend on how the declaration was spelled.
 *
 * Both fail silently if untrue: an identity that moves with the clock turns a
 * replayed program into a new operation, and nothing in the output says so.
 */
class IdentityMaterialTest {

    @Nested
    @DisplayName("a payload that declares no fact of instant is kept whole")
    inner class Whole {

        @Test
        fun `the identity text is the declared text, byte for byte`() {
            val declared = """{"kind":"sh","target":"prod"}"""
            val material = IdentityMaterial.of(declared)
            assertEquals(
                declared,
                material.identityText,
                "the declared text was rewritten for a payload that declares no fact of instant",
            )
            assertTrue(
                material.excludedFields.isEmpty(),
                "a whole material excludes nothing, got ${material.excludedFields}",
            )
        }

        @Test
        fun `a payload that is not a JSON object is kept whole`() {
            // A shell fragment, a plain word, a non-object JSON value: none of
            // them declares fields, so none of them can carry a fact of instant.
            val declared = "core.sh --target prod"
            val material = IdentityMaterial.of(declared)
            assertEquals(declared, material.identityText)
            assertTrue(material.excludedFields.isEmpty())
        }

        @Test
        fun `a payload that does not parse is kept whole`() {
            val declared = """{"kind": "sh", """
            val material = IdentityMaterial.of(declared)
            assertEquals(
                declared,
                material.identityText,
                "a text that does not parse was rewritten instead of being kept whole",
            )
            assertTrue(material.excludedFields.isEmpty())
        }

        @Test
        fun `a payload that parses into a JSON value that is not an object is kept whole`() {
            // The third text of the contract: one that parses, and parses into
            // something that is not an object. It declares no fields, so it
            // cannot carry a fact of instant — and the row that covers the
            // text which does not parse at all does not cover this one.
            val declared = """["kind","sh"]"""
            val material = IdentityMaterial.of(declared)
            assertEquals(
                declared,
                material.identityText,
                "a JSON value that is not an object was rewritten instead of being kept whole",
            )
            assertTrue(material.excludedFields.isEmpty())
        }
    }

    /**
     * The facts of instant are DECLARED, not guessed: a field is one because
     * the domain says so, and the declaration is the list the projection
     * intersects the payload with.
     */
    @Nested
    @DisplayName("the facts of instant are declared")
    inner class Declaration {

        @Test
        fun `the fact of instant that the projection excludes is among the declared ones`() {
            assertTrue(
                IdentityMaterial.instantFields.contains("enteredAt"),
                "the fact of instant the projection excludes is not among the declared ones: " +
                    "${IdentityMaterial.instantFields}",
            )
        }
    }

    @Nested
    @DisplayName("a payload that declares a fact of instant is projected")
    inner class Projected {

        @Test
        fun `the fact of instant does not reach the identity`() {
            val material = IdentityMaterial.of(
                """{"kind":"CatchErrorEntered","buildResult":"UNSTABLE","enteredAt":"1793356105290"}""",
            )
            assertEquals(
                setOf("enteredAt"),
                material.excludedFields,
                "the declared fact of instant was not the one excluded",
            )
            assertFalse(
                material.identityText.contains("enteredAt"),
                "the fact of instant reached the identity: ${material.identityText}",
            )
            assertTrue(
                material.identityText.contains("CatchErrorEntered"),
                "the projection dropped a declared field: ${material.identityText}",
            )
        }

        @Test
        fun `the projection does not depend on the order of the declared keys`() {
            val forward = IdentityMaterial.of(
                """{"kind":"sh","enteredAt":"1700000000123","target":"prod"}""",
            ).identityText
            val reversed = IdentityMaterial.of(
                """{"target":"prod","enteredAt":"1700000000123","kind":"sh"}""",
            ).identityText
            assertEquals(
                forward,
                reversed,
                "two spellings of the same declaration projected to two identities",
            )
        }
    }

    /**
     * The invariants of a projected material, exercised on the material itself.
     *
     * The projection is built by [IdentityMaterial.of], and that factory only
     * ever builds a material that satisfies these three rules — so the rules
     * were never observed doing their work. They are the invariants of a type
     * that a caller can build directly, and a guard that no row reaches is a
     * guard that nothing proves.
     */
    @Nested
    @DisplayName("a projected material satisfies its own invariants")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    inner class Invariants {

        @Test
        fun `a projection that excludes nothing is refused`() {
            // A material that excludes nothing is not a projection: it is a
            // whole material wearing the other name, and the two have to be
            // told apart by their own construction.
            val refusal = assertThrows<IllegalArgumentException> {
                IdentityMaterial.Projected("""{"kind":"sh"}""", emptySet())
            }
            assertTrue(
                refusal.message.orEmpty().contains("exclude"),
                "the refusal did not name the rule it is about: ${refusal.message}",
            )
        }

        @Test
        fun `a projection that excludes a field that is not a fact of instant is refused`() {
            // Only the DECLARED facts of instant may leave the identity. A
            // projection that excluded anything else would drop a fact the
            // program declared about itself.
            val refusal = assertThrows<IllegalArgumentException> {
                IdentityMaterial.Projected("""{"kind":"sh"}""", setOf("kind"))
            }
            assertTrue(
                refusal.message.orEmpty().contains("facts of instant"),
                "the refusal did not name the rule it is about: ${refusal.message}",
            )
        }

        @Test
        fun `a projection whose text still declares the excluded field is refused`() {
            // The whole point of the projection is that the fact of instant
            // does not reach the identity. A material that keeps the field it
            // says it excluded is an identity that moves with the clock.
            val refusal = assertThrows<IllegalArgumentException> {
                IdentityMaterial.Projected("""{"enteredAt":"1700000000123"}""", setOf("enteredAt"))
            }
            assertTrue(
                refusal.message.orEmpty().contains("still declares"),
                "the refusal did not name the rule it is about: ${refusal.message}",
            )
        }
    }

    @Nested
    @DisplayName("only the payload parameter is projected")
    inner class Projection {

        @Test
        fun `another parameter is left exactly as declared`() {
            val sandboxProfile = JsonPrimitive("""{"profile":"strict","enteredAt":"1700000000123"}""")
            val projected = IdentityMaterial.project(mapOf("sandboxProfile" to sandboxProfile))
            assertEquals(
                sandboxProfile,
                projected["sandboxProfile"],
                "a parameter that is not the payload was projected",
            )
        }

        @Test
        fun `the payload parameter is projected`() {
            val projected = IdentityMaterial.project(
                mapOf("payload" to JsonPrimitive("""{"kind":"sh","enteredAt":"1700000000123"}""")),
            )
            val text = (projected["payload"] as JsonPrimitive).content
            assertFalse(text.contains("enteredAt"), "the fact of instant reached the identity: $text")
        }

        @Test
        fun `a payload that is not a string is left alone`() {
            val payload = JsonObject(mapOf("kind" to JsonPrimitive("sh")))
            val projected = IdentityMaterial.project(mapOf("payload" to payload))
            assertEquals(payload, projected["payload"], "a payload that is not a string was rewritten")
        }
    }
}
