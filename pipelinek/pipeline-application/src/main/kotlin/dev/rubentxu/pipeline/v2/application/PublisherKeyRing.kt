package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * The publisher keys the operator keeps.
 *
 * ## Why the key lives with the operator and not with the plugin
 * A public key shipped beside the artifact it signs proves nothing: whoever replaced the
 * artifact can replace the key with it. The key has to be pinned somewhere the plugin cannot
 * reach, and the only such place is the operator's own machine. That is why the trust row names a
 * KEY ID rather than carrying the key itself: a row the plugin could have written would be able to
 * name any key at all, while a row that can only name keys the operator already keeps cannot.
 */
interface PublisherKeyRing {

    /**
     * The public key for [keyId], or `null` when the operator keeps no such key.
     *
     * @return the key's X.509 SubjectPublicKeyInfo bytes, DER.
     */
    fun publicKey(keyId: String): ByteArray?
}

/**
 * The operator's keyring on disk: one file per key, `<keysDir>/<keyId>.pub`, holding the base64
 * of the key's X.509 SubjectPublicKeyInfo.
 *
 * ## Why the file holds base64 and not PEM
 * PEM is a base64 payload between two header lines, and reading it means deciding what to do with
 * the headers. The DER form is what the JDK's `X509EncodedKeySpec` consumes and what
 * `openssl pkey -pubout -outform DER` produces, so the operator's file is exactly the bytes the
 * runtime verifies with, and the base64 encoding is what makes a key safe to store as text.
 *
 * ## Why the key id is checked before it is used as a file name
 * The key id reaches this adapter from two places: the operator's own row, and the signature
 * sidecar, which is not the operator's file. A key id carrying a path separator could name a file
 * outside the keyring, so an id that is not a plain name is not a key id.
 */
class FilePublisherKeyRing(
    private val keysDir: Path,
) : PublisherKeyRing {

    override fun publicKey(keyId: String): ByteArray? {
        if (!isPlainName(keyId)) return null
        val file = keysDir.resolve("$keyId.pub")
        if (!Files.isRegularFile(file)) return null
        val text = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { Base64.getDecoder().decode(text.trim()) }.getOrNull()
    }

    /** A key id is a name, not a path. Anything else is refused before it reaches the filesystem. */
    private fun isPlainName(keyId: String): Boolean =
        keyId.isNotBlank() &&
            keyId != "." &&
            keyId != ".." &&
            keyId.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
}
