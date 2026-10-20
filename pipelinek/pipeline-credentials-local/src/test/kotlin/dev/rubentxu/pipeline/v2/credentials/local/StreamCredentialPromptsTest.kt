package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a credential seeded from a redirected stream is the same
 * credential one typed at a terminal would produce.
 * The capacity this serves: a store prepared by a script — provisioning, a
 * container, a build — holds the value that was piped in. Before this reader
 * existed the terminal was the only source, so every such use of `credentials
 * add` had no way in at all.
 * "The same credential" is asserted on the bytes, not on the exit code. A
 * reader that returns the right kind of object holding the wrong value would
 * pass a test that only checked success, so each test compares content and the
 * binary case compares every byte including a NUL and a high byte.
 * Everything here is in-memory: a byte array in, a credential out. No terminal,
 * no file, no clock, no store.
*/
class StreamCredentialPromptsTest {

    private fun read(kind: MainCredentialsCli.CredentialKind, text: String) =
        StreamCredentialPrompts.read(kind, ByteArrayInputStream(text.toByteArray()))

    private fun read(kind: MainCredentialsCli.CredentialKind, bytes: ByteArray) =
        StreamCredentialPrompts.read(kind, ByteArrayInputStream(bytes))

    @Test
    fun `a secret keeps every byte a shell pipeline would deliver`() {
        val secret = read(MainCredentialsCli.CredentialKind.SECRET_TEXT, "s3cr3t-value") as SecretText

        assertArrayEquals(
            "s3cr3t-value".toByteArray(),
            secret.bytes,
            "the value must arrive exactly as sent; a store holding a mangled secret fails at use, not here",
        )
    }

    @Test
    fun `the newline a shell adds is not part of the value`() {
        val piped = read(MainCredentialsCli.CredentialKind.SECRET_TEXT, "value\n") as SecretText
        val exact = read(MainCredentialsCli.CredentialKind.SECRET_TEXT, "value") as SecretText

        assertArrayEquals(
            exact.bytes,
            piped.bytes,
            "`echo value |` is how a shell writes a value; the trailing newline must not become part of the secret",
        )
    }

    @Test
    fun `a password that itself ends in a newline keeps that newline`() {
        // One newline closes the value; the other belongs to it. A passphrase
        // read from a file routinely ends this way, and dropping both would
        // store a secret the caller never sent.
        val credential = read(
            MainCredentialsCli.CredentialKind.USERNAME_PASSWORD,
            "alice\ncorrect horse\n\n",
        ) as UsernamePassword

        assertEquals("alice", credential.username)
        assertArrayEquals(
            "correct horse\n".toByteArray(),
            credential.password,
            "exactly one newline terminates the value; a newline inside it is part of it",
        )
    }

    @Test
    fun `the two-field kinds take the username from the first line`() {
        val colon = read(
            MainCredentialsCli.CredentialKind.USERNAME_COLON_PASSWORD,
            "bob\ns3cret\n",
        ) as UsernameColonPassword

        assertEquals("bob", colon.user)
        assertArrayEquals("s3cret".toByteArray(), colon.pass)
    }

    @Test
    fun `a binary archive survives the round trip byte for byte`() {
        val archive = zipBytes(byteArrayOf(0, 0x7F, 0xFF.toByte(), 0x0A, 0x0D))

        val stored = read(MainCredentialsCli.CredentialKind.ZIP, archive) as Zip

        val entry = stored.entries["payload.bin"]
        assertTrue(entry != null, "the archive must keep its entry name, not just its bytes")
        assertArrayEquals(
            byteArrayOf(0, 0x7F, 0xFF.toByte(), 0x0A, 0x0D),
            entry,
            "a keystore or archive is binary; any text round trip corrupts it silently",
        )
    }

    @Test
    fun `an empty value is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            read(MainCredentialsCli.CredentialKind.SECRET_TEXT, "")
        }
    }

    @Test
    fun `a two-field kind given only one value is refused rather than guessed`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            read(MainCredentialsCli.CredentialKind.USERNAME_PASSWORD, "only-a-username")
        }

        assertTrue(
            failure.message!!.contains("separated"),
            "the refusal must name the rule that was broken, not just fail: $failure",
        )
    }

    /** Builds a one-entry ZIP whose single entry holds [payload] verbatim. */
    private fun zipBytes(payload: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("payload.bin"))
            zip.write(payload)
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }
}
