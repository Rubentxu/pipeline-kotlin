package dev.rubentxu.pipeline.v2.application.durable.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CertificateBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.FileBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.SshUserPrivateKeyBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPasswordBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePasswordBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.ZipBindingSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * ASX-000 — characterization baseline for the credential-binding node payload.
 *
 * ## The production authority this crosses
 *
 * [CredentialBindingsPayload] itself: its `encode` is the single producer of the `withCredentials`
 * node payload, and `decode` is the single consumer that the coordinator uses. There is no adapter
 * in between and none is simulated here; this class calls the same object production calls.
 *
 * ## What it pins, and why the exact bytes are the point
 *
 * The encoded JSON is the **payload of the compiled `withCredentials` node**, which means it is
 * fingerprint material: two runs whose payloads differ are two different operations to the durable
 * engine. A migration that reorders a field, renames a key or changes a kind discriminator would
 * therefore change the identity of every existing `withCredentials` operation in every existing
 * journal, and would do so **silently**, because until this class existed nothing asserted the
 * bytes. Tests called `encode(...)` and carried the result into the coordinator, which proves the
 * codec works and proves nothing about what it produces.
 *
 * So the rows below assert the literal string per binding kind, and the discriminators are pinned
 * as a closed set rather than one example. A field order is not stylistic here: `StringBindingSpec`
 * constructs as `(credentialsId, variable)` and `ZipBindingSpec` as `(variable, credentialsId)` —
 * "Jenkins verbatim field order" — while the ENCODER emits `credentialsId` first for every kind.
 * That divergence is deliberate and is exactly the kind of detail a reader cannot infer from the
 * domain classes, so it is recorded as bytes rather than as prose.
 *
 * ## Characterization, stated as such
 *
 * These rows describe what the codec does today; they are not a claim about what it should do. The
 * mutation that kills them is a change to `CredentialBindingsPayload.encode` — swapping the
 * `credentialsId` and `variable` puts for `StringBindingSpec` turns the first row red and leaves
 * the rest green, which is the property that makes these pins specific rather than a golden blob.
 * As ASX migrates the credential surface, this class is what turns a silent identity change into a
 * failing test.
 */
@Timeout(30)
class CredentialBindingsPayloadCharacterizationTest {

    private fun id(value: String = "cid") = CredentialsId(value)

    // ---------------------------------------------------------------- exact bytes, per kind

    @Test
    @DisplayName("string: exact payload bytes")
    fun stringKindExactBytes() {
        assertEquals(
            """{"bindings":[{"kind":"string","credentialsId":"cid","variable":"VAR"}]}""",
            CredentialBindingsPayload.encode(listOf(StringBindingSpec(id(), "VAR"))),
        )
    }

    @Test
    @DisplayName("usernamePassword: exact payload bytes")
    fun usernamePasswordKindExactBytes() {
        assertEquals(
            """{"bindings":[{"kind":"usernamePassword","credentialsId":"cid","usernameVariable":"U","passwordVariable":"P"}]}""",
            CredentialBindingsPayload.encode(
                listOf(UsernamePasswordBindingSpec(id(), usernameVariable = "U", passwordVariable = "P")),
            ),
        )
    }

    @Test
    @DisplayName("sshUserPrivateKey: exact bytes with optional fields ABSENT (they are omitted, not nulled)")
    fun sshKindExactBytesWithoutOptionals() {
        assertEquals(
            """{"bindings":[{"kind":"sshUserPrivateKey","credentialsId":"cid","keyFileVariable":"K"}]}""",
            CredentialBindingsPayload.encode(listOf(SshUserPrivateKeyBindingSpec(id(), keyFileVariable = "K"))),
        )
    }

    @Test
    @DisplayName("sshUserPrivateKey: exact bytes with optional fields PRESENT, in encoder order")
    fun sshKindExactBytesWithOptionals() {
        assertEquals(
            """{"bindings":[{"kind":"sshUserPrivateKey","credentialsId":"cid","keyFileVariable":"K","passphraseVariable":"PP","usernameVariable":"U"}]}""",
            CredentialBindingsPayload.encode(
                listOf(
                    SshUserPrivateKeyBindingSpec(
                        id(),
                        keyFileVariable = "K",
                        passphraseVariable = "PP",
                        usernameVariable = "U",
                    ),
                ),
            ),
        )
    }

    @Test
    @DisplayName("file: exact payload bytes")
    fun fileKindExactBytes() {
        assertEquals(
            """{"bindings":[{"kind":"file","credentialsId":"cid","variable":"VAR"}]}""",
            CredentialBindingsPayload.encode(listOf(FileBindingSpec(id(), "VAR"))),
        )
    }

    @Test
    @DisplayName("certificate: exact bytes with optional fields ABSENT")
    fun certificateKindExactBytesWithoutOptionals() {
        assertEquals(
            """{"bindings":[{"kind":"certificate","credentialsId":"cid","keystoreVariable":"KS"}]}""",
            CredentialBindingsPayload.encode(listOf(CertificateBindingSpec("KS", id()))),
        )
    }

    @Test
    @DisplayName("certificate: credentialsId is emitted BEFORE keystoreVariable, unlike its constructor")
    fun certificateKindExactBytesWithOptionals() {
        assertEquals(
            """{"bindings":[{"kind":"certificate","credentialsId":"cid","keystoreVariable":"KS","aliasVariable":"A","passwordVariable":"P"}]}""",
            CredentialBindingsPayload.encode(
                listOf(CertificateBindingSpec("KS", id(), aliasVariable = "A", passwordVariable = "P")),
            ),
        )
    }

    @Test
    @DisplayName("zip: credentialsId is emitted FIRST although the domain class constructs variable first")
    fun zipKindExactBytes() {
        assertEquals(
            """{"bindings":[{"kind":"zip","credentialsId":"cid","variable":"VAR"}]}""",
            CredentialBindingsPayload.encode(listOf(ZipBindingSpec("VAR", id()))),
        )
    }

    @Test
    @DisplayName("usernameColonPassword: exact payload bytes")
    fun usernameColonPasswordKindExactBytes() {
        assertEquals(
            """{"bindings":[{"kind":"usernameColonPassword","credentialsId":"cid","variable":"VAR"}]}""",
            CredentialBindingsPayload.encode(listOf(UsernameColonPasswordBindingSpec("VAR", id()))),
        )
    }

    // ---------------------------------------------------------------- structure of the payload

    @Test
    @DisplayName("bindings keep declaration order and share one array and one root key")
    fun orderIsPreservedAndShapeIsSingleRooted() {
        val encoded = CredentialBindingsPayload.encode(
            listOf(
                StringBindingSpec(id("first"), "A"),
                ZipBindingSpec("B", id("second")),
                FileBindingSpec(id("third"), "C"),
            ),
        )
        assertEquals(
            """{"bindings":[""" +
                """{"kind":"string","credentialsId":"first","variable":"A"},""" +
                """{"kind":"zip","credentialsId":"second","variable":"B"},""" +
                """{"kind":"file","credentialsId":"third","variable":"C"}]}""",
            encoded,
        )
    }

    @Test
    @DisplayName("an empty binding list still produces the root key, so decode round-trips it")
    fun emptyListProducesTheRootKey() {
        val encoded = CredentialBindingsPayload.encode(emptyList())
        assertEquals("""{"bindings":[]}""", encoded)
        assertEquals(emptyList<Any>(), CredentialBindingsPayload.decode(encoded))
    }

    // ---------------------------------------------------------------- symmetry over all seven

    @Test
    @DisplayName("decode(encode(x)) == x for every binding kind, both optional shapes")
    fun codecIsSymmetricPerKind() {
        val all = listOf(
            StringBindingSpec(id(), "VAR"),
            UsernamePasswordBindingSpec(id(), usernameVariable = "U", passwordVariable = "P"),
            SshUserPrivateKeyBindingSpec(id(), keyFileVariable = "K"),
            SshUserPrivateKeyBindingSpec(id(), keyFileVariable = "K", passphraseVariable = "PP", usernameVariable = "U"),
            FileBindingSpec(id(), "VAR"),
            CertificateBindingSpec("KS", id()),
            CertificateBindingSpec("KS", id(), aliasVariable = "A", passwordVariable = "P"),
            ZipBindingSpec("VAR", id()),
            UsernameColonPasswordBindingSpec("VAR", id()),
        )
        assertEquals(all, CredentialBindingsPayload.decode(CredentialBindingsPayload.encode(all)))
    }

    @Test
    @DisplayName("all seven kind discriminators are exactly these strings, and nothing else decodes")
    fun discriminatorVocabularyIsClosed() {
        val kinds = listOf(
            StringBindingSpec(id(), "V"),
            UsernamePasswordBindingSpec(id(), "U", "P"),
            SshUserPrivateKeyBindingSpec(id(), "K"),
            FileBindingSpec(id(), "V"),
            CertificateBindingSpec("KS", id()),
            ZipBindingSpec("V", id()),
            UsernameColonPasswordBindingSpec("V", id()),
        ).map { it.kind }

        assertEquals(
            listOf(
                "string",
                "usernamePassword",
                "sshUserPrivateKey",
                "file",
                "certificate",
                "zip",
                "usernameColonPassword",
            ),
            kinds,
        )

        // The closed vocabulary is enforced on the way in: an eighth discriminator is refused
        // rather than defaulted into a plausible kind.
        val invented = """{"bindings":[{"kind":"martian","credentialsId":"x","variable":"V"}]}"""
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CredentialBindingsPayload.decode(invented)
        }
        assertTrue(failure.message!!.contains("martian")) {
            "the refusal must name the unknown kind, not report a generic parse error: ${failure.message}"
        }
    }

    // ---------------------------------------------------------------- fail closed

    @Test
    @DisplayName("a binding missing a required field is refused, naming the field and the kind")
    fun missingRequiredFieldIsRefused() {
        val noVariable = """{"bindings":[{"kind":"string","credentialsId":"cid"}]}"""
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CredentialBindingsPayload.decode(noVariable)
        }
        assertTrue(failure.message!!.contains("variable") && failure.message!!.contains("string")) {
            "the refusal must name the field and the kind: ${failure.message}"
        }
    }

    @Test
    @DisplayName("a payload without the bindings array is refused rather than treated as empty")
    fun missingRootKeyIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CredentialBindingsPayload.decode("""{"other":[]}""")
        }
        assertTrue(failure.message!!.contains(CredentialBindingsPayload.BINDINGS_KEY)) {
            "the refusal must name the missing key: ${failure.message}"
        }
    }
}
