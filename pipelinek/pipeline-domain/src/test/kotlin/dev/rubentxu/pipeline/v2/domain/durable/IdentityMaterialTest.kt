package dev.rubentxu.pipeline.v2.domain.durable

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
