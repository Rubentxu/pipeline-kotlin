package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import java.io.ByteArrayOutputStream
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
 * Everything here is written with the codec's own hands: the header, the
 * framing of the entry and the AAD the entry is sealed under. The codec is
 * the ONE authority over the shape of the store file, and a fixture that
 * wrote those bytes by hand would be a second authority over the same shape.
 *
 * ## What this fixture proves, and what it does not
 * A test that uses it proves that the store OPENS a store of that shape and
 * migrates it, and that the shape it opens is the shape the codec frames.
 * It does not prove that the shape matches the one an older release wrote:
 * only a store captured from that release would, and no such capture travels
 * with this tree.
 *
 * Every fact this fixture states is checked by the store that reads it: a
 * wrong magic, a wrong version or a wrong layout make the store refuse to
 * open the file, and the test that used the fixture says so.
 */
internal object LegacyV1StoreFixture {

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
            StoreCodec.magic(),
            StoreCodec.VERSION_LEGACY,
            LocalSecretStore.OWASP_M,
            LocalSecretStore.OWASP_T,
            LocalSecretStore.OWASP_P,
            salt,
            CredentialCrypto.wrapDek(kek, dek),
        )

        val format = StoreCodec.of(StoreCodec.VERSION_LEGACY)
        val out = ByteArrayOutputStream()
        StoreCodec.writeHeader(out, header)
        for ((id, secret) in entries) {
            val sealed = AeadCipher.encrypt(dek, secret, format.aad(header, id, CredentialKinds.SECRET_TEXT))
            out.write(format.encode(StoreEntry(id, sealed, CredentialKinds.SECRET_TEXT)))
        }
        Files.write(file, out.toByteArray())
    }
}
