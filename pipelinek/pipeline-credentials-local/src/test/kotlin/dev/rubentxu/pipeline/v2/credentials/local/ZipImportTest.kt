package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: importing an archive is bounded, and what it carries is what
 * the operator gave.
 *
 * ## What is bounded, and why it matters
 *
 * An archive says how much it expands to, and the operator's file decides it.
 * The expansion used to be read with no ceiling at all: every entry was read
 * whole, and the number of entries was whatever the file carried. A small
 * file that expands to a gigabyte was therefore a small file that cost a
 * gigabyte of heap, and the ceiling that ends that is a property of the
 * store, not of the archive: the store refuses to hold a secret longer than
 * its own limit, so a longer one can never be stored.
 *
 * ## What each row proves
 *
 * - A small archive keeps every byte and every name: the change bounds the
 *   import, it does not alter what a valid import produces.
 * - An archive with more entries than the format can frame is refused: the
 *   count of the parts is written in one byte.
 * - An entry that expands past the ceiling is refused, and so is an archive
 *   whose entries together expand past it: the ceilings are applied while the
 *   bytes are read, not after the whole archive has been materialised.
 * - The size an entry declares is not the size it carries: the bytes are
 *   counted, because the claim is what an archive that wants to exhaust a heap
 *   would lie about.
 * - Two entries with one name are refused: a credential holds a map, so one of
 *   them would be left out and the operator would never be told which.
 * - A name the form cannot carry is refused at the boundary, where the
 *   operator can act on it.
 * - A refusal names the ceiling and never the content.
 *
 * ## Why this is a unit test
 *
 * Everything here is bytes in and a credential out: no terminal, no file, no
 * clock, no store. The store that keeps the result is exercised by the
 * integration lane, over a real file.
 */
class ZipImportTest {

    @Test
    fun `a small archive keeps every byte and every name`() {
        val archive = archiveOf("config.json" to "one".toByteArray(), "keys/id.pem" to "two".toByteArray())

        val stored = CredentialValues.zip(archive)

        assertEquals(
            listOf("config.json", "keys/id.pem"),
            stored.entries.keys.toList(),
            "the names of the entries are part of the credential; a store that keeps the bytes " +
                "under another name holds a credential the operator never gave",
        )
        assertArrayEquals(
            "one".toByteArray(),
            stored.entries.getValue("config.json"),
            "the bytes of an entry are the credential; anything else is a credential nobody gave",
        )
        assertArrayEquals(
            "two".toByteArray(),
            stored.entries.getValue("keys/id.pem"),
            "an entry nested in a folder keeps its bytes like any other entry",
        )
    }

    @Test
    fun `an archive with more entries than the format can frame is refused`() {
        val entries = (0..CredentialImportLimits.DEFAULT_MAX_ENTRIES)
            .map { "entry-$it.txt" to "x".toByteArray() }
        val archive = archiveOf(*entries.toTypedArray())

        val refusal = assertThrows(CredentialImportRefusal.TooManyEntries::class.java) {
            CredentialValues.zip(archive)
        }

        assertEquals(
            CredentialImportLimits.DEFAULT_MAX_ENTRIES,
            refusal.limit,
            "the refusal must name the ceiling that was crossed",
        )
    }

    @Test
    fun `an entry that expands past the ceiling is refused while it is read`() {
        val archive = archiveOf("big.bin" to ByteArray(4096))
        val limits = CredentialImportLimits(maxEntryBytes = 64)

        val refusal = assertThrows(CredentialImportRefusal.InputTooLarge::class.java) {
            CredentialValues.zip(archive, limits)
        }

        assertEquals(
            64L,
            refusal.limit,
            "the refusal must name the ceiling the entry crossed, so the operator knows how much to remove",
        )
    }

    @Test
    fun `an archive whose entries together expand past the ceiling is refused`() {
        val archive = archiveOf("a.bin" to ByteArray(60), "b.bin" to ByteArray(60))
        val limits = CredentialImportLimits(maxContentBytes = 100)

        val refusal = assertThrows(CredentialImportRefusal.TooLargeInTotal::class.java) {
            CredentialValues.zip(archive, limits)
        }

        assertEquals(
            100L,
            refusal.limit,
            "the ceiling of the whole archive is a different fact from the ceiling of one entry",
        )
    }

    @Test
    fun `a small archive that expands to a huge entry is refused`() {
        // The archive is two kilobytes; the entry it carries is two megabytes.
        // The size of the file says nothing about what it costs, which is why
        // the ceiling is applied to the bytes as they are read.
        val archive = archiveOf("bomb.bin" to ByteArray(2 * 1024 * 1024))

        val refusal = assertThrows(CredentialImportRefusal.InputTooLarge::class.java) {
            CredentialValues.zip(archive)
        }

        assertEquals(
            CredentialImportLimits.DEFAULT_MAX_CONTENT_BYTES,
            refusal.limit,
            "the ceiling that stops the expansion is the store's own ceiling of the secret an entry holds",
        )
    }

    @Test
    fun `the size an entry declares is not the size it carries`() {
        val content = ByteArray(4096) { (it % 251).toByte() }
        val archive = archiveDeclaringLess("payload.bin", content, declaredSize = 4)
        val limits = CredentialImportLimits(maxEntryBytes = 100)

        val refusal = assertThrows(CredentialImportRefusal.InputTooLarge::class.java) {
            CredentialValues.zip(archive, limits)
        }

        assertEquals(
            100L,
            refusal.limit,
            "the bytes of the entry are counted while they are read; the size the header declares is a claim",
        )
    }

    @Test
    fun `the size an entry declares does not shorten the entry`() {
        val content = ByteArray(4096) { (it % 251).toByte() }
        val archive = archiveDeclaringLess("payload.bin", content, declaredSize = 4)

        val stored = CredentialValues.zip(archive)

        assertArrayEquals(
            content,
            stored.entries.getValue("payload.bin"),
            "the entry carries the bytes it holds; a header that declares fewer is not the entry",
        )
    }

    @Test
    fun `two entries with the same name are refused`() {
        val archive = archiveWithDuplicateNames()

        assertThrows(CredentialImportRefusal.DuplicateEntryNames::class.java) {
            CredentialValues.zip(archive)
        }
    }

    @Test
    fun `an entry name the form cannot carry is refused at the boundary`() {
        val name = "n".repeat(256)
        val archive = archiveOf(name to "payload".toByteArray())

        val refusal = assertThrows(CredentialImportRefusal.EntryNameNotCarried::class.java) {
            CredentialValues.zip(archive)
        }

        assertEquals(
            255,
            refusal.limit,
            "the name is written in one byte, so the ceiling is what one byte can name",
        )
    }

    @Test
    fun `a refusal names the ceiling and never the content`() {
        val secret = "the-secret-inside-the-archive"
        val archive = archiveOf("secret.txt" to secret.toByteArray())
        val limits = CredentialImportLimits(maxEntryBytes = 4)

        val refusal = assertThrows(CredentialImportRefusal.InputTooLarge::class.java) {
            CredentialValues.zip(archive, limits)
        }

        val message = refusal.message!!
        assertTrue(
            message.contains("4"),
            "the refusal must name the ceiling that was crossed: $message",
        )
        assertTrue(
            !message.contains(secret) && !message.contains("secret.txt"),
            "the refusal must not carry what the credential held: $message",
        )
    }

    @Test
    fun `an archive bigger than the ceiling of the read is refused`() {
        val archive = archiveOf("a.bin" to ByteArray(64))
        val limits = CredentialImportLimits(maxArchiveBytes = 8)

        val refusal = assertThrows(CredentialImportRefusal.InputTooLarge::class.java) {
            CredentialValues.zip(archive, limits)
        }

        assertEquals(
            8L,
            refusal.limit,
            "the archive is read whole, so the ceiling of that read is the size of the archive",
        )
    }

    @Test
    fun `a credential the store cannot hold is refused`() {
        val tooLong = ByteArray(CredentialImportLimits.DEFAULT_MAX_CONTENT_BYTES.toInt())

        val refusal = assertThrows(CredentialImportRefusal.LargerThanTheStoreHolds::class.java) {
            CredentialValues.secretText(tooLong)
        }

        assertEquals(
            StoreCodec.MAX_PLAINTEXT_LENGTH,
            refusal.limit,
            "the ceiling is the store's own: an entry longer than it could never be given back",
        )
    }

    @Test
    fun `the ceilings are the store's own`() {
        val limits = CredentialImportLimits.DEFAULT

        assertEquals(
            StoreCodec.MAX_PLAINTEXT_LENGTH,
            limits.maxContentBytes,
            "the content of a credential is bounded by what the store can hold",
        )
        assertEquals(
            StoreCodec.MAX_PLAINTEXT_LENGTH,
            limits.maxEntryBytes,
            "one entry cannot be larger than the whole credential",
        )
        assertEquals(
            254,
            limits.maxEntries,
            "the count of the parts is one byte, and one part carries the count of the entries",
        )
    }

    @Test
    fun `the value read from a stream is refused when it is longer than the ceiling`() {
        val stream = ByteArrayInputStream(ByteArray(64))

        val refusal = assertThrows(CredentialImportRefusal.InputTooLarge::class.java) {
            StreamCredentialPrompts.read(
                MainCredentialsCli.CredentialKind.SECRET_TEXT,
                stream,
                CredentialImportLimits(maxContentBytes = 8),
            )
        }

        assertEquals(
            8L,
            refusal.limit,
            "the bytes of a stream are read under the ceiling of the kind that reads them",
        )
    }

    @Test
    fun `the value read from a stream keeps every byte when it fits`() {
        val content = "the bytes a pipeline sends".toByteArray()

        val stored = assertInstanceOf(
            SecretText::class.java,
            StreamCredentialPrompts.read(
                MainCredentialsCli.CredentialKind.SECRET_TEXT,
                ByteArrayInputStream(content),
            ),
        )

        assertArrayEquals(
            content,
            stored.bytes,
            "the bound is a bound: what fits is stored exactly as it arrived",
        )
    }

    /** An archive whose entries are [entries], each one stored as it is. */
    private fun archiveOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /**
     * An archive of one entry whose header declares a size that is not the
     * size the entry carries.
     *
     * The size of an entry is written in the header of the archive, and the
     * bytes that follow it are the ones the entry holds. The header is
     * patched here so that the two disagree, which is what an archive that
     * wants to be read cheaply would look like.
     */
    private fun archiveDeclaringLess(name: String, content: ByteArray, declaredSize: Int): ByteArray {
        val archive = archiveOf(name to content)
        val declared = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(declaredSize)
            .array()
        System.arraycopy(declared, 0, archive, UNCOMPRESSED_SIZE_OFFSET, declared.size)
        return archive
    }

    /**
     * An archive of two entries that carry the same name.
     *
     * The writer of an archive refuses to write two entries with one name, so
     * the archive is written with two names of the same length and the second
     * one is renamed afterwards. The reader of an archive reads the name of
     * each entry from its header, and the header is what it sees.
     */
    private fun archiveWithDuplicateNames(): ByteArray {
        val archive = archiveOf(
            "dup-one.txt" to "first".toByteArray(),
            "dup-two.txt" to "second".toByteArray(),
        )
        val second = "dup-two.txt".toByteArray()
        val first = "dup-one.txt".toByteArray()
        var at = indexOf(archive, second)
        while (at >= 0) {
            System.arraycopy(first, 0, archive, at, first.size)
            at = indexOf(archive, second)
        }
        return archive
    }

    /** Where [needle] is in [haystack], or -1 when it is not there. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    continue@outer
                }
            }
            return start
        }
        return -1
    }
}

/** Where the size an entry declares is written in the header of an archive. */
private const val UNCOMPRESSED_SIZE_OFFSET = 22
