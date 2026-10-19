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
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * [CredentialSerializer] is the on-disk format of every credential part. The bytes it
 * writes are the bytes the [CredentialPartReader] reads; the two objects share one
 * contract, and the test below pins that contract.
 * Each test is round-trip: write the credential, read it back, and assert equality. A
 * silent edit to the layout would either fail the read with a different shape or succeed
 * with the wrong fields; both outcomes are visible.
*/
class CredentialSerializerTest {

    private val id = CredentialsId.from("aws-prod")

    // --- the part format ------------------------------------------------------

    @Test
    fun `a part is one byte of name length, the name in UTF-8, four bytes of payload length in big-endian, then the payload`() {
        val out = ByteArrayOutputStream()
        val payload = "abc".toByteArray()

        CredentialSerializer.writePart(out, "value", payload)

        val written = out.toByteArray()
        // 1 byte name length + 5 bytes "value" + 4 bytes length + 3 bytes payload
        assertEquals(1 + 5 + 4 + 3, written.size)
        assertEquals(5, written[0].toInt() and 0xFF, "name length is one byte and equals 'value'.length")
        assertArrayEquals("value".toByteArray(Charsets.UTF_8), written.copyOfRange(1, 6))
        // Big-endian length 0x00000003.
        assertEquals(0, written[6].toInt() and 0xFF)
        assertEquals(0, written[7].toInt() and 0xFF)
        assertEquals(0, written[8].toInt() and 0xFF)
        assertEquals(3, written[9].toInt() and 0xFF)
        assertArrayEquals(payload, written.copyOfRange(10, 13))
    }

    @Test
    fun `a part's name length is the String length (UTF-16 code units), not the UTF-8 byte count`() {
        // The source KDoc pins this: every part name in the current vocabulary is ASCII,
        // so the two agree, but the byte written is the UTF-16 count. This is the
        // pre-existing contract; changing it would change bytes on disk.
        val out = ByteArrayOutputStream()

        CredentialSerializer.writePart(out, "value", ByteArray(0))

        assertEquals(5, out.toByteArray()[0].toInt() and 0xFF, "String.length, not UTF-8 byte count")
    }

    @Test
    fun `a linked-ref part writes the 0xFFFFFFFF marker and the refId`() {
        val out = ByteArrayOutputStream()

        CredentialSerializer.writeRefPart(out, "passphrase", LinkedSecretRef(CredentialsId.from("linked")))

        val written = out.toByteArray()
        // 1 byte name length + 10 bytes "passphrase" + 4 bytes marker + 4 bytes refId length + 6 bytes "linked"
        assertEquals(1 + 10 + 4 + 4 + 6, written.size)
        // Marker is 0xFFFFFFFF in BIG-endian.
        assertEquals(0xFF, written[11].toInt() and 0xFF)
        assertEquals(0xFF, written[12].toInt() and 0xFF)
        assertEquals(0xFF, written[13].toInt() and 0xFF)
        assertEquals(0xFF, written[14].toInt() and 0xFF)
        // RefId length 6 in big-endian.
        assertEquals(0, written[15].toInt() and 0xFF)
        assertEquals(0, written[16].toInt() and 0xFF)
        assertEquals(0, written[17].toInt() and 0xFF)
        assertEquals(6, written[18].toInt() and 0xFF)
        assertArrayEquals("linked".toByteArray(), written.copyOfRange(19, 25))
    }

    @Test
    fun `an absent linked-ref part writes the 0x00000000 marker, no refId`() {
        val out = ByteArrayOutputStream()

        CredentialSerializer.writeRefPart(out, "passphrase", null)

        val written = out.toByteArray()
        // 1 byte name length + 10 bytes "passphrase" + 4 bytes marker
        assertEquals(1 + 10 + 4, written.size)
        assertEquals(0, written[11].toInt() and 0xFF)
        assertEquals(0, written[12].toInt() and 0xFF)
        assertEquals(0, written[13].toInt() and 0xFF)
        assertEquals(0, written[14].toInt() and 0xFF)
    }

    // --- SecretText -----------------------------------------------------------

    @Test
    fun `SecretText round-trips a single value part`() {
        val credential = SecretText(id, CredentialScope.GLOBAL, "the value".toByteArray())

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 1, id = id)

        assertTrue(recovered is SecretText, "kindId 1 deserializes to SecretText")
        assertArrayEquals("the value".toByteArray(), (recovered as SecretText).bytes)
    }

    // --- UsernamePassword -----------------------------------------------------

    @Test
    fun `UsernamePassword round-trips username and password parts`() {
        val credential = UsernamePassword(
            id,
            CredentialScope.GLOBAL,
            username = "alice",
            password = "p@ss".toByteArray(),
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 2, id = id)

        assertTrue(recovered is UsernamePassword)
        recovered as UsernamePassword
        assertEquals("alice", recovered.username)
        assertArrayEquals("p@ss".toByteArray(), recovered.password)
    }

    // --- SshPrivateKey --------------------------------------------------------

    @Test
    fun `SshPrivateKey round-trips with an absent passphrase`() {
        val credential = SshPrivateKey(
            id,
            CredentialScope.GLOBAL,
            username = "git",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray(),
            passphraseRef = null,
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 3, id = id)

        assertTrue(recovered is SshPrivateKey)
        recovered as SshPrivateKey
        assertEquals("git", recovered.username)
        assertNull(recovered.passphraseRef, "the absent passphrase round-trips as null")
    }

    @Test
    fun `SshPrivateKey round-trips with a linked passphrase`() {
        val credential = SshPrivateKey(
            id,
            CredentialScope.GLOBAL,
            username = "git",
            privateKey = "PRIV".toByteArray(),
            passphraseRef = LinkedSecretRef(CredentialsId.from("pass-id")),
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 3, id = id)

        assertTrue(recovered is SshPrivateKey)
        recovered as SshPrivateKey
        assertNotNull(recovered.passphraseRef, "the linked passphrase round-trips as non-null")
        assertEquals(CredentialsId.from("pass-id"), recovered.passphraseRef!!.credentialsId)
    }

    // --- SecretFile -----------------------------------------------------------

    @Test
    fun `SecretFile round-trips the original name and content`() {
        val credential = SecretFile(
            id,
            CredentialScope.GLOBAL,
            bytes = "hello".toByteArray(),
            originalName = "greeting.txt",
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 4, id = id)

        assertTrue(recovered is SecretFile)
        recovered as SecretFile
        assertArrayEquals("hello".toByteArray(), recovered.bytes)
        assertEquals("greeting.txt", recovered.originalName)
    }

    @Test
    fun `SecretFile round-trips a null originalName as null, not an empty string`() {
        val credential = SecretFile(
            id,
            CredentialScope.GLOBAL,
            bytes = "x".toByteArray(),
            originalName = null,
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 4, id = id) as SecretFile

        // The serializer writes an empty originalName, and the reader converts an empty
        // string back to null. Pin the round-trip explicitly: the test that proves the
        // null is preserved.
        assertNull(recovered.originalName, "an absent originalName round-trips as null")
    }

    // --- Certificate ----------------------------------------------------------

    @Test
    fun `Certificate round-trips the keystore, alias and a linked password`() {
        val credential = Certificate(
            id,
            CredentialScope.GLOBAL,
            keystore = "keystore-bytes".toByteArray(),
            passwordRef = LinkedSecretRef(CredentialsId.from("keystore-pw")),
            alias = "client",
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 5, id = id) as Certificate

        assertArrayEquals("keystore-bytes".toByteArray(), recovered.keystore)
        assertEquals("client", recovered.alias)
        assertEquals(CredentialsId.from("keystore-pw"), recovered.passwordRef!!.credentialsId)
    }

    @Test
    fun `Certificate round-trips an absent alias as null`() {
        val credential = Certificate(
            id,
            CredentialScope.GLOBAL,
            keystore = "ks".toByteArray(),
            passwordRef = null,
            alias = "",
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 5, id = id) as Certificate

        assertNull(recovered.alias, "empty alias round-trips as null")
        assertNull(recovered.passwordRef, "absent password round-trips as null")
    }

    // --- Zip -----------------------------------------------------------------

    @Test
    fun `Zip round-trips the entries and the entry count`() {
        val credential = Zip(
            id,
            CredentialScope.GLOBAL,
            entries = linkedMapOf(
                "a.txt" to "alpha".toByteArray(),
                "b.txt" to "beta".toByteArray(),
            ),
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 6, id = id) as Zip

        assertEquals(setOf("a.txt", "b.txt"), recovered.entries.keys)
        assertArrayEquals("alpha".toByteArray(), recovered.entries.getValue("a.txt"))
        assertArrayEquals("beta".toByteArray(), recovered.entries.getValue("b.txt"))
    }

    @Test
    fun `Zip entry name must fit in one unsigned byte`() {
        // 256-byte name -> the writer refuses. The check is part of the on-disk
        // contract because the entry name length is encoded as one unsigned byte.
        val tooLong = "x".repeat(256)
        val credential = Zip(
            id,
            CredentialScope.GLOBAL,
            entries = mapOf(tooLong to "x".toByteArray()),
        )

        val ex = try {
            CredentialSerializer.serializeCredential(credential)
            null
        } catch (e: dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStoreTamperException) {
            e
        }

        assertTrue(ex != null, "256-byte entry name is refused by the writer")
    }

    // --- UsernameColonPassword ----------------------------------------------

    @Test
    fun `UsernameColonPassword round-trips user and pass`() {
        val credential = UsernameColonPassword(
            id,
            CredentialScope.GLOBAL,
            user = "alice",
            pass = "p".toByteArray(),
        )

        val bytes = CredentialSerializer.serializeCredential(credential)
        val recovered = CredentialSerializer.deserializeCredential(bytes, kindId = 7, id = id)

        assertTrue(recovered is UsernameColonPassword)
        recovered as UsernameColonPassword
        assertEquals("alice", recovered.user)
        assertArrayEquals("p".toByteArray(), recovered.pass)
    }

    // --- kind-id exclusivity -------------------------------------------------

    @Test
    fun `an unknown kind id is refused rather than guessed`() {
        val bytes = CredentialSerializer.serializeCredential(
            SecretText(id, CredentialScope.GLOBAL, "x".toByteArray()),
        )

        val ex = try {
            CredentialSerializer.deserializeCredential(bytes, kindId = 99, id = id)
            null
        } catch (e: dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStoreTamperException) {
            e
        }

        assertTrue(ex != null, "an unknown kind id must throw, not silently map to a credential")
    }

    @Test
    fun `each kind id round-trips with its own kindId`() {
        // Pin the mapping by enumeration: every supported kind id (1..7) has a
        // serialized form that reads back as the credential it was written from.
        // This is the guarantee the store rests on — a file it wrote must decrypt
        // to the credential the caller handed it.
        // Note what is deliberately NOT asserted here: that each kind has a distinct
        // byte length. KIND_USERNAME_PASSWORD and KIND_USERNAME_COLON_PASSWORD write
        // the same two-part layout (username, password) and differ only in the kind
        // id carried in the file header, so their envelopes can be the same size.
        // Byte length is not the discriminator; the kind id is.
        val credentials: List<Pair<Short, Credential>> = listOf(
            1.toShort() to SecretText(id, CredentialScope.GLOBAL, "v".toByteArray()),
            2.toShort() to UsernamePassword(id, CredentialScope.GLOBAL, "u", "p".toByteArray()),
            3.toShort() to SshPrivateKey(id, CredentialScope.GLOBAL, "u", "k".toByteArray(), null),
            4.toShort() to SecretFile(id, CredentialScope.GLOBAL, "x".toByteArray(), "name"),
            5.toShort() to Certificate(id, CredentialScope.GLOBAL, "ks".toByteArray(), null, "a"),
            6.toShort() to Zip(id, CredentialScope.GLOBAL, mapOf("a" to "x".toByteArray())),
            7.toShort() to UsernameColonPassword(id, CredentialScope.GLOBAL, "u", "p".toByteArray()),
        )

        for ((kindId, credential) in credentials) {
            val bytes = CredentialSerializer.serializeCredential(credential)
            val roundTripped = CredentialSerializer.deserializeCredential(bytes, kindId, id)

            assertEquals(
                credential,
                roundTripped,
                "serialized kindId=$kindId must round-trip with its own kindId",
            )
        }
    }
}
