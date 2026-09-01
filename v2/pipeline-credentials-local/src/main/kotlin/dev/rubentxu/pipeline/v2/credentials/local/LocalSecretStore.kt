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
            val kek = deriveKek(hdr.kdfSalt)

            // Load existing DEK from header, or generate new one for a FRESH store only.
            val dek = if (hdr.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
                ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
            } else {
                unwrapDek(kek, hdr.wrappedDek)
            }

            val wrappedDek = wrapDek(kek, dek)
            val aad = buildAad(hdr, id)
            val sealed = AeadCipher.encrypt(dek, bytes, aad)

            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, wrappedDek)

            // Parse existing entries and rebuild with new/updated entry
            if (existingFileBytes != null && existingFileBytes.size > HEADER_SIZE_V1) {
                val existingEntries = readEntriesV1(existingFileBytes).toMutableMap()
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
    }

    /**
     * Stores a typed credential (ML-R6) in v2 format.
     * On first add() to a v1 file: migrates existing v1 entries to v2.
     */
    override fun add(id: CredentialsId, credential: Credential) {
        withExclusiveLock {
            val hdr = loadOrCreateHeader()
            val kek = deriveKek(hdr.kdfSalt)

            // Load or generate DEK
            val dek = if (hdr.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
                ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
            } else {
                unwrapDek(kek, hdr.wrappedDek)
            }

            // Serialize credential to bytes
            val credentialBytes = serializeCredential(credential)
            val kindId = kindIdFor(credential)

            // Encrypt with DEK - AAD includes kind for anti-swap
            val aad = buildV2Aad(hdr, id, kindId)
            val sealed = AeadCipher.encrypt(dek, credentialBytes, aad)

            // Atomic write
            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val existingBytes = if (Files.exists(file) && Files.size(file) > 0) Files.readAllBytes(file) else null

            val out = ByteArrayOutputStream()
            // Always write V2 header for new entries; migrate V1 entries to V2
            writeHeader(out, hdr.magic, VERSION_V2, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, wrapDek(kek, dek))

            if (existingBytes != null && existingBytes.size > HEADER_SIZE_V1) {
                // Route to correct reader based on header version of existing file
                val existingEntries: MutableMap<String, V2EntryData> = if (hdr.version == VERSION_V1.toShort()) {
                    // Migrate V1 entries to V2: decrypt as SecretText and re-encrypt as V2
                    val v1Entries = readEntriesV1(existingBytes)
                    v1Entries.mapValues { (entryId, v1Entry) ->
                        val v1Hdr = readHeader(existingBytes)
                        val v1Aad = buildAad(v1Hdr, CredentialsId.from(entryId))
                        val plaintext = AeadCipher.decrypt(dek, v1Entry.sealed, v1Aad)
                        val migratedSealed = AeadCipher.encrypt(dek, plaintext, buildV2Aad(hdr, CredentialsId.from(entryId), KIND_SECRET_TEXT))
                        V2EntryData(CredentialsId.from(entryId), migratedSealed, KIND_SECRET_TEXT)
                    }.toMutableMap()
                } else {
                    readEntries(existingBytes).toMutableMap()
                }
                existingEntries[id.value] = V2EntryData(id, sealed, kindId)
                for ((_, entryData) in existingEntries) {
                    out.write(encodeV2Entry(entryData))
                }
            } else {
                out.write(encodeV2Entry(V2EntryData(id, sealed, kindId)))
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
        val kek = deriveKek(hdr.kdfSalt)
        val dek = unwrapDek(kek, hdr.wrappedDek)

        // Route to correct reader based on header version
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            readEntriesV1(fileBytes)
        } else {
            readEntries(fileBytes)
        }
        val entry = entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Check if v1 or v2 entry
        if (hdr.version == VERSION_V1.toShort()) {
            // v1 entry - decrypt and return as SecretText
            val v1Entry = entry as EntryData
            val aad = buildAad(hdr, id)
            val plaintext = try {
                AeadCipher.decrypt(dek, v1Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
            }
            return SecretText(id, CredentialScope.GLOBAL, plaintext)
        } else {
            // v2 entry - decrypt and deserialize
            val v2Entry = entry as V2EntryData
            val aad = buildV2Aad(hdr, id, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
            }
            return deserializeCredential(plaintext, v2Entry.kindId, id)
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
        val kek = deriveKek(hdr.kdfSalt)

        // Route to correct reader based on header version (add() stores in V2 format)
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            readEntriesV1(fileBytes)
        } else {
            readEntries(fileBytes)
        }
        val entry = entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Decrypt DEK: the DEK is stored in the header (wrapped with KEK)
        val dek = unwrapDek(kek, hdr.wrappedDek)

        // Decrypt secret - build AAD based on version
        val plaintext = try {
            if (hdr.version == VERSION_V1.toShort()) {
                // V1 entry: decrypt using V1 AAD
                val v1Entry = entry as? EntryData ?: throw ClassCastException("Expected EntryData but got ${entry::class.simpleName}")
                val aad = buildAad(hdr, id)
                AeadCipher.decrypt(dek, v1Entry.sealed, aad)
            } else {
                // V2 entry: decrypt using V2 AAD with kindId
                val v2Entry = entry as? V2EntryData ?: throw ClassCastException("Expected V2EntryData but got ${entry::class.simpleName}")
                val aad = buildV2Aad(hdr, id, v2Entry.kindId)
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
        val kek = deriveKek(hdr.kdfSalt)
        val dek = unwrapDek(kek, hdr.wrappedDek)

        // Route based on header version
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            throw SecretStoreTamperException("getAsHandle not supported for v1 single-blob entries")
        } else {
            readEntries(fileBytes)
        }
        val entry = entries[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        val v2Entry = entry as V2EntryData

        val aad = buildV2Aad(hdr, id, v2Entry.kindId)
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
    ): SecretHandle {
        val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)

        // ── helpers ────────────────────────────────────────────────────────────
        // Read nameLen(1) + name(nameLen) + 4-byte length field, return value length,
        // advance to start of value bytes.
        fun readValueLen(buf: ByteBuffer): Int {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) {
                throw SecretStoreTamperException(
                    "Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
            }
            buf.position(buf.position() + nameLen)
            return buf.int // big-endian, unsigned-safe; position now at value bytes
        }

        // Skip nameLen(1) + name(nameLen) + 4-byte length + valueLen bytes.
        // Returns the value length for the caller's use.
        fun skipPart(buf: ByteBuffer): Int {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) {
                throw SecretStoreTamperException(
                    "Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
            }
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int
            buf.position(buf.position() + valueLen)
            return valueLen
        }

        return when (kindId) {
            KIND_SECRET_TEXT -> {
                if (partName != "value") {
                    throw SecretStoreTamperException("Part '$partName' not found in SecretText credential")
                }
                buf.get().toInt() // partCount
                val valueLen = readValueLen(buf)
                val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                SecretHandle.secret(valueBytes)
            }
            KIND_USERNAME_PASSWORD -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "username" -> {
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "password" -> {
                        skipPart(buf) // skip username part
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    else -> throw SecretStoreTamperException("Part '$partName' not found in UsernamePassword credential")
                }
            }
            KIND_SSH_PRIVATE_KEY -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "username" -> {
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "privateKey" -> {
                        skipPart(buf) // skip username part
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "passphrase" -> {
                        skipPart(buf) // skip username part
                        skipPart(buf) // skip privateKey part
                        // Now at passphrase header: nameLen(1) + name + 4-byte marker
                        val passphraseNameLen = buf.get().toInt() and 0xFF
                        buf.position(buf.position() + passphraseNameLen)
                        val marker = buf.int
                        when (marker) {
                            0xFFFFFFFF.toInt() -> {
                                val refIdLen = buf.int
                                val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                                val refId = CredentialsId.from(String(refIdBytes, Charsets.UTF_8))
                                return resolveLinkedSecretRef(refId, id, visited)
                            }
                            0x00000000.toInt() -> {
                                throw SecretStoreTamperException("Credential has no passphrase part")
                            }
                            else -> throw SecretStoreTamperException("SshPrivateKey passphrase must be linked-ref or absent")
                        }
                    }
                    else -> throw SecretStoreTamperException("Part '$partName' not found in SshPrivateKey credential")
                }
            }
            KIND_SECRET_FILE -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "originalName" -> {
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "content" -> {
                        skipPart(buf) // skip originalName part
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    else -> throw SecretStoreTamperException("Part '$partName' not found in SecretFile credential")
                }
            }
            KIND_CERTIFICATE -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "keystore" -> {
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "alias" -> {
                        skipPart(buf) // skip keystore part
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "password" -> {
                        skipPart(buf) // skip keystore part
                        skipPart(buf) // skip alias part
                        // Now at password header: nameLen(1) + name + 4-byte marker
                        val pwNameLen = buf.get().toInt() and 0xFF
                        buf.position(buf.position() + pwNameLen)
                        val pwMarker = buf.int
                        when (pwMarker) {
                            0xFFFFFFFF.toInt() -> {
                                val refIdLen = buf.int
                                val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                                val refId = CredentialsId.from(String(refIdBytes, Charsets.UTF_8))
                                return resolveLinkedSecretRef(refId, id, visited)
                            }
                            0x00000000.toInt() -> {
                                throw SecretStoreTamperException("Credential has no password part")
                            }
                            else -> throw SecretStoreTamperException("Certificate password must be linked-ref or absent")
                        }
                    }
                    else -> throw SecretStoreTamperException("Part '$partName' not found in Certificate credential")
                }
            }
            KIND_ZIP -> {
                val partCount = buf.get().toInt()
                // metadata: _entryCount (normal)
                val metaNameLen = buf.get().toInt()
                buf.position(buf.position() + metaNameLen)
                buf.int // skip count value
                buf.get() // consume metadata content byte (1)

                // Find the requested entry
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
                throw SecretStoreTamperException("Part '$partName' not found in Zip credential")
            }
            KIND_USERNAME_COLON_PASSWORD -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "username" -> {
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "password" -> {
                        skipPart(buf) // skip username part
                        val valueLen = readValueLen(buf)
                        val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                        SecretHandle.secret(valueBytes)
                    }
                    "value" -> {
                        // Read username part
                        val usernameLen = readValueLen(buf)
                        val usernameBytes = ByteArray(usernameLen); buf.get(usernameBytes)
                        // Skip password part header then read password value
                        skipPart(buf)
                        val passwordLen = readValueLen(buf)
                        val passwordBytes = ByteArray(passwordLen); buf.get(passwordBytes)
                        // Join with ASCII colon (0x3A)
                        val joinedBytes = usernameBytes + byteArrayOf(0x3A) + passwordBytes
                        SecretHandle.secret(joinedBytes)
                    }
                    else -> throw SecretStoreTamperException("Part '$partName' not found in UsernameColonPassword credential")
                }
            }
            else -> throw SecretStoreTamperException("Unknown credential kind: $kindId")
        }
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
        val kek = deriveKek(hdr.kdfSalt)
        val dek = unwrapDek(kek, hdr.wrappedDek)

        // Route to correct reader based on header version
        val entries: Map<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
            readEntriesV1(fileBytes)
        } else {
            readEntries(fileBytes)
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
            val aad = buildV2Aad(hdr, refId, v2Entry.kindId)
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
            val aad = buildV2Aad(hdr, refId, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
            }
            return extractLinkedRefFromSshPrivateKey(refId, plaintext, visited)
        }

        // For Certificate: follow the password LinkedSecretRef chain
        if (v2Entry.kindId == KIND_CERTIFICATE) {
            val aad = buildV2Aad(hdr, refId, v2Entry.kindId)
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
            readEntriesV1(fileBytes)
        } else {
            readEntries(fileBytes)
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
                readEntriesV1(fileBytes).toMutableMap()
            } else {
                readEntries(fileBytes).toMutableMap()
            }
            entries.remove(id.value) ?: return // Not found, no-op

            // Rewrite without the removed entry
            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
            for ((_, entryData) in entries) {
                when (entryData) {
                    is EntryData -> out.write(encodeEntry(entryData.id, entryData.sealed))
                    is V2EntryData -> out.write(encodeV2Entry(entryData))
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
            val kek = deriveKek(hdr.kdfSalt)

            // Route based on header version; rotate always upgrades to v2
            val entries: MutableMap<String, V2EntryData> = if (hdr.version == VERSION_V1.toShort()) {
                // Migrate V1 entries to V2
                val v1Entries = readEntriesV1(fileBytes)
                val dek = unwrapDek(kek, hdr.wrappedDek)
                v1Entries.mapValues { (entryId, v1Entry) ->
                    val v1Aad = buildAad(hdr, CredentialsId.from(entryId))
                    val plaintext = AeadCipher.decrypt(dek, v1Entry.sealed, v1Aad)
                    val migratedSealed = AeadCipher.encrypt(dek, plaintext, buildV2Aad(hdr, CredentialsId.from(entryId), KIND_SECRET_TEXT))
                    V2EntryData(CredentialsId.from(entryId), migratedSealed, KIND_SECRET_TEXT)
                }.toMutableMap()
            } else {
                readEntries(fileBytes).toMutableMap()
            }

            if (!entries.containsKey(id.value)) {
                throw SecretStoreTamperException("Credential not found: ${id.value}")
            }

            // Get DEK from header (reuse existing DEK)
            val dek = unwrapDek(kek, hdr.wrappedDek)

            // Serialize and encrypt the new credential
            val credentialBytes = serializeCredential(credential)
            val kindId = kindIdFor(credential)
            val aad = buildV2Aad(hdr, id, kindId)
            val sealed = AeadCipher.encrypt(dek, credentialBytes, aad)

            // Update entries
            entries[id.value] = V2EntryData(id, sealed, kindId)

            // Atomic write - always write V2 header
            val tempFile = file.resolveSibling(file.fileName.toString() + ".tmp")
            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, VERSION_V2, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
            for ((_, entryData) in entries) {
                out.write(encodeV2Entry(entryData))
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
        val kek = deriveKek(hdr.kdfSalt)

        val entries = readEntriesV1(fileBytes)
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

    private fun buildV2Aad(header: StoreHeader, id: CredentialsId, kindId: Short): ByteArray {
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

    private fun encodeV2Entry(entry: V2EntryData): ByteArray {
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

    private fun readEntries(fileBytes: ByteArray): Map<String, V2EntryData> {
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
    private fun readEntriesV1(fileBytes: ByteArray): Map<String, EntryData> {
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

    // === Credential serialization ===

    // Canonical part format (D2):
    //   normal part:  [nameLen:1][name][len:4 BE][bytes]
    //   linked-ref:   [nameLen:1][name][0xFFFFFFFF:4][refIdLen:4][refId]
    //   absent opt:   [nameLen:1][name][0x00000000:4]
    private fun writeLinkedRefMarker(out: ByteArrayOutputStream) {
        val marker = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0xFFFFFFFF.toInt())
        out.write(marker.array())
    }

    private fun writeAbsentMarker(out: ByteArrayOutputStream) {
        val marker = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x00000000.toInt())
        out.write(marker.array())
    }

    private fun serializeCredential(credential: Credential): ByteArray {
        val out = ByteArrayOutputStream()
        when (credential) {
            is SecretText -> {
                out.write(1) // part count
                val partName = "value"
                out.write(partName.length)
                out.write(partName.toByteArray(Charsets.UTF_8))
                val partBytesLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.bytes.size)
                out.write(partBytesLenBuf.array())
                out.write(credential.bytes, 0, credential.bytes.size)
            }
            is dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword -> {
                out.write(2) // 2 parts
                // username part: normal
                val userPartName = "username"
                out.write(userPartName.length)
                out.write(userPartName.toByteArray(Charsets.UTF_8))
                val userBytes = credential.username.toByteArray(Charsets.UTF_8)
                val userLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(userBytes.size)
                out.write(userLenBuf.array())
                out.write(userBytes, 0, userBytes.size)
                // password part: normal
                val passPartName = "password"
                out.write(passPartName.length)
                out.write(passPartName.toByteArray(Charsets.UTF_8))
                val passLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.password.size)
                out.write(passLenBuf.array())
                out.write(credential.password, 0, credential.password.size)
            }
            is dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey -> {
                out.write(3) // 3 parts: username, privateKey, passphrase
                // username: normal
                val usernamePartName = "username"
                out.write(usernamePartName.length)
                out.write(usernamePartName.toByteArray(Charsets.UTF_8))
                val usernameBytes = credential.username.toByteArray(Charsets.UTF_8)
                val usernameLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(usernameBytes.size)
                out.write(usernameLenBuf.array())
                out.write(usernameBytes, 0, usernameBytes.size)
                // privateKey: normal
                val keyPartName = "privateKey"
                out.write(keyPartName.length)
                out.write(keyPartName.toByteArray(Charsets.UTF_8))
                val keyLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.privateKey.size)
                out.write(keyLenBuf.array())
                out.write(credential.privateKey, 0, credential.privateKey.size)
                // passphrase: linked-ref or absent
                val passphrasePartName = "passphrase"
                out.write(passphrasePartName.length)
                out.write(passphrasePartName.toByteArray(Charsets.UTF_8))
                val passphraseRef = credential.passphraseRef
                if (passphraseRef != null) {
                    writeLinkedRefMarker(out)
                    val refIdBytes = passphraseRef.credentialsId.value.toByteArray(Charsets.UTF_8)
                    val refIdLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(refIdBytes.size)
                    out.write(refIdLenBuf.array())
                    out.write(refIdBytes, 0, refIdBytes.size)
                } else {
                    writeAbsentMarker(out)
                }
            }
            is dev.rubentxu.pipeline.v2.domain.credentials.SecretFile -> {
                out.write(2) // 2 parts: originalName, content
                // originalName: normal
                val namePartName = "originalName"
                out.write(namePartName.length)
                out.write(namePartName.toByteArray(Charsets.UTF_8))
                val nameBytes = (credential.originalName ?: "").toByteArray(Charsets.UTF_8)
                val nameLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(nameBytes.size)
                out.write(nameLenBuf.array())
                out.write(nameBytes, 0, nameBytes.size)
                // content: normal
                val contentPartName = "content"
                out.write(contentPartName.length)
                out.write(contentPartName.toByteArray(Charsets.UTF_8))
                val contentLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.bytes.size)
                out.write(contentLenBuf.array())
                out.write(credential.bytes, 0, credential.bytes.size)
            }
            is dev.rubentxu.pipeline.v2.domain.credentials.Certificate -> {
                out.write(3) // 3 parts: keystore, alias, password
                // keystore: normal
                val keystorePartName = "keystore"
                out.write(keystorePartName.length)
                out.write(keystorePartName.toByteArray(Charsets.UTF_8))
                val keystoreLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.keystore.size)
                out.write(keystoreLenBuf.array())
                out.write(credential.keystore, 0, credential.keystore.size)
                // alias: normal
                val aliasPartName = "alias"
                out.write(aliasPartName.length)
                out.write(aliasPartName.toByteArray(Charsets.UTF_8))
                val aliasBytes = (credential.alias ?: "").toByteArray(Charsets.UTF_8)
                val aliasLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(aliasBytes.size)
                out.write(aliasLenBuf.array())
                out.write(aliasBytes, 0, aliasBytes.size)
                // password: linked-ref or absent
                val passwordPartName = "password"
                out.write(passwordPartName.length)
                out.write(passwordPartName.toByteArray(Charsets.UTF_8))
                val passwordRef = credential.passwordRef
                if (passwordRef != null) {
                    writeLinkedRefMarker(out)
                    val refIdBytes = passwordRef.credentialsId.value.toByteArray(Charsets.UTF_8)
                    val refIdLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(refIdBytes.size)
                    out.write(refIdLenBuf.array())
                    out.write(refIdBytes, 0, refIdBytes.size)
                } else {
                    writeAbsentMarker(out)
                }
            }
            is dev.rubentxu.pipeline.v2.domain.credentials.Zip -> {
                out.write(1 + credential.entries.size) // 1 + n parts
                // _entryCount metadata: normal
                val metaPartName = "_entryCount"
                out.write(metaPartName.length)
                out.write(metaPartName.toByteArray(Charsets.UTF_8))
                val countBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.entries.size)
                out.write(countBuf.array())
                // metadata content = 1 (as int)
                val metaContentBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1)
                out.write(metaContentBuf.array())
                for ((entryName, entryBytes) in credential.entries) {
                    val entryPartName = entryName
                    val entryNameBytes = entryPartName.toByteArray(Charsets.UTF_8)
                    if (entryNameBytes.size !in 1..255) {
                        throw SecretStoreTamperException(
                            "Zip entry name must be 1-255 UTF-8 bytes, got ${entryNameBytes.size} bytes for entry '$entryPartName'")
                    }
                    out.write(entryNameBytes.size)
                    out.write(entryNameBytes)
                    val entryLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(entryBytes.size)
                    out.write(entryLenBuf.array())
                    out.write(entryBytes, 0, entryBytes.size)
                }
            }
            is dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword -> {
                out.write(2) // 2 parts
                // username: normal
                val userPartName = "username"
                out.write(userPartName.length)
                out.write(userPartName.toByteArray(Charsets.UTF_8))
                val userBytes = credential.user.toByteArray(Charsets.UTF_8)
                val userLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(userBytes.size)
                out.write(userLenBuf.array())
                out.write(userBytes, 0, userBytes.size)
                // password: normal
                val passPartName = "password"
                out.write(passPartName.length)
                out.write(passPartName.toByteArray(Charsets.UTF_8))
                val passLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(credential.pass.size)
                out.write(passLenBuf.array())
                out.write(credential.pass, 0, credential.pass.size)
            }
        }
        return out.toByteArray()
    }

    // Reads a 4-byte big-endian marker from the buffer.
    // Returns: 0xFFFFFFFF = linked-ref, 0x00000000 = absent, other = inline byte count
    private fun readPartMarker(buf: ByteBuffer): Int {
        return buf.int
    }

    private fun deserializeCredential(bytes: ByteArray, kindId: Short, id: CredentialsId): Credential {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return when (kindId) {
            KIND_SECRET_TEXT -> {
                val partCount = buf.get().toInt()
                // name
                val nameLen = buf.get().toInt()
                buf.position(buf.position() + nameLen)
                // value (inline)
                val valueLen = readPartMarker(buf)
                val valueBytes = ByteArray(valueLen); buf.get(valueBytes)
                SecretText(id, CredentialScope.GLOBAL, valueBytes)
            }
            KIND_USERNAME_PASSWORD -> {
                val partCount = buf.get().toInt()
                // username: normal
                val userNameLen = buf.get().toInt()
                buf.position(buf.position() + userNameLen) // skip name
                val userLen = readPartMarker(buf)
                val userBytes = ByteArray(userLen); buf.get(userBytes)
                val username = String(userBytes, Charsets.UTF_8)
                // password: normal
                val passNameLen = buf.get().toInt()
                buf.position(buf.position() + passNameLen) // skip name
                val passLen = readPartMarker(buf)
                val passBytes = ByteArray(passLen); buf.get(passBytes)
                dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword(id, CredentialScope.GLOBAL, username, passBytes)
            }
            KIND_SSH_PRIVATE_KEY -> {
                val partCount = buf.get().toInt()
                // username: normal
                val userNameLen = buf.get().toInt()
                buf.position(buf.position() + userNameLen)
                val userLen = readPartMarker(buf)
                val userBytes = ByteArray(userLen); buf.get(userBytes)
                val username = String(userBytes, Charsets.UTF_8)
                // privateKey: normal
                val keyNameLen = buf.get().toInt()
                buf.position(buf.position() + keyNameLen)
                val keyLen = readPartMarker(buf)
                val keyBytes = ByteArray(keyLen); buf.get(keyBytes)
                // passphrase: linked-ref or absent
                val passphraseNameLen = buf.get().toInt()
                buf.position(buf.position() + passphraseNameLen) // skip name
                val passphraseMarker = readPartMarker(buf)
                val passphraseRef = when (passphraseMarker) {
                    0xFFFFFFFF.toInt() -> {
                        val refIdLen = buf.int
                        val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                        LinkedSecretRef(CredentialsId.from(String(refIdBytes, Charsets.UTF_8)))
                    }
                    0x00000000.toInt() -> null
                    else -> throw SecretStoreTamperException("SshPrivateKey passphrase must be linked-ref or absent")
                }
                dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey(id, CredentialScope.GLOBAL, username, keyBytes, passphraseRef)
            }
            KIND_SECRET_FILE -> {
                val partCount = buf.get().toInt()
                // originalName: normal
                val origNameLen = buf.get().toInt()
                buf.position(buf.position() + origNameLen)
                val origNameLen2 = readPartMarker(buf)
                val origNameBytes = ByteArray(origNameLen2); buf.get(origNameBytes)
                val originalName = String(origNameBytes, Charsets.UTF_8)
                // content: normal
                val contentNameLen = buf.get().toInt()
                buf.position(buf.position() + contentNameLen)
                val contentLen = readPartMarker(buf)
                val contentBytes = ByteArray(contentLen); buf.get(contentBytes)
                dev.rubentxu.pipeline.v2.domain.credentials.SecretFile(id, CredentialScope.GLOBAL, contentBytes, originalName.ifEmpty { null })
            }
            KIND_CERTIFICATE -> {
                val partCount = buf.get().toInt()
                // keystore: normal
                val ksNameLen = buf.get().toInt()
                buf.position(buf.position() + ksNameLen)
                val ksLen = readPartMarker(buf)
                val ksBytes = ByteArray(ksLen); buf.get(ksBytes)
                // alias: normal
                val aliasNameLen = buf.get().toInt()
                buf.position(buf.position() + aliasNameLen)
                val aliasLen = readPartMarker(buf)
                val aliasBytes = ByteArray(aliasLen); buf.get(aliasBytes)
                val alias = String(aliasBytes, Charsets.UTF_8)
                // password: linked-ref or absent
                val pwNameLen = buf.get().toInt()
                buf.position(buf.position() + pwNameLen)
                val pwMarker = readPartMarker(buf)
                val passwordRef = when (pwMarker) {
                    0xFFFFFFFF.toInt() -> {
                        val refIdLen = buf.int
                        val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                        LinkedSecretRef(CredentialsId.from(String(refIdBytes, Charsets.UTF_8)))
                    }
                    0x00000000.toInt() -> null
                    else -> throw SecretStoreTamperException("Certificate password must be linked-ref or absent")
                }
                dev.rubentxu.pipeline.v2.domain.credentials.Certificate(id, CredentialScope.GLOBAL, ksBytes, passwordRef, alias.ifEmpty { null })
            }
            KIND_ZIP -> {
                val partCount = buf.get().toInt()
                // _entryCount metadata: normal
                val metaNameLen = buf.get().toInt()
                buf.position(buf.position() + metaNameLen)
                buf.int // skip count
                buf.get() // consume metadata content byte (1)
                val entries = mutableMapOf<String, ByteArray>()
                repeat(partCount - 1) {
                    val entryNameLen = buf.get().toInt()
                    val entryNameBytes = ByteArray(entryNameLen); buf.get(entryNameBytes)
                    val entryName = String(entryNameBytes, Charsets.UTF_8)
                    val entryLen = readPartMarker(buf)
                    val entryBytes = ByteArray(entryLen); buf.get(entryBytes)
                    entries[entryName] = entryBytes
                }
                dev.rubentxu.pipeline.v2.domain.credentials.Zip(id, CredentialScope.GLOBAL, entries)
            }
            KIND_USERNAME_COLON_PASSWORD -> {
                val partCount = buf.get().toInt()
                // username: normal
                val userNameLen = buf.get().toInt()
                buf.position(buf.position() + userNameLen)
                val userLen = readPartMarker(buf)
                val userBytes = ByteArray(userLen); buf.get(userBytes)
                val username = String(userBytes, Charsets.UTF_8)
                // password: normal
                val passNameLen = buf.get().toInt()
                buf.position(buf.position() + passNameLen)
                val passLen = readPartMarker(buf)
                val passBytes = ByteArray(passLen); buf.get(passBytes)
                dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword(id, CredentialScope.GLOBAL, username, passBytes)
            }
            else -> throw SecretStoreTamperException("Unknown credential kind: $kindId")
        }
    }
}
