package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.EntryData
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.SecretStorePassphraseMismatchException
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.StoreHeader
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore.V2EntryData
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.bouncycastle.crypto.engines.AESWrapEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure cryptographic and entry-encoding primitives of the local secret store.
 *
 * Every operation here is a function of its arguments only: no file handles,
 * no locks and no cached state. The passphrase is passed in explicitly rather
 * than read from an instance field, which is what makes this object safe to
 * extract from [LocalSecretStore] without changing the derived key material.
 */
internal object CredentialCrypto {

    private const val OWASP_M = 19456  // KiB
    private const val OWASP_T = 2       // iterations
    private const val OWASP_P = 1       // parallelism
    private const val SALT_SIZE = 16
    private const val DEK_SIZE = 32

    private const val WRAPPED_DEK_SIZE = DEK_SIZE + 8  // = 40 bytes

    private const val HEADER_SIZE_V1 = 4 + 2 + 4 + 4 + 4 + SALT_SIZE + WRAPPED_DEK_SIZE

fun deriveKek(passphrase: CharArray, salt: ByteArray): ByteArray {
    // Argon2id = Argon2Parameters.ARGON2_id = 2 (BouncyCastle convention)
    // m=19456 KiB (OWASP floor), t=2 iterations, p=1 parallelism
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withMemoryAsKB(OWASP_M)
        .withIterations(OWASP_T)
        .withParallelism(OWASP_P)
        .withSalt(salt)
        .build()

    val generator = Argon2BytesGenerator()
    generator.init(params)

    val kek = ByteArray(DEK_SIZE)
    generator.generateBytes(passphrase, kek)
    return kek
}

fun wrapDek(kek: ByteArray, dek: ByteArray): ByteArray {
    // AES-KWP (RFC 3394) - wrap returns the ciphertext directly
    val kwp = AESWrapEngine()
    kwp.init(true, KeyParameter(kek))
    return kwp.wrap(dek, 0, dek.size)
}

fun unwrapDek(kek: ByteArray, wrapped: ByteArray): ByteArray {
    val kwp = AESWrapEngine()
    kwp.init(false, KeyParameter(kek))
    return try {
        kwp.unwrap(wrapped, 0, wrapped.size)
    } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
        throw SecretStorePassphraseMismatchException()
    }
}

fun buildAad(header: StoreHeader, id: CredentialsId): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(header.magic)
    val versionBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.version)
    out.write(versionBuf.array())
    // AAD = magic || version(2) || m(4) || t(4) || p(4) || salt || credentialId
    val kdfBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    kdfBuf.putInt(header.kdfM)
    kdfBuf.putInt(header.kdfT)
    kdfBuf.putInt(header.kdfP)
    out.write(kdfBuf.array())
    out.write(header.kdfSalt)
    out.write(id.value.toByteArray(Charsets.UTF_8))
    return out.toByteArray()
}

fun buildV2Aad(header: StoreHeader, id: CredentialsId, kindId: Short): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(header.magic)
    val versionBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.version)
    out.write(versionBuf.array())
    // AAD = magic || version(2) || m(4) || t(4) || p(4) || salt || credentialId || ":" || kindId
    val kdfBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    kdfBuf.putInt(header.kdfM)
    kdfBuf.putInt(header.kdfT)
    kdfBuf.putInt(header.kdfP)
    out.write(kdfBuf.array())
    out.write(header.kdfSalt)
    out.write(id.value.toByteArray(Charsets.UTF_8))
    out.write(':'.code)
    val kindBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(kindId)
    out.write(kindBuf.array())
    return out.toByteArray()
}

fun encodeEntry(id: CredentialsId, sealed: AeadCipher.SealedBlob): ByteArray {
    val idBytes = id.value.toByteArray(Charsets.UTF_8)
    val blobBytes = sealed.toByteArray()
    // plaintextLen is stored so readEntries can determine entry boundaries without decryption
    val plaintextLen = blobBytes.size - AeadCipher.NONCE_SIZE_BYTES - AeadCipher.TAG_SIZE_BYTES
    // Entry format: idLen(2) + idBytes + plaintextLen(4) + nonce(12) + ciphertext + tag(16)
    val entry = ByteBuffer.allocate(2 + idBytes.size + 4 + blobBytes.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(idBytes.size.toShort())
        .put(idBytes)
        .putInt(plaintextLen)
        .put(blobBytes)
        .array()
    return entry
}

fun encodeV2Entry(entry: V2EntryData): ByteArray {
    val idBytes = entry.id.value.toByteArray(Charsets.UTF_8)
    val blobBytes = entry.sealed.toByteArray()
    val plaintextLen = blobBytes.size - AeadCipher.NONCE_SIZE_BYTES - AeadCipher.TAG_SIZE_BYTES
    // Entry format: idLen(2) + idBytes + kind(2) + plaintextLen(4) + nonce(12) + ciphertext + tag(16)
    val entryBuf = ByteBuffer.allocate(2 + idBytes.size + 2 + 4 + blobBytes.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(idBytes.size.toShort())
        .put(idBytes)
        .putShort(entry.kindId)
        .putInt(plaintextLen)
        .put(blobBytes)
        .array()
    return entryBuf
}

fun readEntries(fileBytes: ByteArray): Map<String, V2EntryData> {
    val result = mutableMapOf<String, V2EntryData>()
    var offset = HEADER_SIZE_V1
    while (offset < fileBytes.size) {
        val buf = ByteBuffer.wrap(fileBytes, offset, fileBytes.size - offset)
            .order(ByteOrder.LITTLE_ENDIAN)
        val idLen = buf.short.toInt()
        if (idLen <= 0 || idLen > 1024) break  // Sanity check
        val idBytes = ByteArray(idLen)
        buf.get(idBytes)
        val id = String(idBytes, Charsets.UTF_8)
        val kindId = buf.short
        val plaintextLen = buf.int
        if (plaintextLen <= 0 || plaintextLen > 1_000_000) break  // Sanity check
        // blob = nonce(12) + ciphertext(plaintextLen) + tag(16)
        val blobLen = AeadCipher.NONCE_SIZE_BYTES + plaintextLen + AeadCipher.TAG_SIZE_BYTES
        val sealedBytes = ByteArray(blobLen)
        buf.get(sealedBytes)
        val sealed = AeadCipher.SealedBlob(sealedBytes)
        result[id] = V2EntryData(CredentialsId.from(id), sealed, kindId)
        offset += 2 + idLen + 2 + 4 + sealedBytes.size
    }
    return result
}

/**
 * Read v1 format entries (no kindId field).
 * Used by v1 back-compat methods (put, rotateBytes).
 */
fun readEntriesV1(fileBytes: ByteArray): Map<String, EntryData> {
    val result = mutableMapOf<String, EntryData>()
    var offset = HEADER_SIZE_V1
    while (offset < fileBytes.size) {
        val buf = ByteBuffer.wrap(fileBytes, offset, fileBytes.size - offset)
            .order(ByteOrder.LITTLE_ENDIAN)
        val idLen = buf.short.toInt()
        if (idLen <= 0 || idLen > 1024) break  // Sanity check
        val idBytes = ByteArray(idLen)
        buf.get(idBytes)
        val id = String(idBytes, Charsets.UTF_8)
        val plaintextLen = buf.int
        if (plaintextLen <= 0 || plaintextLen > 1_000_000) break  // Sanity check
        // blob = nonce(12) + ciphertext(plaintextLen) + tag(16)
        val blobLen = AeadCipher.NONCE_SIZE_BYTES + plaintextLen + AeadCipher.TAG_SIZE_BYTES
        val sealedBytes = ByteArray(blobLen)
        buf.get(sealedBytes)
        val sealed = AeadCipher.SealedBlob(sealedBytes)
        result[id] = EntryData(CredentialsId.from(id), sealed, plaintextLen)
        offset += 2 + idLen + 4 + sealedBytes.size
    }
    return result
}
}
