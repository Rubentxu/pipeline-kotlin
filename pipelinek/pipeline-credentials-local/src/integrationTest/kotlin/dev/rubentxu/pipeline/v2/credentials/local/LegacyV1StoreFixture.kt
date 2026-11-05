package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * A store in the legacy format: the one the store still opens, and the one it
 * does not write.
 *
 * The store writes a single format. This fixture writes the other one, so
 * that the reader of that format stays exercised: a store that holds it must
 * still open, still migrate and still keep every credential it held.
 *
 * ## What is written with whose hands
 * The ENTRIES are written with the module's own encoder, its own AAD, its own
 * key derivation and its own cipher, so the shape of an entry has one
 * authority. The HEADER is written here, from the layout the module's own
 * header reader documents -- `magic(4) + version(2) + m(4) + t(4) + p(4) +
 * salt(16) + wrappedDek(40)` -- because the module has no writer of that
 * format left to ask, and writing one here would be a second authority over
 * the shape of a header.
 *
 * ## What this fixture proves, and what it does not
 * A test that uses it proves that the store OPENS a store of that shape and
 * migrates it. It does not prove that the shape matches the one an older
 * release wrote: only a store captured from that release would, and no such
 * capture travels with this tree.
 *
 * Every fact this fixture states is checked by the store that reads it: a
 * wrong magic, a wrong version or a wrong layout make the store refuse to
 * open the file, and the test that used the fixture says so.
 */
internal object LegacyV1StoreFixture {

    /** The magic the store's header carries: "PKCR" (Pipeline CRedentials). */
    private val MAGIC = byteArrayOf(0x50, 0x4B, 0x43, 0x52)

    /** The version the legacy format carries in its header. */
    private const val LEGACY_VERSION: Short = 1

    /**
     * Writes a store in the legacy format, holding [entries] under [file].
     *
     * @param file the store file to write
     * @param passphrase the passphrase the store is sealed with
     * @param entries the credentials the store holds, and the secret of each
     */
    fun write(
        file: Path,
        passphrase: CharArray,
        entries: Map<CredentialsId, ByteArray>,
    ) {
        file.parent?.let { Files.createDirectories(it) }
        val salt = ByteArray(LocalSecretStore.SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val kek = CredentialCrypto.deriveKek(passphrase, salt)
        val dek = ByteArray(LocalSecretStore.DEK_SIZE).also { SecureRandom().nextBytes(it) }
        val header = LocalSecretStore.StoreHeader(
            MAGIC,
            LEGACY_VERSION,
            LocalSecretStore.OWASP_M,
            LocalSecretStore.OWASP_T,
            LocalSecretStore.OWASP_P,
            salt,
            CredentialCrypto.wrapDek(kek, dek),
        )

        val out = ByteArrayOutputStream()
        writeHeader(out, header)
        for ((id, secret) in entries) {
            val sealed = AeadCipher.encrypt(dek, secret, CredentialCrypto.buildAad(header, id))
            out.write(CredentialCrypto.encodeEntry(id, sealed))
        }
        Files.write(file, out.toByteArray())
    }

    /**
     * Writes the header of the store file, in the layout the store's own
     * header reader documents.
     */
    private fun writeHeader(out: ByteArrayOutputStream, header: LocalSecretStore.StoreHeader) {
        val buffer = ByteBuffer
            .allocate(LocalSecretStore.HEADER_SIZE_V1)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(header.magic)
        buffer.putShort(header.version)
        buffer.putInt(header.kdfM)
        buffer.putInt(header.kdfT)
        buffer.putInt(header.kdfP)
        buffer.put(header.kdfSalt)
        buffer.put(header.wrappedDek)
        out.write(buffer.array())
    }
}
