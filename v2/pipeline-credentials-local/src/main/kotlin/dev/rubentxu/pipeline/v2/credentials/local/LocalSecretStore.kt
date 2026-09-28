package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceNotFoundException
import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceTypeMismatchException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import org.bouncycastle.crypto.engines.AESWrapEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

    /** Shared wire types of the store file format. */
    /**
     * Header data for the store.
     */
    data class StoreHeader(
        val magic: ByteArray,
        val version: Short,
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
    /**
     * V2 entry data: id + sealed blob + kind ID for v2 format.
     */
    data class V2EntryData(
        val id: CredentialsId,
        val sealed: AeadCipher.SealedBlob,
        val kindId: Short,
    )

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
        private const val VERSION_V1: Short = 1
        private const val VERSION_V2: Short = 2

        /** OWASP floor KDF params (2023 recommendation) */
        const val OWASP_M = 19456  // KiB
        const val OWASP_T = 2       // iterations
        const val OWASP_P = 1       // parallelism
        const val SALT_SIZE = 16
        const val DEK_SIZE = 32
        // AES-KWP (RFC 3394) output = input + 8 bytes (64-bit IV/wrap vector)
        const val WRAPPED_DEK_SIZE = DEK_SIZE + 8  // = 40 bytes

        // Kind IDs for v2 envelope (stored as first 2 bytes after version)
        private const val KIND_SECRET_TEXT: Short = 1
        private const val KIND_USERNAME_PASSWORD: Short = 2
        private const val KIND_SSH_PRIVATE_KEY: Short = 3
        private const val KIND_SECRET_FILE: Short = 4
        private const val KIND_CERTIFICATE: Short = 5
        private const val KIND_ZIP: Short = 6
        private const val KIND_USERNAME_COLON_PASSWORD: Short = 7

        /**
         * Payload marker of a part that links to another credential. The
         * referenced id follows the marker, without a length prefix of its own.
         */
        private const val LINKED_REF_MARKER: Int = -1

        /** Payload marker of a part that is declared but has no value. */
        private const val ABSENT_MARKER: Int = 0

        /**
         * Maps a Credential simple name to a kind ID.
         */
        fun kindIdFor(credential: Credential): Short = when (credential) {
            is SecretText -> KIND_SECRET_TEXT
            is dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword -> KIND_USERNAME_PASSWORD
            is dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey -> KIND_SSH_PRIVATE_KEY
            is dev.rubentxu.pipeline.v2.domain.credentials.SecretFile -> KIND_SECRET_FILE
            is dev.rubentxu.pipeline.v2.domain.credentials.Certificate -> KIND_CERTIFICATE
            is dev.rubentxu.pipeline.v2.domain.credentials.Zip -> KIND_ZIP
            is dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword -> KIND_USERNAME_COLON_PASSWORD
        }

        /**
         * Maps a kind ID to a Credential simple name.
         */
        fun kindNameFor(kindId: Short): String = when (kindId) {
            KIND_SECRET_TEXT -> "SecretText"
            KIND_USERNAME_PASSWORD -> "UsernamePassword"
            KIND_SSH_PRIVATE_KEY -> "SshPrivateKey"
            KIND_SECRET_FILE -> "SecretFile"
            KIND_CERTIFICATE -> "Certificate"
            KIND_ZIP -> "Zip"
            KIND_USERNAME_COLON_PASSWORD -> "UsernameColonPassword"
            else -> "Unknown"
        }

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
            val version = buf.short
            if (version != VERSION_V1.toShort() && version != VERSION_V2.toShort()) {
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

        /** Header size v1: magic(4) + version(2) + m(4) + t(4) + p(4) + salt(16) + wrappedDEK(40) = 74 */
        const val HEADER_SIZE_V1 = 4 + 2 + 4 + 4 + 4 + SALT_SIZE + WRAPPED_DEK_SIZE
        /** Header size v2: same as v1 - version is 2 bytes in both */
        const val HEADER_SIZE_V2 = HEADER_SIZE_V1
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
     * Returns the path to the lock file for this store.
     */
    private fun lockFile(): Path = file.resolveSibling(file.fileName.toString() + ".lock")

    /**
     * Executes a mutation operation with an exclusive file lock.
     * Uses synchronized for JVM-level mutual exclusion (handles same-JVM thread contention)
     * and FileChannel.lock() for OS-level inter-process locking (handles crash consistency).
     */
    private val mutationLock = Any()

    private inline fun <T> withExclusiveLock(block: () -> T): T {
        synchronized(mutationLock) {
            // Inside synchronized block, perform the actual file mutation with file-level locking
            // The synchronized ensures same-JVM threads don't conflict
            // FileChannel.lock() provides OS-level inter-process locking for crash consistency
            val lock = lockFile()
            val channel = FileChannel.open(lock,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            try {
                // Acquire exclusive OS-level lock (blocks until available)
                channel.lock().use {
                    return block()
                }
            } finally {
                channel.close()
            }
        }
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

        withExclusiveLock {
            val existingFileBytes = if (Files.exists(file) && Files.size(file) > 0) Files.readAllBytes(file) else null
            val hdr = if (existingFileBytes != null) {
                readHeader(existingFileBytes)
            } else {
                // Fresh V1 store for legacy backward-compat contract
                val salt = ByteArray(SALT_SIZE); secureRandom.nextBytes(salt)
                StoreHeader(MAGIC, VERSION_V1, OWASP_M, OWASP_T, OWASP_P, salt, ByteArray(WRAPPED_DEK_SIZE))
            }
            val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)

            // Load existing DEK from header, or generate new one for a FRESH store only.
            val dek = if (hdr.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
                ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
            } else {
                CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)
            }

            val wrappedDek = CredentialCrypto.wrapDek(kek, dek)
            val aad = CredentialCrypto.buildAad(hdr, id)
            val sealed = AeadCipher.encrypt(dek, bytes, aad)

            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, wrappedDek)

            // Parse existing entries and rebuild with new/updated entry
            if (existingFileBytes != null && existingFileBytes.size > HEADER_SIZE_V1) {
                val existingEntries = CredentialCrypto.readEntriesV1(existingFileBytes).toMutableMap()
                existingEntries[id.value] = EntryData(id, sealed, bytes.size)
                for ((_, entryData) in existingEntries) {
                    out.write(CredentialCrypto.encodeEntry(entryData.id, entryData.sealed))
                }
            } else {
                // New store: just write the single entry
                out.write(CredentialCrypto.encodeEntry(id, sealed))
            }

            val written = out.toByteArray()
            Files.write(tempFile, written)
            CredentialsStorePosix.setFilePermissions(tempFile)
            tempFile.toFile().renameTo(file.toFile())
        }
    }

    /**
     * Stores a typed credential (ML-R6) in v2 format.
     * On first add() to a v1 file: migrates existing v1 entries to v2.
     */
    override fun add(id: CredentialsId, credential: Credential) {
        withExclusiveLock {
            val hdr = loadOrCreateHeader()
            val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)

            // Load or generate DEK
            val dek = if (hdr.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
                ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
            } else {
                CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)
            }

            // Serialize credential to bytes
            val credentialBytes = CredentialSerializer.serializeCredential(credential)
            val kindId = kindIdFor(credential)

            // Encrypt with DEK - AAD includes kind for anti-swap
            val aad = CredentialCrypto.buildV2Aad(hdr, id, kindId)
            val sealed = AeadCipher.encrypt(dek, credentialBytes, aad)

            // Atomic write
            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val existingBytes = if (Files.exists(file) && Files.size(file) > 0) Files.readAllBytes(file) else null

            val out = ByteArrayOutputStream()
            // Always write V2 header for new entries; migrate V1 entries to V2
            writeHeader(out, hdr.magic, VERSION_V2, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, CredentialCrypto.wrapDek(kek, dek))

            if (existingBytes != null && existingBytes.size > HEADER_SIZE_V1) {
                // Route to correct reader based on header version of existing file
                val existingEntries: MutableMap<String, V2EntryData> = if (hdr.version == VERSION_V1.toShort()) {
                    // Migrate V1 entries to V2: decrypt as SecretText and re-encrypt as V2
                    val v1Entries = CredentialCrypto.readEntriesV1(existingBytes)
                    v1Entries.mapValues { (entryId, v1Entry) ->
                        val v1Hdr = readHeader(existingBytes)
                        val v1Aad = CredentialCrypto.buildAad(v1Hdr, CredentialsId.from(entryId))
                        val plaintext = AeadCipher.decrypt(dek, v1Entry.sealed, v1Aad)
                        val migratedSealed = AeadCipher.encrypt(dek, plaintext, CredentialCrypto.buildV2Aad(hdr, CredentialsId.from(entryId), KIND_SECRET_TEXT))
                        V2EntryData(CredentialsId.from(entryId), migratedSealed, KIND_SECRET_TEXT)
                    }.toMutableMap()
                } else {
                    CredentialCrypto.readEntries(existingBytes).toMutableMap()
                }
                existingEntries[id.value] = V2EntryData(id, sealed, kindId)
                for ((_, entryData) in existingEntries) {
                    out.write(CredentialCrypto.encodeV2Entry(entryData))
                }
            } else {
                out.write(CredentialCrypto.encodeV2Entry(V2EntryData(id, sealed, kindId)))
            }

            val written = out.toByteArray()
            Files.write(tempFile, written)
            CredentialsStorePosix.setFilePermissions(tempFile)
            tempFile.toFile().renameTo(file.toFile())
        }
    }

    /**
     * Retrieves a typed credential (ML-R6).
     * Handles both v1 (back-compat) and v2 formats.
     */
    override fun get(id: CredentialsId): Credential {
        if (!Files.exists(file)) {
            throw SecretStoreTamperException("Store file does not exist")
        }
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)
        val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)

        // Route to correct reader based on header version
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            CredentialCrypto.readEntriesV1(fileBytes)
        } else {
            CredentialCrypto.readEntries(fileBytes)
        }
        val entry = entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Check if v1 or v2 entry
        if (hdr.version == VERSION_V1.toShort()) {
            // v1 entry - decrypt and return as SecretText
            val v1Entry = entry as EntryData
            val aad = CredentialCrypto.buildAad(hdr, id)
            val plaintext = try {
                AeadCipher.decrypt(dek, v1Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
            }
            return SecretText(id, CredentialScope.GLOBAL, plaintext)
        } else {
            // v2 entry - decrypt and deserialize
            val v2Entry = entry as V2EntryData
            val aad = CredentialCrypto.buildV2Aad(hdr, id, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
            }
            return CredentialSerializer.deserializeCredential(plaintext, v2Entry.kindId, id)
        }
    }

    /**
     * Retrieves a credential as SecretHandle (v1 compatibility).
     * Full implementation - same as ML-R4 behavior.
     */
    override fun getAsSecretHandle(id: CredentialsId): SecretHandle {
        if (!Files.exists(file)) {
            throw SecretStoreTamperException("Store file does not exist")
        }
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)

        // Route to correct reader based on header version (add() stores in V2 format)
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            CredentialCrypto.readEntriesV1(fileBytes)
        } else {
            CredentialCrypto.readEntries(fileBytes)
        }
        val entry = entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Decrypt DEK: the DEK is stored in the header (wrapped with KEK)
        val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)

        // Decrypt secret - build AAD based on version
        val plaintext = try {
            if (hdr.version == VERSION_V1.toShort()) {
                // V1 entry: decrypt using V1 AAD
                val v1Entry = entry as? EntryData ?: throw ClassCastException("Expected EntryData but got ${entry::class.simpleName}")
                val aad = CredentialCrypto.buildAad(hdr, id)
                AeadCipher.decrypt(dek, v1Entry.sealed, aad)
            } else {
                // V2 entry: decrypt using V2 AAD with kindId
                val v2Entry = entry as? V2EntryData ?: throw ClassCastException("Expected V2EntryData but got ${entry::class.simpleName}")
                val aad = CredentialCrypto.buildV2Aad(hdr, id, v2Entry.kindId)
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
        }

        return SecretHandle.secret(plaintext)
    }

    /**
     * Retrieves a specific part of a multipart credential as a SecretHandle.
     *
     * For file-based kinds (SecretFile, Certificate, Zip), this returns the raw part bytes.
     * For LinkedSecretRef parts, this resolves the reference and returns the SecretText part
     * of the referenced credential.
     *
     * @param id The credential ID
     * @param partName The name of the part to retrieve (e.g., "password", "privateKey")
     * @return SecretHandle wrapping the part bytes
     * @throws LinkedSecretReferenceNotFoundException if the credential or referenced credential does not exist
     * @throws LinkedSecretReferenceTypeMismatchException if the referenced credential is not SecretText
     * @throws SecretStoreTamperException if the credential is tampered
     */
    override fun getAsHandle(id: CredentialsId, partName: String): SecretHandle {
        if (!Files.exists(file)) {
            throw SecretStoreTamperException("Store file does not exist")
        }
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)
        val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)

        // Route based on header version
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            throw SecretStoreTamperException("getAsHandle not supported for v1 single-blob entries")
        } else {
            CredentialCrypto.readEntries(fileBytes)
        }
        val entry = entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        val v2Entry = entry as V2EntryData

        val aad = CredentialCrypto.buildV2Aad(hdr, id, v2Entry.kindId)
        val plaintext = try {
            AeadCipher.decrypt(dek, v2Entry.sealed, aad)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
        }

        // Deserialize and extract the requested part; use visited set for cycle detection
        return extractPart(plaintext, v2Entry.kindId, id, partName, dek, mutableSetOf(id))
    }

    /**
     * Extracts a named part from deserialized credential bytes using the canonical part format.
     * Format per part: [nameLen:1][name][len:4 BE][bytes]
     * After reading nameLen and name, position is at len(4). We must also skip len to reach bytes.
     */
    private fun extractPart(
        plaintext: ByteArray,
        kindId: Short,
        id: CredentialsId,
        partName: String,
        dek: ByteArray,
        visited: MutableSet<CredentialsId> = mutableSetOf(id)
    ): SecretHandle = try {
        readPart(plaintext, kindId, partName, id, visited)
    } catch (e: CredentialPartReader.MalformedEnvelopeException) {
        // The reader reports a malformed envelope; callers of the store catch
        // SecretStoreTamperException, which stays the public type.
        throw SecretStoreTamperException(e.message ?: "Malformed envelope", e)
    }

    private fun readPart(
        plaintext: ByteArray,
        kindId: Short,
        partName: String,
        id: CredentialsId,
        visited: MutableSet<CredentialsId>,
    ): SecretHandle {
        val buf = CredentialPartReader.bufferOver(plaintext)
        return when (kindId) {
            KIND_SECRET_TEXT -> {
                requirePart(partName == "value", partName, "SecretText")
                buf.get().toInt() // partCount
                SecretHandle.secret(CredentialPartReader.readInlinePart(buf, 0))
            }
            KIND_USERNAME_PASSWORD -> {
                buf.get().toInt() // partCount
                SecretHandle.secret(readPositionalPart(buf, partName, "UsernamePassword", "username" to 0, "password" to 1))
            }
            KIND_SSH_PRIVATE_KEY -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "username", "privateKey" ->
                        SecretHandle.secret(readPositionalPart(buf, partName, "SshPrivateKey", "username" to 0, "privateKey" to 1))
                    "passphrase" -> {
                        CredentialPartReader.skipPart(buf) // skip username part
                        CredentialPartReader.skipPart(buf) // skip privateKey part
                        val refId = CredentialPartReader.readLinkedRefPart(buf, "passphrase")
                        requireLinkedRef(refId, "Credential has no passphrase part")
                        return resolveLinkedSecretRef(refId!!, id, visited)
                    }
                    else -> throw notFound(partName, "SshPrivateKey")
                }
            }
            KIND_SECRET_FILE -> {
                buf.get().toInt() // partCount
                SecretHandle.secret(readPositionalPart(buf, partName, "SecretFile", "originalName" to 0, "content" to 1))
            }
            KIND_CERTIFICATE -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "keystore", "alias" ->
                        SecretHandle.secret(readPositionalPart(buf, partName, "Certificate", "keystore" to 0, "alias" to 1))
                    "password" -> {
                        CredentialPartReader.skipPart(buf) // skip keystore part
                        CredentialPartReader.skipPart(buf) // skip alias part
                        val refId = CredentialPartReader.readLinkedRefPart(buf, "password")
                        requireLinkedRef(refId, "Credential has no password part")
                        return resolveLinkedSecretRef(refId!!, id, visited)
                    }
                    else -> throw notFound(partName, "Certificate")
                }
            }
            KIND_ZIP -> readZipPart(buf, partName)
            KIND_USERNAME_COLON_PASSWORD -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "username", "password" ->
                        SecretHandle.secret(readPositionalPart(buf, partName, "UsernameColonPassword", "username" to 0, "password" to 1))
                    "value" -> {
                        // Read username part: readInlinePart returns the username value bytes
                        val usernameBytes = CredentialPartReader.readPartValue(buf)
                        // Read password length from header (pass name follows username value)
                        val passLen = CredentialPartReader.readPartHeader(buf)
                        // Read password value bytes (do NOT skip — we're at the value already)
                        val passwordBytes = ByteArray(passLen); buf.get(passwordBytes)
                        // Join with ASCII colon (0x3A)
                        SecretHandle.secret(usernameBytes + byteArrayOf(0x3A) + passwordBytes)
                    }
                    else -> throw notFound(partName, "UsernameColonPassword")
                }
            }
            else -> throw SecretStoreTamperException("Unknown credential kind: $kindId")
        }
    }

    /**
     * Reads a part whose position within its credential is fixed, as opposed to
     * the Zip case where the name is searched for.
     */
    private fun readPositionalPart(
        buf: ByteBuffer,
        partName: String,
        credential: String,
        vararg orderedParts: Pair<String, Int>,
    ): ByteArray {
        val offset = CredentialPartReader.partOffset(partName, *orderedParts)
            ?: throw notFound(partName, credential)
        return CredentialPartReader.readInlinePart(buf, offset)
    }

    /**
     * Finds a named entry in a Zip credential. Entries are keyed by name
     * rather than by a fixed position, so the reader walks them.
     */
    private fun readZipPart(buf: ByteBuffer, partName: String): SecretHandle {
        val partCount = buf.get().toInt()
        // metadata: _entryCount (normal)
        val metaNameLen = buf.get().toInt()
        buf.position(buf.position() + metaNameLen)
        buf.int // skip count value
        buf.get() // consume metadata content byte (1)

        repeat(partCount - 1) {
            val entryNameLen = buf.get().toInt()
            val entryNameBytes = ByteArray(entryNameLen); buf.get(entryNameBytes)
            val entryName = String(entryNameBytes, Charsets.UTF_8)
            val entryLen = buf.int
            val entryBytes = ByteArray(entryLen); buf.get(entryBytes)

            if (entryName == partName) {
                return SecretHandle.secret(entryBytes)
            }
        }
        throw notFound(partName, "Zip")
    }

    private fun notFound(partName: String, credential: String) =
        SecretStoreTamperException("Part '$partName' not found in $credential credential")

    private fun requirePart(matches: Boolean, partName: String, credential: String) {
        if (!matches) throw notFound(partName, credential)
    }

    /**
     * A linked-ref part must carry a reference. [readLinkedRefPart] already
     * raises for the absent marker, so this is the null-safety net that lets the
     * caller pass the result straight to [resolveLinkedSecretRef].
     */
    private fun requireLinkedRef(refId: CredentialsId?, absentMessage: String) {
        if (refId == null) throw SecretStoreTamperException(absentMessage)
    }

    /**
     * Resolves a LinkedSecretRef by getting the SecretText part of the referenced credential.
     * Uses a visited set with depth cap ≤16 to detect circular linked secret references.
     * For SshPrivateKey/Certificate passphrase/password LinkedSecretRefs, this method
     * traverses through intermediate credentials following the ref chain until SecretText is found.
     */
    private fun resolveLinkedSecretRef(refId: CredentialsId, originalId: CredentialsId, visited: MutableSet<CredentialsId> = mutableSetOf()): SecretHandle {
        // Cycle detection: if refId is already in visited set, we have a circular reference
        if (refId in visited) {
            throw SecretStoreTamperException("Circular linked secret reference detected: ${visited.joinToString(" → ") { it.value }} → ${refId.value}")
        }
        // Depth cap: if visited set is large, we've hit a long chain (potential cycle or deep ref)
        if (visited.size >= 16) {
            throw SecretStoreTamperException("Linked secret reference chain exceeds maximum depth of 16")
        }

        visited.add(refId)

        if (!Files.exists(file)) {
            throw LinkedSecretReferenceNotFoundException(refId)
        }
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)
        val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)

        // Route to correct reader based on header version
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            CredentialCrypto.readEntriesV1(fileBytes)
        } else {
            CredentialCrypto.readEntries(fileBytes)
        }
        val refEntry = entries[refId.value]
            ?: throw LinkedSecretReferenceNotFoundException(refId)

        val v2Entry: V2EntryData
        try {
            v2Entry = refEntry as V2EntryData
        } catch (e: ClassCastException) {
            throw LinkedSecretReferenceTypeMismatchException(refId, "SecretText", "Unknown (v1 format not supported for linked refs)")
        }

        // For SecretText: directly extract and return the value
        if (v2Entry.kindId == KIND_SECRET_TEXT) {
            val aad = CredentialCrypto.buildV2Aad(hdr, refId, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
            }
            // Extract the "value" part from SecretText using canonical format
            val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
            val partCount = buf.get().toInt()
            val nameLen = buf.get().toInt()
            if (nameLen > buf.remaining()) {
                throw SecretStoreTamperException("Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
            }
            buf.position(buf.position() + nameLen) // skip name "value"
            val valueLen = buf.int
            val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
            return SecretHandle.secret(valueBytes)
        }

        // For SshPrivateKey: follow the passphrase LinkedSecretRef chain
        if (v2Entry.kindId == KIND_SSH_PRIVATE_KEY) {
            val aad = CredentialCrypto.buildV2Aad(hdr, refId, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
            }
            return extractLinkedRefFromSshPrivateKey(refId, plaintext, visited)
        }

        // For Certificate: follow the password LinkedSecretRef chain
        if (v2Entry.kindId == KIND_CERTIFICATE) {
            val aad = CredentialCrypto.buildV2Aad(hdr, refId, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
            }
            return extractLinkedRefFromCertificate(refId, plaintext, visited)
        }

        // Other credential types cannot be the target of a LinkedSecretRef for passphrase/password
        throw LinkedSecretReferenceTypeMismatchException(refId, "SecretText", kindNameFor(v2Entry.kindId))
    }

    /**
     * Extracts the passphrase LinkedSecretRef from a SshPrivateKey and resolves it.
     */
    private fun extractLinkedRefFromSshPrivateKey(refId: CredentialsId, plaintext: ByteArray, visited: MutableSet<CredentialsId>): SecretHandle {
        val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        val partCount = buf.get().toInt()
        // Skip username part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) throw SecretStoreTamperException("Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Skip privateKey part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) throw SecretStoreTamperException("Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Now at passphrase header: nameLen(1) + name + 4-byte marker
        val passphraseNameLen = buf.get().toInt() and 0xFF
        if (passphraseNameLen > buf.remaining()) {
            throw SecretStoreTamperException("Malformed envelope: nameLen($passphraseNameLen) exceeds remaining bytes(${buf.remaining()})")
        }
        buf.position(buf.position() + passphraseNameLen)
        val marker = buf.int
        when (marker) {
            0xFFFFFFFF.toInt() -> {
                val refIdLen = buf.int
                val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                val nextRefId = CredentialsId.from(String(refIdBytes, Charsets.UTF_8))
                return resolveLinkedSecretRef(nextRefId, refId, visited)
            }
            0x00000000.toInt() -> {
                throw SecretStoreTamperException("Credential has no passphrase part")
            }
            else -> throw SecretStoreTamperException("SshPrivateKey passphrase must be linked-ref or absent")
        }
    }

    /**
     * Extracts the password LinkedSecretRef from a Certificate and resolves it.
     */
    private fun extractLinkedRefFromCertificate(refId: CredentialsId, plaintext: ByteArray, visited: MutableSet<CredentialsId>): SecretHandle {
        val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        val partCount = buf.get().toInt()
        // Skip keystore part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) throw SecretStoreTamperException("Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Skip alias part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) throw SecretStoreTamperException("Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Now at password header: nameLen(1) + name + 4-byte marker
        val pwNameLen = buf.get().toInt() and 0xFF
        if (pwNameLen > buf.remaining()) {
            throw SecretStoreTamperException("Malformed envelope: nameLen($pwNameLen) exceeds remaining bytes(${buf.remaining()})")
        }
        buf.position(buf.position() + pwNameLen)
        val marker = buf.int
        when (marker) {
            0xFFFFFFFF.toInt() -> {
                val pwRefIdLen = buf.int
                val pwRefIdBytes = ByteArray(pwRefIdLen); buf.get(pwRefIdBytes)
                val nextRefId = CredentialsId.from(String(pwRefIdBytes, Charsets.UTF_8))
                return resolveLinkedSecretRef(nextRefId, refId, visited)
            }
            0x00000000.toInt() -> {
                throw SecretStoreTamperException("Credential has no password part")
            }
            else -> throw SecretStoreTamperException("Certificate password must be linked-ref or absent")
        }
    }

    /**
     * Lists all credential IDs in the store.
     *
     * @return List of credential IDs (never returns values)
     */
    override fun list(): List<CredentialsId> {
        if (!Files.exists(file) || Files.size(file) == 0L) return emptyList()
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        // Route based on header version
        val entries = if (hdr.version == VERSION_V1.toShort()) {
            CredentialCrypto.readEntriesV1(fileBytes)
        } else {
            CredentialCrypto.readEntries(fileBytes)
        }
        return entries.keys.map { CredentialsId.from(it) }
    }

    /**
     * Removes a credential.
     *
     * @param id The credential ID to remove
     */
    override fun remove(id: CredentialsId) {
        withExclusiveLock {
            if (!Files.exists(file)) return
            val fileBytes = Files.readAllBytes(file)
            val hdr = readHeader(fileBytes)

            // Route based on header version
            val entries: MutableMap<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
                CredentialCrypto.readEntriesV1(fileBytes).toMutableMap()
            } else {
                CredentialCrypto.readEntries(fileBytes).toMutableMap()
            }
            entries.remove(id.value) ?: return // Not found, no-op

            // Rewrite without the removed entry
            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
            for ((_, entryData) in entries) {
                when (entryData) {
                    is EntryData -> out.write(CredentialCrypto.encodeEntry(entryData.id, entryData.sealed))
                    is V2EntryData -> out.write(CredentialCrypto.encodeV2Entry(entryData))
                }
            }
            Files.write(tempFile, out.toByteArray())
            CredentialsStorePosix.setFilePermissions(tempFile)
            tempFile.toFile().renameTo(file.toFile())
        }
    }

    /**
     * Re-encrypts a credential with new bytes, preserving the DEK.
     *
     * Replaces the existing credential with a new one of the same ID but new content.
     * Uses the same DEK (from the store header) to re-encrypt the new credential.
     * This preserves the ability to decrypt existing entries while updating the credential.
     *
     * @param id The credential ID (must already exist)
     * @param credential The new typed credential to store
     * @throws SecretStoreTamperException if the credential doesn't exist
     */
    override fun rotate(id: CredentialsId, credential: Credential) {
        withExclusiveLock {
            if (!Files.exists(file)) {
                throw SecretStoreTamperException("Store file does not exist")
            }
            if (Files.size(file) == 0L) {
                throw SecretStoreTamperException("Credential not found: ${id.value}")
            }

            val fileBytes = Files.readAllBytes(file)
            val hdr = readHeader(fileBytes)
            val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)

            // Route based on header version; rotate always upgrades to v2
            val entries: MutableMap<String, V2EntryData> = if (hdr.version == VERSION_V1.toShort()) {
                // Migrate V1 entries to V2
                val v1Entries = CredentialCrypto.readEntriesV1(fileBytes)
                val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)
                v1Entries.mapValues { (entryId, v1Entry) ->
                    val v1Aad = CredentialCrypto.buildAad(hdr, CredentialsId.from(entryId))
                    val plaintext = AeadCipher.decrypt(dek, v1Entry.sealed, v1Aad)
                    val migratedSealed = AeadCipher.encrypt(dek, plaintext, CredentialCrypto.buildV2Aad(hdr, CredentialsId.from(entryId), KIND_SECRET_TEXT))
                    V2EntryData(CredentialsId.from(entryId), migratedSealed, KIND_SECRET_TEXT)
                }.toMutableMap()
            } else {
                CredentialCrypto.readEntries(fileBytes).toMutableMap()
            }

            if (!entries.containsKey(id.value)) {
                throw SecretStoreTamperException("Credential not found: ${id.value}")
            }

            // Get DEK from header (reuse existing DEK)
            val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)

            // Serialize and encrypt the new credential
            val credentialBytes = CredentialSerializer.serializeCredential(credential)
            val kindId = kindIdFor(credential)
            val aad = CredentialCrypto.buildV2Aad(hdr, id, kindId)
            val sealed = AeadCipher.encrypt(dek, credentialBytes, aad)

            // Update entries
            entries[id.value] = V2EntryData(id, sealed, kindId)

            // Atomic write - always write V2 header
            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, VERSION_V2, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
            for ((_, entryData) in entries) {
                out.write(CredentialCrypto.encodeV2Entry(entryData))
            }
            Files.write(tempFile, out.toByteArray())
            CredentialsStorePosix.setFilePermissions(tempFile)
            tempFile.toFile().renameTo(file.toFile())
        }
    }

    /**
     * Rotates a credential with new bytes, preserving the DEK (v1 compatibility).
     * Full implementation - same as ML-R4 behavior.
     */
    override fun rotateBytes(id: CredentialsId, newBytes: ByteArray) {
        if (newBytes.isEmpty()) throw CredentialsStoreEmptySecretException()
        if (!Files.exists(file)) {
            put(id, newBytes)
            return
        }
        val fileBytes = Files.readAllBytes(file)
        val hdr = readHeader(fileBytes)
        val kek = CredentialCrypto.deriveKek(passphrase, hdr.kdfSalt)

        val entries = CredentialCrypto.readEntriesV1(fileBytes)
        entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Decrypt existing DEK from header
        val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)
        val aad = CredentialCrypto.buildAad(hdr, id)
        val sealed = AeadCipher.encrypt(dek, newBytes, aad)

        // Update entry
        val updatedEntries = entries.toMutableMap()
        updatedEntries[id.value] = EntryData(id, sealed, newBytes.size)

        val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
        val out = ByteArrayOutputStream()
        writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
        for ((_, entryData) in updatedEntries) {
            out.write(CredentialCrypto.encodeEntry(entryData.id, entryData.sealed))
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
        // Create new header with fresh salt - use V2 format for new stores
        val salt = ByteArray(SALT_SIZE)
        secureRandom.nextBytes(salt)
        return StoreHeader(MAGIC, VERSION_V2, OWASP_M, OWASP_T, OWASP_P, salt, ByteArray(WRAPPED_DEK_SIZE))
    }

    private fun writeHeader(
        out: ByteArrayOutputStream,
        magic: ByteArray,
        version: Short,
        m: Int,
        t: Int,
        p: Int,
        salt: ByteArray,
        wrappedDek: ByteArray,
    ) {
        out.write(magic)
        val versionBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(version)
        out.write(versionBuf.array())
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

}
