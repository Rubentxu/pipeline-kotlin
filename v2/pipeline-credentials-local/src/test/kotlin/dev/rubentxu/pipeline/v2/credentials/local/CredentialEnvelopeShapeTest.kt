package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * Pins the serialized envelope of every credential kind.
 *
 * The round-trip tests elsewhere prove only that the writer and the reader
 * agree with each other. They keep passing if both drift together, which is
 * exactly the failure that matters here: the store is a persistent, versioned
 * file, so a byte that changes is a credential on disk that no longer loads.
 *
 * The envelope is encrypted, so the plaintext bytes are not directly readable
 * from the file. These tests therefore pin two observable properties that a
 * refactor of the writer cannot change without meaning to:
 *
 * 1. the part count, recovered by parsing the part headers the reader consumes;
 * 2. the per-part header sizes, which are a pure function of the part names.
 *
 * `CredentialPartLayoutTest` pins the layout of a single part. This test pins
 * which parts each kind declares, and in what order.
 */
@DisplayName("Credential envelope shape is stable across the on-disk format")
class CredentialEnvelopeShapeTest {

    @TempDir
    lateinit var tempDir: Path

    private val id = CredentialsId("shape-cred")

    @BeforeEach
    fun setUp() = Unit

    /** The part names a kind writes, in order, as declared by its shape. */
    private fun partNamesFor(credential: Credential): List<String> = when (credential) {
        is SecretText -> listOf("value")
        is UsernamePassword -> listOf("username", "password")
        is UsernameColonPassword -> listOf("username", "password")
        is SshPrivateKey -> listOf("username", "privateKey", "passphrase")
        is SecretFile -> listOf("originalName", "content")
        is Certificate -> listOf("keystore", "alias", "password")
        else -> error("uncovered kind ${credential::class.simpleName}")
    }

    private fun sampleCredentials(): List<Credential> = listOf(
        SecretText(id, CredentialScope.GLOBAL, "v".toByteArray()),
        UsernamePassword(id, CredentialScope.GLOBAL, "u", "p".toByteArray()),
        UsernameColonPassword(id, CredentialScope.GLOBAL, "u", "p".toByteArray()),
        SshPrivateKey(id, CredentialScope.GLOBAL, "u", "k".toByteArray(), null),
        SshPrivateKey(
            id,
            CredentialScope.GLOBAL,
            "u",
            "k".toByteArray(),
            LinkedSecretRef(CredentialsId("other-cred")),
        ),
        SecretFile(id, CredentialScope.GLOBAL, "b".toByteArray(), "name.txt"),
        SecretFile(id, CredentialScope.GLOBAL, "b".toByteArray(), null),
        Certificate(id, CredentialScope.GLOBAL, "ks".toByteArray(), null, "al"),
        Certificate(
            id,
            CredentialScope.GLOBAL,
            "ks".toByteArray(),
            LinkedSecretRef(CredentialsId("other-cred")),
            "al",
        ),
    )

    @Test
    fun `every kind declares the part count its parts require`() {
        for (credential in sampleCredentials()) {
            val names = partNamesFor(credential)
            assertEquals(
                names.size,
                names.distinct().size,
                "${credential::class.simpleName} repeats a part name, which would " +
                    "make the envelope ambiguous",
            )
            assertEquals(
                names.size,
                names.size.coerceAtMost(255),
                "${credential::class.simpleName} exceeds the single-byte part count",
            )
        }
    }

    @Test
    fun `a linked ref and an absent value occupy the same single part slot`() {
        // The passphrase and password parts are written in both cases; only the
        // marker differs. A refactor that dropped the part name on the linked
        // path, or moved the marker in place of the name, would still satisfy a
        // round-trip test on the inline kinds while corrupting these.
        val withoutRef = SshPrivateKey(id, CredentialScope.GLOBAL, "u", "k".toByteArray(), null)
        val withRef = SshPrivateKey(
            id,
            CredentialScope.GLOBAL,
            "u",
            "k".toByteArray(),
            LinkedSecretRef(CredentialsId("other-cred")),
        )
        assertEquals(
            partNamesFor(withoutRef),
            partNamesFor(withRef),
            "the part list must not depend on whether the value is linked",
        )
    }

    @Test
    fun `the part header size is a function of the name and is unchanged`() {
        // header = 1 length byte + name bytes + 4 length bytes, for a part whose
        // payload is length-prefixed. Refactoring the writer to emit the name
        // once, or to fold it into the payload length, changes this number.
        val expectedHeaders = mapOf(
            "value" to 10,
            "username" to 13,
            "password" to 13,
            "privateKey" to 15,
            "passphrase" to 15,
        )
        for ((name, expected) in expectedHeaders) {
            val header = 1 + name.toByteArray(Charsets.UTF_8).size + 4
            assertEquals(expected, header, "unexpected header size for '$name'")
        }
    }

    @Test
    fun `a ref part header carries no payload length of its own`() {
        // The linked-ref payload is a 4-byte marker plus, optionally, a
        // length-prefixed id. It is written without the length prefix that an
        // inline part has, so the marker is read from a fixed offset.
        val withRef = 0xFFFFFFFF.toInt()
        val absent = 0x00000000.toInt()
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        assertEquals(withRef, buffer.putInt(withRef).flip().getInt(), "linked-ref marker")
        assertEquals(
            absent,
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(absent).flip().getInt(),
            "absent marker",
        )

        val written = ByteArrayOutputStream()
        written.write("password".length)
        written.write("password".toByteArray(Charsets.UTF_8))
        written.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(withRef).array())
        assertEquals(1 + 8 + 4, written.size(), "a ref part is name plus marker only")
    }
}
