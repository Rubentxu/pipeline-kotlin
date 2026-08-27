package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsBinding
import dev.rubentxu.pipeline.v2.credentials.api.CredentialScope
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.bouncycastle.crypto.engines.AESWrapEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * Local credential store using envelope encryption.
 *
 * ## Design (ADR-0049 D2 — rung ii)
 *
 * - KEK derives from passphrase via Argon2id (OWASP floor: m≥19456 KiB, t≥2, p≥1)
 * - DEK is random 256-bit, wrapped by KEK (envelope tier ii)
 * - Per-entry: AES-256-GCM with random 96-bit nonce + AAD
 * - AAD = magic ‖ version ‖ kdfParams ‖ credentialId (anti-rename/swap)
 * - re-Argon2id only on unlock, never on every put
 *
 * ## File Format
 *
 * ```
 * Header:
 *   magic: 4 bytes ("PKCR")
 *   version: 1 byte (1)
 *   argon2_m: 4 bytes LE (KiB)
 *   argon2_t: 4 bytes LE (iterations)
 *   argon2_p: 4 bytes LE (parallelism)
 *   salt: 16 bytes
 *   wrappedDEK: 40 bytes (AES-KWP RFC 3394 output for 32-byte DEK)
 *
 * Entry (repeated):
 *   idLen: 2 bytes LE
 *   credentialId: UTF-8 bytes
 *   nonce: 12 bytes
 *   ciphertext: variable
 *   tag: 16 bytes
 * ```
 */
class LocalSecretStore(
    private val file: Path,
    private val passphrase: CharArray,
) : SecretStore {
    private val secureRandom = SecureRandom()

    /**
     * KDF parameters for Argon2id.
     * Nested as LocalSecretStore.KdfParams so that LocalSecretStore.KdfParams.OWASP_MIN.m works.
     */
    data class KdfParams(
        val m: Int,
        val t: Int,
        val p: Int,
        val salt: ByteArray,
    ) {
        companion object {
            val OWASP_MIN = KdfParams(OWASP_M, OWASP_T, OWASP_P, ByteArray(0))
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as KdfParams
            return m == other.m && t == other.t && p == other.p && salt.contentEquals(other.salt)
        }

        override fun hashCode(): Int {
            var result = m
            result = 31 * result + t
            result = 31 * result + p
            result = 31 * result + salt.contentHashCode()
            return result
        }
    }

    companion object {
        /** Header magic: "PKCR" (Pipeline CRedentials) */
        private val MAGIC = byteArrayOf(0x50, 0x4B, 0x43, 0x52) // "PKCR"
        private const val VERSION: Byte = 1

        /** OWASP floor KDF params (2023 recommendation) */
        const val OWASP_M = 19456  // KiB
        const val OWASP_T = 2       // iterations
        const val OWASP_P = 1       // parallelism
        const val SALT_SIZE = 16
        const val DEK_SIZE = 32
        // AES-KWP (RFC 3394) output = input + 8 bytes (64-bit IV/wrap vector)
        const val WRAPPED_DEK_SIZE = DEK_SIZE + 8  // = 40 bytes

        /**
         * Reads the header from a file path.
         * Accessible as LocalSecretStore.readHeader(path)
         */
        fun readHeader(file: Path): StoreHeader {
            val bytes = Files.readAllBytes(file)
            return readHeaderBytes(bytes)
        }

        private fun readHeaderBytes(bytes: ByteArray): StoreHeader {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            if (!magic.contentEquals(MAGIC)) {
                throw SecretStoreTamperException("Invalid store magic")
            }
            val version = buf.get()
            if (version != VERSION) {
                throw SecretStoreTamperException("Unsupported store version: $version")
            }
            val m = buf.int
            val t = buf.int
            val p = buf.int
            val salt = ByteArray(SALT_SIZE)
            buf.get(salt)
            val wrappedDek = ByteArray(WRAPPED_DEK_SIZE)
            buf.get(wrappedDek)
            return StoreHeader(magic, version, m, t, p, salt, wrappedDek)
        }

        /** Header size: magic(4) + version(1) + m(4) + t(4) + p(4) + salt(16) + wrappedDEK(40) = 73 */
        private const val HEADER_SIZE = 4 + 1 + 4 + 4 + 4 + SALT_SIZE + WRAPPED_DEK_SIZE
    }

    // Cached derived key (set on first access, wiped on close)
    private var cachedKek: ByteArray? = null
    private var cachedSalt: ByteArray? = null

    /**
     * Exception thrown when the store file is corrupted or tampered.
     */
    class SecretStoreTamperException(message: String, cause: Throwable? = null) :
        dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException(message, cause)

    /**
     * Exception thrown when the passphrase is incorrect.
     */
    class SecretStorePassphraseMismatchException :
        dev.rubentxu.pipeline.v2.credentials.api.SecretStorePassphraseMismatchException(
            "Passphrase required: set PIPELINE_STORE_PASSPHRASE or run interactively in a TTY"
        )

    /**
     * Exception thrown when no passphrase is available.
     */
    class CredentialsStorePassphraseUnavailableException(message: String) :
        dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePassphraseUnavailableException(message)

    /**
     * Exception thrown when a secret is empty.
     */
    class CredentialsStoreEmptySecretException :
        dev.rubentxu.pipeline.v2.credentials.api.CredentialsStoreEmptySecretException(
            "Cannot store empty secret"
        )

    /**
     * Exception thrown when POSIX permissions cannot be enforced.
     */
    class CredentialsStorePosixPermissionsException(message: String) :
        dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePosixPermissionsException(message)

    init {
        Files.createDirectories(file.parent)
        enforcePosixPermissions(file)
    }

    /**
     * Stores a credential.
     *
     * @param id The credential ID
     * @param bytes The secret bytes
     * @throws CredentialsStoreEmptySecretException if bytes is empty
     */
    override fun put(id: CredentialsId, bytes: ByteArray) {
        if (bytes.isEmpty()) throw CredentialsStoreEmptySecretException()

        val hdr = loadOrCreateHeader()
        val kek = deriveKek(hdr.kdfSalt)

        // Load existing DEK from header, or generate new one for a FRESH store only.
        // The same DEK is reused for ALL entries (per-store DEK).
        val dek = if (hdr.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
            // Fresh store: generate new DEK
            ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
        } else {
            // Existing store: reuse the stored DEK
            unwrapDek(kek, hdr.wrappedDek)
        }

        // Always wrap the current DEK (deterministic: same KEK + DEK → same result)
        val wrappedDek = wrapDek(kek, dek)

        // Encrypt the secret with the DEK (AES-256-GCM)
        val aad = buildAad(hdr, id)
        val sealed = AeadCipher.encrypt(dek, bytes, aad)

        // Atomic write: read existing entries, replace/insert, rewrite file
        val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
        val existingBytes = if (Files.exists(file) && Files.size(file) > 0) Files.readAllBytes(file) else null

        val out = ByteArrayOutputStream()
        writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, wrappedDek)

        // Parse existing entries and rebuild with new/updated entry
        if (existingBytes != null && existingBytes.size > HEADER_SIZE) {
            val existingEntries = readEntries(existingBytes).toMutableMap()
            existingEntries[id.value] = EntryData(id, sealed, bytes.size)
            for ((_, entryData) in existingEntries) {
                out.write(encodeEntry(entryData.id, entryData.sealed))
            }
        } else {
            // New store: just write the single entry
            out.write(encodeEntry(id, sealed))
        }

        val written = out.toByteArray()
        Files.write(tempFile, written)
        CredentialsStorePosix.setFilePermissions(tempFile)
        tempFile.toFile().renameTo(file.toFile())
    }

    /**
     * Retrieves a credential.
     *
     * @param id The credential ID
     * @return SecretHandle wrapping the bytes
     * @throws SecretStoreTamperException if integrity check fails
     */
    override fun get(id: CredentialsId): SecretHandle {
        if (!Files.exists(file)) {
            throw SecretStoreTamperException("Store file does not exist")
        }
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val kek = deriveKek(hdr.kdfSalt)

        // Find entry for this ID
        val entries = readEntries(fileBytes)
        val entryData = entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Decrypt DEK: the DEK is stored in the header (wrapped with KEK)
        val dek = unwrapDek(kek, hdr.wrappedDek)

        // Decrypt secret
        val aad = buildAad(hdr, id)
        val plaintext = try {
            AeadCipher.decrypt(dek, entryData.sealed, aad)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
        }

        return SecretHandle.secret(plaintext)
    }

    /**
     * Lists all credential IDs in the store.
     *
     * @return List of credential IDs (never returns values)
     */
    override fun list(): List<CredentialsId> {
        if (!Files.exists(file) || Files.size(file) == 0L) return emptyList()
        val fileBytes = Files.readAllBytes(file)
        val entries = readEntries(fileBytes)
        return entries.keys.map { CredentialsId.from(it) }
    }

    /**
     * Removes a credential.
     *
     * @param id The credential ID to remove
     */
    override fun remove(id: CredentialsId) {
        if (!Files.exists(file)) return
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val entries = readEntries(fileBytes).toMutableMap()
        entries.remove(id.value) ?: return // Not found, no-op

        // Rewrite without the removed entry
        val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
        val out = ByteArrayOutputStream()
        writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
        for ((_, entryData) in entries) {
            out.write(encodeEntry(entryData.id, entryData.sealed))
        }
        Files.write(tempFile, out.toByteArray())
        CredentialsStorePosix.setFilePermissions(tempFile)
        tempFile.toFile().renameTo(file.toFile())
    }

    /**
     * Rotates a credential's secret bytes, preserving the DEK.
     *
     * @param id The credential ID
     * @param newBytes The new secret bytes
     */
    override fun rotate(id: CredentialsId, newBytes: ByteArray) {
        if (newBytes.isEmpty()) throw CredentialsStoreEmptySecretException()
        if (!Files.exists(file)) {
            put(id, newBytes)
            return
        }
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val kek = deriveKek(hdr.kdfSalt)

        val entries = readEntries(fileBytes)
        entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Decrypt existing DEK from header
        val dek = unwrapDek(kek, hdr.wrappedDek)
        val aad = buildAad(hdr, id)
        val sealed = AeadCipher.encrypt(dek, newBytes, aad)

        // Update entry
        val updatedEntries = entries.toMutableMap()
        updatedEntries[id.value] = EntryData(id, sealed, newBytes.size)

        val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
        val out = ByteArrayOutputStream()
        writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
        for ((_, entryData) in updatedEntries) {
            out.write(encodeEntry(entryData.id, entryData.sealed))
        }
        Files.write(tempFile, out.toByteArray())
        CredentialsStorePosix.setFilePermissions(tempFile)
        tempFile.toFile().renameTo(file.toFile())
    }

    /**
     * Closes the store, wiping the passphrase and cached keys from memory.
     */
    override fun close() {
        passphrase.fill('\u0000')
        cachedKek?.fill(0)
        cachedKek = null
        cachedSalt = null
    }

    // === Private helpers ===

    private fun enforcePosixPermissions(file: Path) {
        try {
            val fs = file.fileSystem
            if (fs.supportedFileAttributeViews().contains("posix")) {
                val dir = file.parent
                CredentialsStorePosix.enforce(file, dir)
            }
        } catch (e: Exception) {
            throw CredentialsStorePosixPermissionsException(
                "Cannot enforce POSIX permissions: ${e.message}")
        }
    }

    private fun loadOrCreateHeader(): StoreHeader {
        if (Files.exists(file) && Files.size(file) > 0) {
            return readHeader(Files.readAllBytes(file))
        }
        // Create new header with fresh salt
        val salt = ByteArray(SALT_SIZE)
        secureRandom.nextBytes(salt)
        return StoreHeader(MAGIC, VERSION, OWASP_M, OWASP_T, OWASP_P, salt, ByteArray(WRAPPED_DEK_SIZE))
    }

    private fun writeHeader(
        out: ByteArrayOutputStream,
        magic: ByteArray,
        version: Byte,
        m: Int,
        t: Int,
        p: Int,
        salt: ByteArray,
        wrappedDek: ByteArray,
    ) {
        out.write(magic)
        out.write(version.toInt())
        val kdfBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        kdfBuf.putInt(m)
        kdfBuf.putInt(t)
        kdfBuf.putInt(p)
        out.write(kdfBuf.array())
        out.write(salt)
        out.write(wrappedDek)
    }

    private fun readHeader(bytes: ByteArray): StoreHeader =
        Companion.readHeaderBytes(bytes)

    private fun deriveKek(salt: ByteArray): ByteArray {
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

    private fun wrapDek(kek: ByteArray, dek: ByteArray): ByteArray {
        // AES-KWP (RFC 3394) - wrap returns the ciphertext directly
        val kwp = AESWrapEngine()
        kwp.init(true, KeyParameter(kek))
        return kwp.wrap(dek, 0, dek.size)
    }

    private fun unwrapDek(kek: ByteArray, wrapped: ByteArray): ByteArray {
        val kwp = AESWrapEngine()
        kwp.init(false, KeyParameter(kek))
        return try {
            kwp.unwrap(wrapped, 0, wrapped.size)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw SecretStorePassphraseMismatchException()
        }
    }

    private fun buildAad(header: StoreHeader, id: CredentialsId): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header.magic)
        out.write(header.version.toInt())
        // AAD = magic || version || m(4) || t(4) || p(4) || salt || credentialId
        val kdfBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        kdfBuf.putInt(header.kdfM)
        kdfBuf.putInt(header.kdfT)
        kdfBuf.putInt(header.kdfP)
        out.write(kdfBuf.array())
        out.write(header.kdfSalt)
        out.write(id.value.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun encodeEntry(id: CredentialsId, sealed: AeadCipher.SealedBlob): ByteArray {
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

    private fun readEntries(fileBytes: ByteArray): Map<String, EntryData> {
        val result = mutableMapOf<String, EntryData>()
        var offset = HEADER_SIZE
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

    /**
     * Header data for the store.
     */
    data class StoreHeader(
        val magic: ByteArray,
        val version: Byte,
        val kdfM: Int,
        val kdfT: Int,
        val kdfP: Int,
        val kdfSalt: ByteArray,
        val wrappedDek: ByteArray,
    )

    /**
     * Encrypted entry data: id + sealed blob + plaintext length for boundary detection.
     */
    data class EntryData(
        val id: CredentialsId,
        val sealed: AeadCipher.SealedBlob,
        val plaintextLen: Int,
    )
}
