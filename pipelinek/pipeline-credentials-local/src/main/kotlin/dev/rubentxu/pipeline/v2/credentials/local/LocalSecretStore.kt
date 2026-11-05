package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStoreCommitException
import dev.rubentxu.pipeline.v2.credentials.api.CredentialsStoreProjectionException
import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceNotFoundException
import dev.rubentxu.pipeline.v2.credentials.api.LinkedSecretReferenceTypeMismatchException
import dev.rubentxu.pipeline.v2.credentials.api.SecretStore
import dev.rubentxu.pipeline.v2.credentials.api.SecretStoreTamperException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Local credential store using envelope encryption.
 * ## Design (— rung ii)
 * - KEK derives from passphrase via Argon2id (OWASP floor: m≥19456 KiB, t≥2, p≥1)
 * - DEK is random 256-bit, wrapped by KEK (envelope tier ii)
 * Per-entry: with random 96-bit nonce + AAD
 * - AAD = magic ‖ version ‖ kdfParams ‖ credentialId (anti-rename/swap)
 * - re-Argon2id only on unlock, never on every put
 * ## The commit
 * A mutation writes a temporary file with a name of its own and replaces the
 * store with it. The replacement is atomic where the filesystem offers it,
 * and a replacement that does not happen is REPORTED: a mutation that says it
 * worked has changed the file. Two writers of the same store wait for each
 * other, in this process and in another one.
 * ## File Format
 * ```
 * Header:
 *   magic: 4 bytes ("PKCR")
 *   version: 1 byte (1)
 *   argon2_m: 4 bytes LE (KiB)
 *   argon2_t: 4 bytes LE (iterations)
 *   argon2_p: 4 bytes LE (parallelism)
 *   salt: 16 bytes
 * WrappedDEK: 40 bytes (RFC 3394 output for 32-byte DEK)
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
    private val committer: StoreCommit = AtomicStoreCommit,
) : SecretStore {
    private val secureRandom = SecureRandom()

    /**
     * The key that opens this store, once the passphrase that wraps it has been
     * accepted. Kept so that the passphrase is checked once per store and not
     * once per operation.
     */
    private var openedDek: ByteArray? = null

    /**
     * How many times this store derived a key from the passphrase.
     *
     * The derivation is the expensive half of opening a store: Argon2id over
     * nineteen mebibytes of memory, twice. The number of derivations an
     * operation performs IS its cost, so an operation that walks a chain of
     * linked secrets and derives once per hop pays that cost once per hop.
     * This counter is what makes the difference visible instead of argued.
     *
     * It counts and nothing else. No derivation is skipped, delayed or made
     * cheaper because of it.
     */
    internal val passphraseValidations = AtomicInteger()

    /**
     * How many times this store's file was read.
     *
     * Every read of the store file goes through one place, so this counts
     * all of them: the reads that answer a question and the reads that
     * write one back. A chain of linked secrets is answered against ONE
     * view of the store, so the number of reads does not grow with the
     * length of the chain.
     *
     * It counts and nothing else. No read is skipped, delayed or made
     * cheaper because of it.
     */
    internal val storeReads = AtomicInteger()

    /**
     * How many times the data key was unwrapped to answer a question.
     *
     * The unwrap is the step that turns the passphrase into the key that
     * opens the entries. A chain of linked secrets unwraps once, not once
     * per hop.
     *
     * It counts and nothing else. No unwrap is skipped, delayed or made
     * cheaper because of it.
     */
    internal val keyUnwraps = AtomicInteger()

    /**
     * How many times the entries of the store file were parsed to answer a
     * question.
     *
     * A chain of linked secrets is answered against ONE parsing of the
     * entries, not one per hop.
     *
     * It counts and nothing else. No parse is skipped, delayed or made
     * cheaper because of it.
     */
    internal val entryParses = AtomicInteger()

    /**
     * Derives the key that wraps this store's data key.
     *
     * Every derivation this store performs goes through here, so the counter
     * above counts all of them and not only the ones somebody remembered to
     * count.
     */
    private fun deriveKek(salt: ByteArray): ByteArray {
        passphraseValidations.incrementAndGet()
        return CredentialCrypto.deriveKek(passphrase, salt)
    }

    /**
     * Checks that this passphrase is the one that opens this store.
     *
     * A store that holds something is sealed with a key that only the right
     * passphrase can unwrap, and unwrapping it is the only way to know that the
     * passphrase is right. A store that holds nothing has nothing to check.
     *
     * @throws SecretStorePassphraseMismatchException if the passphrase does not
     *   match the store
     */
    fun verifyPassphrase() {
        if (!Files.exists(file) || Files.size(file) == 0L) return
        open(readStoreFile())
    }

    /**
     * Unwraps the key that opens the store and remembers it.
     *
     * Every operation that reads or changes what the store holds goes through
     * here first, so a passphrase that does not match is refused before
     * anything is read out or written back.
     */
    private fun open(fileBytes: ByteArray): ByteArray {
        openedDek?.let { return it }
        val hdr = readHeader(fileBytes)
        val kek = deriveKek(hdr.kdfSalt)
        val dek = CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)
        openedDek = dek
        return dek
    }

    /**
     * The store as ONE operation sees it.
     *
     * A snapshot carries the bytes of the store file, the header they carry
     * and the key that opens them. Every operation takes one snapshot and
     * every hop of a chain of linked secrets walks THAT one, so the passphrase
     * is validated once per operation and the cost of a chain does not grow
     * with the length of the chain.
     *
     * The key lives in the snapshot and in nothing else: the snapshot is a
     * local value, so the key dies with the operation that took it, and no
     * key outlives the operation that opened it.
     *
     * The header is read when the snapshot is taken, and the key and the
     * entries are opened WHEN THEY ARE NEEDED, because the paths that need
     * them need them in a different order: one of them looks for the
     * credential before it opens the key, and that order is what decides
     * whether a wrong passphrase is answered with "the passphrase is wrong"
     * or with "that credential is not there".
     */
    private inner class Snapshot(
        val header: StoreHeader,
        private val fileBytes: ByteArray,
        private val kek: ByteArray,
    ) {
        private var unwrappedDek: ByteArray? = null
        private var parsedEntries: Map<String, out Any>? = null

        /**
         * The key that opens the entries, unwrapped once.
         *
         * The unwrap is remembered, so a chain of linked secrets pays for one
         * unwrap and not for one per hop.
         */
        fun dek(): ByteArray {
            val already = unwrappedDek
            if (already != null) return already
            keyUnwraps.incrementAndGet()
            val fresh = CredentialCrypto.unwrapDek(kek, header.wrappedDek)
            unwrappedDek = fresh
            return fresh
        }

        /**
         * The entries the store file holds, parsed once.
         *
         * The parse is remembered, so a chain of linked secrets pays for one
         * parse and not for one per hop.
         */
        fun entries(): Map<String, out Any> {
            val already = parsedEntries
            if (already != null) return already
            entryParses.incrementAndGet()
            val fresh = if (header.version == VERSION_V1.toShort()) {
                CredentialCrypto.readEntriesV1(fileBytes)
            } else {
                CredentialCrypto.readEntries(fileBytes)
            }
            parsedEntries = fresh
            return fresh
        }
    }

    /**
     * Takes the snapshot this operation works on.
     *
     * The file is read once and the passphrase is validated once. The
     * derivation is the expensive half of opening a store -- Argon2id over
     * nineteen mebibytes, twice -- and an operation that derives once per hop
     * of a chain pays that cost once per hop.
     */
    private fun snapshot(): Snapshot {
        if (!Files.exists(file)) {
            throw SecretStoreTamperException("Store file does not exist")
        }
        val fileBytes = readStoreFile()
        val header = readHeader(fileBytes)
        val kek = deriveKek(header.kdfSalt)
        return Snapshot(header, fileBytes, kek)
    }

    /**
     * Reads this store's file.
     *
     * Every read of the store file goes through here, so the counter above
     * counts all of them and not only the ones somebody remembered to count.
     */
    private fun readStoreFile(): ByteArray {
        storeReads.incrementAndGet()
        return Files.readAllBytes(file)
    }

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
     * Id + sealed blob + kind ID for v2 format.
*/
    data class V2EntryData(
        val id: CredentialsId,
        val sealed: AeadCipher.SealedBlob,
        val kindId: Short,
    )

    /**
     * The store a mutation publishes: the header it will carry and the
     * entries it will hold under that header.
     *
     * @property header the header the store will carry once the mutation is done
     * @property entries the entries the store will hold under that header
     * @property migrated whether every one of those entries was sealed by this
     *   mutation, and so can be opened before the store replaces the one that
     *   is there
     */
    private class Upgrade(
        val header: StoreHeader,
        val entries: MutableMap<String, V2EntryData>,
        val migrated: Boolean,
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

        /**
         * The monitor each store file's writers share inside one JVM.
         *
         * It lives in the companion because it belongs to the FILE: two
         * instances of the store over the same path have to wait for each
         * other exactly like two processes do.
         */
        private val jvmMonitors = ConcurrentHashMap<Path, ReentrantLock>()

        /** OWASP floor KDF params (2023 recommendation) */
        const val OWASP_M = 19456  // KiB
        const val OWASP_T = 2       // iterations
        const val OWASP_P = 1       // parallelism
        const val SALT_SIZE = 16
        const val DEK_SIZE = 32
        // (RFC 3394) output = input + 8 bytes (64-bit IV/wrap vector)
        const val WRAPPED_DEK_SIZE = DEK_SIZE + 8  // = 40 bytes

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
     *
     * The message says what happened, because "a passphrase is required" would
     * send the operator looking for a variable they already set.
*/
    class SecretStorePassphraseMismatchException :
        dev.rubentxu.pipeline.v2.credentials.api.SecretStorePassphraseMismatchException(
            "the passphrase does not match the store"
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
     * The monitor every writer of this store file shares inside one JVM.
     *
     * The monitor belongs to the FILE and not to the instance: two stores over
     * the same path are two views of one store, and the operating system's
     * lock is not reentrant, so the second view would be told that the lock is
     * already held instead of being made to wait for its turn.
     */
    private fun jvmMonitor(): ReentrantLock =
        jvmMonitors.computeIfAbsent(lockFile().toAbsolutePath().normalize()) { ReentrantLock() }

    /**
     * Executes a mutation operation with an exclusive file lock.
     *
     * The monitor of the JVM serializes the writers that share this file
     * inside this JVM, and the file lock serializes the writers of other
     * processes. Both are needed: the file lock alone refuses a second
     * writer of the same JVM instead of making it wait, and the monitor alone
     * says nothing to a process.
*/
    private inline fun <T> withExclusiveLock(block: () -> T): T {
        val monitor = jvmMonitor()
        monitor.lock()
        try {
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
        } finally {
            monitor.unlock()
        }
    }

    /**
     * Puts what a mutation wrote in the store's place.
     *
     * This is the ONE place where the store file is replaced. A mutation that
     * calls it either sees the new store or sees the failure, and never sees
     * a success it did not achieve: the question of whether the replacement
     * happened is asked here and nowhere else, so every mutation of the store
     * gets the same answer.
     */
    private fun commit(bytes: ByteArray) {
        val temporary = writeTemporary(bytes)
        try {
            committer.commit(temporary, file)
        } catch (e: Throwable) {
            // A temporary that will never be committed must not stay in the
            // store's directory: it holds the secret in clear, and the next
            // writer would find it there.
            Files.deleteIfExists(temporary)
            throw e
        }
    }

    /**
     * The temporary file a mutation writes before it replaces the store.
     *
     * The name is PRIVATE to the write: two writers of the same store never
     * meet on the same file, and a reader that lists the directory sees a
     * name that says nothing about the store it belongs to. The permissions
     * are the ones the store demands, so the secret is not readable by
     * another user while it waits to be put in place, and the content is
     * forced to the device before the replacement, so what the replacement
     * publishes is already on the disk.
     */
    private fun writeTemporary(bytes: ByteArray): Path {
        val directory = file.toAbsolutePath().parent ?: Path.of(".")
        val temporary = Files.createTempFile(directory, "${file.fileName}.tmp-", ".tmp")
        try {
            Files.write(temporary, bytes)
            CredentialsStorePosix.setFilePermissions(temporary)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            return temporary
        } catch (e: Throwable) {
            Files.deleteIfExists(temporary)
            throw e
        }
    }

    /**
     * Stores a credential.
     * @param id The credential ID
     * @param bytes The secret bytes
     * @throws CredentialsStoreEmptySecretException if bytes is empty
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced. The store keeps the content it had.
*/
    override fun put(id: CredentialsId, bytes: ByteArray) {
        if (bytes.isEmpty()) throw CredentialsStoreEmptySecretException()

        withExclusiveLock { writeBytes(id, bytes) }
    }

    /**
     * The bytes of a credential, written into a store that may not exist yet.
     *
     * The caller holds the lock. This is the writing half of [put], kept
     * apart so that a rotation which finds no store can create one without
     * asking for the lock a second time: the operating system's lock is not
     * reentrant, and a nested one would be refused instead of waited for.
     */
    private fun writeBytes(id: CredentialsId, bytes: ByteArray) {
        val existingFileBytes = if (Files.exists(file) && Files.size(file) > 0) readStoreFile() else null
        val hdr = if (existingFileBytes != null) {
            readHeader(existingFileBytes)
        } else {
            // Fresh store for legacy backward-compat contract
            val salt = ByteArray(SALT_SIZE); secureRandom.nextBytes(salt)
            StoreHeader(MAGIC, VERSION_V1, OWASP_M, OWASP_T, OWASP_P, salt, ByteArray(WRAPPED_DEK_SIZE))
        }
        val kek = deriveKek(hdr.kdfSalt)

        // Load existing DEK from header, or generate new one for a FRESH store only.
        val dek = if (hdr.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
            ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
        } else {
            CredentialCrypto.unwrapDek(kek, hdr.wrappedDek)
        }

        val wrappedDek = CredentialCrypto.wrapDek(kek, dek)
        val aad = CredentialCrypto.buildAad(hdr, id)
        val sealed = AeadCipher.encrypt(dek, bytes, aad)

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

        commit(out.toByteArray())
    }

    /**
     * Stores a typed credential in v2 format
     * On first add to a v1 file: migrates existing v1 entries to v2.
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced, or when the migrated store would not open. The store keeps
     *   the content it had.
     * @throws SecretStoreTamperException when an entry of the v1 store does
     *   not open. The store is not migrated and keeps the content it had.
*/
    override fun add(id: CredentialsId, credential: Credential) {
        withExclusiveLock {
            val source = loadOrCreateHeader()
            val kek = deriveKek(source.kdfSalt)

            // Load or generate DEK
            val dek = if (source.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
                ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
            } else {
                CredentialCrypto.unwrapDek(kek, source.wrappedDek)
            }

            // Serialize credential to bytes
            val credentialBytes = CredentialSerializer.serializeCredential(credential)
            val kindId = kindIdFor(credential)

            // Atomic write
            val existingBytes = if (Files.exists(file) && Files.size(file) > 0) readStoreFile() else null

            // The header the store will carry and the entries it will hold
            // under it. The upgrade is decided in ONE place, so the rule that
            // seals an entry cannot be one thing here and another there.
            val upgrade = upgrade(dek, source, existingBytes)

            // Encrypt with DEK - AAD includes kind for anti-swap
            val sealed = AeadCipher.encrypt(dek, credentialBytes, CredentialCrypto.buildV2Aad(upgrade.header, id, kindId))
            upgrade.entries[id.value] = V2EntryData(id, sealed, kindId)

            val out = ByteArrayOutputStream()
            // Always write header for new entries; migrate entries to
            writeHeader(
                out,
                upgrade.header.magic, upgrade.header.version, upgrade.header.kdfM,
                upgrade.header.kdfT, upgrade.header.kdfP, upgrade.header.kdfSalt,
                CredentialCrypto.wrapDek(kek, dek),
            )
            for (entry in upgrade.entries.values) {
                out.write(CredentialCrypto.encodeV2Entry(entry))
            }

            commit(publishable(out.toByteArray(), upgrade, dek))
        }
    }

    /**
     * Retrieves a typed credential
     * Handles both v1 (back-compat) and v2 formats.
*/
    override fun get(id: CredentialsId): Credential {
        val snapshot = snapshot()
        val hdr = snapshot.header
        val dek = snapshot.dek()

        // Route to correct reader based on header version
        val entry = snapshot.entries()[id.value]
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
     * Retrieves a credential as a [SecretHandle].
     *
     * The handle carries the SECRET of the credential and nothing else. A V1
     * entry holds the secret itself, so its bytes ARE the secret. A V2 entry
     * holds the credential in the store's canonical form, and that form is
     * NOT the secret: it carries the part count, the part names and their
     * lengths. Only a credential that IS one secret -- a SecretText -- is
     * delivered as its own bytes; every other kind is refused, because a
     * single opaque value cannot say which of its parts it carries. The
     * caller that wants one part asks for it by name, and the caller that
     * wants the credential asks for it typed.
     *
     * @param id The credential ID
     * @return [SecretHandle] wrapping the secret bytes
     * @throws SecretStoreTamperException if the credential is not there, or
     *   the store has been tampered with
     * @throws CredentialsStoreProjectionException if the credential is not a
     *   single secret
*/
    override fun getAsSecretHandle(id: CredentialsId): SecretHandle {
        val snapshot = snapshot()
        val hdr = snapshot.header

        // Route to correct reader based on header version (add stores in format)
        val entry = snapshot.entries()[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        // Decrypt DEK: the DEK is stored in the header (wrapped with KEK). The
        // key is unwrapped AFTER the lookup, so a store that does not hold
        // the credential answers "it is not there" even when the passphrase
        // is wrong: the answer does not depend on the order of the checks.
        val dek = snapshot.dek()

        if (hdr.version == VERSION_V1.toShort()) {
            // A V1 entry holds the secret itself: its bytes ARE the secret.
            val v1Entry = entry as? EntryData
                ?: throw ClassCastException("Expected EntryData but got ${entry::class.simpleName}")
            val plaintext = try {
                AeadCipher.decrypt(dek, v1Entry.sealed, CredentialCrypto.buildAad(hdr, id))
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
            }
            return SecretHandle.secret(plaintext)
        }

        // A V2 entry holds the credential in the canonical form. Reading it
        // with the SAME reader `get` uses is what keeps the two paths from
        // disagreeing about what the store holds.
        val v2Entry = entry as? V2EntryData
            ?: throw ClassCastException("Expected V2EntryData but got ${entry::class.simpleName}")
        val plaintext = try {
            AeadCipher.decrypt(dek, v2Entry.sealed, CredentialCrypto.buildV2Aad(hdr, id, v2Entry.kindId))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
        }

        val credential = CredentialSerializer.deserializeCredential(plaintext, v2Entry.kindId, id)
        return when (credential) {
            is SecretText -> SecretHandle.secret(credential.bytes)
            // Every kind that is not one secret is refused, and the refusal
            // names the kind. The `when` has no `else` ON PURPOSE: the
            // compiler is the instrument that makes a NEW credential kind
            // decide what it projects to, instead of inheriting a refusal
            // nobody wrote.
            is UsernamePassword,
            is SshPrivateKey,
            is SecretFile,
            is Certificate,
            is Zip,
            is UsernameColonPassword,
            -> throw CredentialsStoreProjectionException(
                id,
                credential::class.simpleName ?: "an unrecognised credential kind",
            )
        }
    }

    /**
     * Retrieves a specific part of a multipart credential as a SecretHandle.
     * For file-based kinds (SecretFile, Certificate, Zip), this returns the raw part bytes.
     * For LinkedSecretRef parts, this resolves the reference and returns the SecretText part
     * of the referenced credential.
     * @param id The credential ID
     * @param partName The name of the part to retrieve (e.g., "password", "privateKey")
     * @return SecretHandle wrapping the part bytes
     * @throws LinkedSecretReferenceNotFoundException if the credential or referenced credential does not exist
     * @throws LinkedSecretReferenceTypeMismatchException if the referenced credential is not SecretText
     * @throws SecretStoreTamperException if the credential is tampered
*/
    override fun getAsHandle(id: CredentialsId, partName: String): SecretHandle {
        val snapshot = snapshot()
        val hdr = snapshot.header
        val dek = snapshot.dek()

        // Route based on header version
        if (hdr.version == VERSION_V1.toShort()) {
            throw SecretStoreTamperException("getAsHandle not supported for v1 single-blob entries")
        }
        val entry = snapshot.entries()[id.value]
            ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

        val v2Entry = entry as V2EntryData

        val aad = CredentialCrypto.buildV2Aad(hdr, id, v2Entry.kindId)
        val plaintext = try {
            AeadCipher.decrypt(dek, v2Entry.sealed, aad)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
        }

        // Deserialize and extract the requested part; use visited set for cycle detection
        return LinkedSecretResolver(snapshot.header, { snapshot.dek() }, { snapshot.entries() })
            .extractPart(plaintext, v2Entry.kindId, id, partName, mutableSetOf(id))
    }

    /**
     * Lists all credential IDs in the store.
     * @return List of credential IDs (never returns values)
     * @throws SecretStorePassphraseMismatchException if the passphrase does not
     *   match the store: asking what a store holds is asking it to open, and a
     *   passphrase that does not match gets an answer, not a list
*/
    override fun list(): List<CredentialsId> {
        if (!Files.exists(file) || Files.size(file) == 0L) return emptyList()
        val fileBytes = readStoreFile()
        open(fileBytes)
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
     * @param id The credential ID to remove
     * @throws SecretStorePassphraseMismatchException if the passphrase does not
     *   match the store. The key is the authorization to change what is inside,
     *   so it is checked before anything is written: a passphrase that does not
     *   match leaves the store exactly as it was.
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced. The store keeps the content it had.
*/
    override fun remove(id: CredentialsId) {
        withExclusiveLock {
            if (!Files.exists(file) || Files.size(file) == 0L) return
            val fileBytes = readStoreFile()
            open(fileBytes)
            val hdr = readHeader(fileBytes)

            // Route based on header version
            val entries: MutableMap<String, out Any> = if (hdr.version == VERSION_V1.toShort()) {
                CredentialCrypto.readEntriesV1(fileBytes).toMutableMap()
            } else {
                CredentialCrypto.readEntries(fileBytes).toMutableMap()
            }
            entries.remove(id.value) ?: return // Not found, no-op

            // Rewrite without the removed entry
            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
            for ((_, entryData) in entries) {
                when (entryData) {
                    is EntryData -> out.write(CredentialCrypto.encodeEntry(entryData.id, entryData.sealed))
                    is V2EntryData -> out.write(CredentialCrypto.encodeV2Entry(entryData))
                }
            }
            commit(out.toByteArray())
        }
    }

    /**
     * Re-encrypts a credential with new bytes, preserving the DEK.
     * Replaces the existing credential with a new one of the same ID but new content.
     * Uses the same DEK (from the store header) to re-encrypt the new credential.
     * This preserves the ability to decrypt existing entries while updating the credential.
     * @param id The credential ID (must already exist)
     * @param credential The new typed credential to store
     * @throws SecretStoreTamperException if the credential doesn't exist, or
     *   when an entry of the v1 store does not open. The store is not
     *   migrated and keeps the content it had.
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced, or when the migrated store would not open. The store keeps
     *   the content it had.
*/
    override fun rotate(id: CredentialsId, credential: Credential) {
        withExclusiveLock {
            if (!Files.exists(file)) {
                throw SecretStoreTamperException("Store file does not exist")
            }
            if (Files.size(file) == 0L) {
                throw SecretStoreTamperException("Credential not found: ${id.value}")
            }

            val fileBytes = readStoreFile()
            val source = readHeader(fileBytes)
            val kek = deriveKek(source.kdfSalt)

            // Get DEK from header (reuse existing DEK)
            val dek = CredentialCrypto.unwrapDek(kek, source.wrappedDek)

            // The header the store will carry and the entries it will hold
            // under it. The upgrade is decided in ONE place, so the rule that
            // seals an entry cannot be one thing here and another there.
            val upgrade = upgrade(dek, source, fileBytes)

            if (!upgrade.entries.containsKey(id.value)) {
                throw SecretStoreTamperException("Credential not found: ${id.value}")
            }

            // Serialize and encrypt the new credential
            val credentialBytes = CredentialSerializer.serializeCredential(credential)
            val kindId = kindIdFor(credential)
            val sealed = AeadCipher.encrypt(dek, credentialBytes, CredentialCrypto.buildV2Aad(upgrade.header, id, kindId))

            // Update entries
            upgrade.entries[id.value] = V2EntryData(id, sealed, kindId)

            // Atomic write - always write header
            val out = ByteArrayOutputStream()
            writeHeader(
                out,
                upgrade.header.magic, upgrade.header.version, upgrade.header.kdfM,
                upgrade.header.kdfT, upgrade.header.kdfP, upgrade.header.kdfSalt,
                source.wrappedDek,
            )
            for (entry in upgrade.entries.values) {
                out.write(CredentialCrypto.encodeV2Entry(entry))
            }

            commit(publishable(out.toByteArray(), upgrade, dek))
        }
    }

    /**
     * Rotates a credential with new bytes, preserving the DEK (v1 compatibility).
     * Full implementation - same as behavior
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced. The store keeps the content it had.
*/
    override fun rotateBytes(id: CredentialsId, newBytes: ByteArray) {
        if (newBytes.isEmpty()) throw CredentialsStoreEmptySecretException()
        withExclusiveLock {
            if (!Files.exists(file)) {
                // A store that is not there yet is CREATED by the rotation, with
                // the same bytes a first write would store. The write happens
                // HERE and not in another call, because the lock this rotation
                // holds is not reentrant at the operating system's level: a
                // nested lock would be refused instead of waited for.
                writeBytes(id, newBytes)
                return@withExclusiveLock
            }
            val fileBytes = readStoreFile()
            val hdr = readHeader(fileBytes)
            val kek = deriveKek(hdr.kdfSalt)

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

            val out = ByteArrayOutputStream()
            writeHeader(out, hdr.magic, hdr.version, hdr.kdfM, hdr.kdfT, hdr.kdfP, hdr.kdfSalt, hdr.wrappedDek)
            for ((_, entryData) in updatedEntries) {
                out.write(CredentialCrypto.encodeEntry(entryData.id, entryData.sealed))
            }
            commit(out.toByteArray())
        }
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

    /**
     * The upgrade of the store to the header it will carry.
     *
     * The header is built BEFORE anything is encrypted, because the AAD of an
     * entry carries the version of the header it lives under: an entry sealed
     * under one version and published under another cannot be opened by
     * anybody, and the store would answer with a diagnostic that blames
     * tampering for a mistake of its own.
     *
     * The v1 entries are read with the header they were sealed under and
     * re-sealed under the one the store will carry. `add` and `rotate` both
     * publish through here, so the rule that decides how an entry is sealed
     * is ONE rule and not two that can drift apart.
     *
     * @param dek the key that opens the entries
     * @param source the header the store carries now
     * @param fileBytes the bytes of the store file, or null when there is none
     * @throws SecretStoreTamperException when an entry of the v1 store does
     *   not open. The store is not migrated and keeps the content it had.
     */
    private fun upgrade(dek: ByteArray, source: StoreHeader, fileBytes: ByteArray?): Upgrade {
        val destination = source.copy(version = VERSION_V2)
        val migrating = source.version == VERSION_V1.toShort()
        val entries: MutableMap<String, V2EntryData> = when {
            fileBytes == null || fileBytes.size <= HEADER_SIZE_V1 -> mutableMapOf()
            migrating -> CredentialCrypto.readEntriesV1(fileBytes).mapValues { (entryId, entry) ->
                val id = CredentialsId.from(entryId)
                val plaintext = try {
                    AeadCipher.decrypt(dek, entry.sealed, CredentialCrypto.buildAad(source, id))
                } catch (e: javax.crypto.AEADBadTagException) {
                    throw SecretStoreTamperException(
                        "the v1 entry '${id.value}' does not open; the store was not migrated " +
                            "and keeps the content it had",
                        e,
                    )
                }
                // A v1 entry holds the secret itself; a v2 entry holds the
                // credential in the canonical part form its reader
                // deserializes. The migration moves the secret from one form
                // to the other, so the entry reads back as the credential it
                // was instead of as a blob nobody can interpret.
                val canonical = CredentialSerializer.serializeCredential(
                    SecretText(id, CredentialScope.GLOBAL, plaintext),
                )
                V2EntryData(
                    id,
                    AeadCipher.encrypt(dek, canonical, CredentialCrypto.buildV2Aad(destination, id, KIND_SECRET_TEXT)),
                    KIND_SECRET_TEXT,
                )
            }.toMutableMap()
            else -> CredentialCrypto.readEntries(fileBytes).toMutableMap()
        }
        return Upgrade(destination, entries, migrating)
    }

    /**
     * The bytes a mutation is about to publish, once they have been opened.
     *
     * An upgrade re-seals every entry the store will carry, so every entry
     * of these bytes was sealed by this mutation and every one of them can be
     * opened before they replace the store: a store that cannot be opened is
     * a store nobody can use, and a store that is half upgraded is worse than
     * one that was not upgraded at all.
     *
     * The entries are opened with the header the READER will read, taken from
     * the bytes that are about to be published and not from the header this
     * mutation built. The guarantee is that the file opens, and the reader
     * reads the file.
     *
     * @throws CredentialsStoreCommitException when the store would not open
     *   under the header it would be published with. The store keeps the
     *   content it had.
     */
    private fun publishable(bytes: ByteArray, upgrade: Upgrade, dek: ByteArray): ByteArray {
        if (!upgrade.migrated) return bytes
        val published = readHeader(bytes)
        for (entry in CredentialCrypto.readEntries(bytes).values) {
            try {
                AeadCipher.decrypt(dek, entry.sealed, CredentialCrypto.buildV2Aad(published, entry.id, entry.kindId))
            } catch (e: javax.crypto.AEADBadTagException) {
                throw CredentialsStoreCommitException(
                    "the migrated store does not open: the entry '${entry.id.value}' cannot be " +
                        "read under the header it would be published with; the store keeps the " +
                        "content it had",
                    e,
                )
            }
        }
        return bytes
    }

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
            return readHeader(readStoreFile())
        }
        // Create new header with fresh salt - use format for new stores
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

// Kind IDs for v2 envelope (stored as first 2 bytes after version)
private const val KIND_SECRET_TEXT: Short = 1
private const val KIND_USERNAME_PASSWORD: Short = 2
private const val KIND_SSH_PRIVATE_KEY: Short = 3
private const val KIND_SECRET_FILE: Short = 4
private const val KIND_CERTIFICATE: Short = 5
private const val KIND_ZIP: Short = 6
private const val KIND_USERNAME_COLON_PASSWORD: Short = 7

/**
 * The walk over a chain of linked secrets.
 *
 * A chain is a credential whose part refers to another credential, which may
 * refer to another one. This walk follows those references until it reaches
 * the secret text at the end of the chain, and it refuses the chains that
 * close on themselves and the chains that are deeper than the cap.
 *
 * The walk reads ONE view of the store: the header, the key and the entries
 * it is given come from the same opening of the store, so the chain is
 * answered against the store as it was when the operation started, and the
 * passphrase is validated once and not once per hop.
 */
private class LinkedSecretResolver(
    private val header: LocalSecretStore.StoreHeader,
    private val dek: () -> ByteArray,
    private val entries: () -> Map<String, out Any>,
) {
    /**
     * Extracts a named part from deserialized credential bytes using the canonical part format.
     * Format per part: [nameLen:1][name][len:4 BE][bytes]
     * After reading nameLen and name, position is at len(4). We must also skip len to reach bytes.
    */
    fun extractPart(
        plaintext: ByteArray,
        kindId: Short,
        id: CredentialsId,
        partName: String,
        visited: MutableSet<CredentialsId> = mutableSetOf(id),
        ): SecretHandle = try {
        readPart(plaintext, kindId, partName, visited)
    } catch (e: CredentialPartReader.MalformedEnvelopeException) {
        // The reader reports a malformed envelope; callers of the store catch
        // SecretStoreTamperException, which stays the public type.
        throw LocalSecretStore.SecretStoreTamperException(e.message ?: "Malformed envelope", e)
    }

    /** Runs a reader operation and re-raises its envelope failure as a tamper. */
    private inline fun <T> envelope(block: () -> T): T =
        try {
            block()
        } catch (e: CredentialPartReader.MalformedEnvelopeException) {
            throw LocalSecretStore.SecretStoreTamperException(e.message ?: "Malformed envelope", e)
        }

    private fun readPart(
        plaintext: ByteArray,
        kindId: Short,
        partName: String,
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
                SecretHandle.secret(CredentialPartReader.readPositionalPart(buf, partName, "UsernamePassword", "username" to 0, "password" to 1))
            }
            KIND_SSH_PRIVATE_KEY -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "username", "privateKey" ->
                        SecretHandle.secret(CredentialPartReader.readPositionalPart(buf, partName, "SshPrivateKey", "username" to 0, "privateKey" to 1))
                    "passphrase" -> {
                        CredentialPartReader.skipPart(buf) // skip username part
                        CredentialPartReader.skipPart(buf) // skip privateKey part
                        val refId = CredentialPartReader.readLinkedRefPart(buf, "passphrase")
                        return resolveLinkedSecretRef(
                            requireLinkedRef(refId, "Credential has no passphrase part"),
                            visited,
                        )
                    }
                    else -> throw envelope { CredentialPartReader.notFound(partName, "SshPrivateKey") }
                }
            }
            KIND_SECRET_FILE -> {
                buf.get().toInt() // partCount
                SecretHandle.secret(CredentialPartReader.readPositionalPart(buf, partName, "SecretFile", "originalName" to 0, "content" to 1))
            }
            KIND_CERTIFICATE -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "keystore", "alias" ->
                        SecretHandle.secret(CredentialPartReader.readPositionalPart(buf, partName, "Certificate", "keystore" to 0, "alias" to 1))
                    "password" -> {
                        CredentialPartReader.skipPart(buf) // skip keystore part
                        CredentialPartReader.skipPart(buf) // skip alias part
                        val refId = CredentialPartReader.readLinkedRefPart(buf, "password")
                        return resolveLinkedSecretRef(
                            requireLinkedRef(refId, "Credential has no password part"),
                            visited,
                        )
                    }
                    else -> throw envelope { CredentialPartReader.notFound(partName, "Certificate") }
                }
            }
            KIND_ZIP -> SecretHandle.secret(envelope { CredentialPartReader.readZipPart(buf, partName) })
            KIND_USERNAME_COLON_PASSWORD -> {
                buf.get().toInt() // partCount
                when (partName) {
                    "username", "password" ->
                        SecretHandle.secret(CredentialPartReader.readPositionalPart(buf, partName, "UsernameColonPassword", "username" to 0, "password" to 1))
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
                    else -> throw envelope { CredentialPartReader.notFound(partName, "UsernameColonPassword") }
                }
            }
            else -> throw LocalSecretStore.SecretStoreTamperException("Unknown credential kind: $kindId")
        }
    }

    private fun requirePart(matches: Boolean, partName: String, credential: String) {
        if (!matches) throw envelope { CredentialPartReader.notFound(partName, credential) }
    }

    /**
     * A linked-ref part must carry a reference. [readLinkedRefPart] already
     * raises for the absent marker, so this is the null-safety net that lets the
     * caller pass the result straight to [resolveLinkedSecretRef].
    */
    private fun requireLinkedRef(refId: CredentialsId?, absentMessage: String): CredentialsId =
        refId ?: throw LocalSecretStore.SecretStoreTamperException(absentMessage)

    /**
     * Resolves a LinkedSecretRef by getting the SecretText part of the referenced credential.
     * Uses a visited set with depth cap ≤16 to detect circular linked secret references.
     * For SshPrivateKey/Certificate passphrase/password LinkedSecretRefs, this method
     * traverses through intermediate credentials following the ref chain until SecretText is found.
     *
     * Every hop walks the SAME view of the store, so the chain is resolved
     * against one consistent view and the passphrase is validated once, not
     * once per hop. The file is not read again: the view holds the bytes the
     * operation resolves against, so a file that disappears mid-operation
     * cannot make the operation see a different store half way through the
     * chain.
    */
    private fun resolveLinkedSecretRef(
        refId: CredentialsId,
        visited: MutableSet<CredentialsId> = mutableSetOf(),
        ): SecretHandle {
        // Cycle detection: if refId is already in visited set, we have a circular reference
        if (refId in visited) {
            throw LocalSecretStore.SecretStoreTamperException(
                "Circular linked secret reference detected: " +
                    "${visited.joinToString(" → ") { it.value }} → ${refId.value}",
            )
        }
        // Depth cap: if visited set is large, we've hit a long chain (potential cycle or deep ref)
        if (visited.size >= 16) {
            throw LocalSecretStore.SecretStoreTamperException("Linked secret reference chain exceeds maximum depth of 16")
        }

        visited.add(refId)

        val hdr = header
        val dek = dek()

        val refEntry = entries()[refId.value]
            ?: throw LinkedSecretReferenceNotFoundException(refId)

        val v2Entry: LocalSecretStore.V2EntryData
        try {
            v2Entry = refEntry as LocalSecretStore.V2EntryData
        } catch (e: ClassCastException) {
            throw LinkedSecretReferenceTypeMismatchException(refId, "SecretText", "Unknown (v1 format not supported for linked refs)")
        }

        // For SecretText: directly extract and return the value
        if (v2Entry.kindId == KIND_SECRET_TEXT) {
            val aad = CredentialCrypto.buildV2Aad(hdr, refId, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw LocalSecretStore.SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
            }
            // Extract the "value" part from SecretText using canonical format
            val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
            buf.get().toInt() // partCount
            val nameLen = buf.get().toInt()
            if (nameLen > buf.remaining()) {
                throw LocalSecretStore.SecretStoreTamperException("Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})")
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
                throw LocalSecretStore.SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
            }
            return extractLinkedRefFromSshPrivateKey(plaintext, visited)
        }

        // For Certificate: follow the password LinkedSecretRef chain
        if (v2Entry.kindId == KIND_CERTIFICATE) {
            val aad = CredentialCrypto.buildV2Aad(hdr, refId, v2Entry.kindId)
            val plaintext = try {
                AeadCipher.decrypt(dek, v2Entry.sealed, aad)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw LocalSecretStore.SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
            }
            return extractLinkedRefFromCertificate(plaintext, visited)
        }

        // Other credential types cannot be the target of a LinkedSecretRef for passphrase/password
        throw LinkedSecretReferenceTypeMismatchException(refId, "SecretText", LocalSecretStore.kindNameFor(v2Entry.kindId))
    }

    /**
     * Extracts the passphrase LinkedSecretRef from a SshPrivateKey and resolves it.
    */
    private fun extractLinkedRefFromSshPrivateKey(plaintext: ByteArray, visited: MutableSet<CredentialsId>): SecretHandle {
        val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        buf.get().toInt() // partCount
        // Skip username part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) {
                throw LocalSecretStore.SecretStoreTamperException(
                    "Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})",
                )
            }
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Skip privateKey part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) {
                throw LocalSecretStore.SecretStoreTamperException(
                    "Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})",
                )
            }
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Now at passphrase header: nameLen(1) + name + 4-byte marker
        val passphraseNameLen = buf.get().toInt() and 0xFF
        if (passphraseNameLen > buf.remaining()) {
            throw LocalSecretStore.SecretStoreTamperException("Malformed envelope: nameLen($passphraseNameLen) exceeds remaining bytes(${buf.remaining()})")
        }
        buf.position(buf.position() + passphraseNameLen)
        val marker = buf.int
        when (marker) {
            0xFFFFFFFF.toInt() -> {
                val refIdLen = buf.int
                val refIdBytes = ByteArray(refIdLen); buf.get(refIdBytes)
                val nextRefId = CredentialsId.from(String(refIdBytes, Charsets.UTF_8))
                return resolveLinkedSecretRef(nextRefId, visited)
            }
            0x00000000.toInt() -> {
                throw LocalSecretStore.SecretStoreTamperException("Credential has no passphrase part")
            }
            else -> throw LocalSecretStore.SecretStoreTamperException("SshPrivateKey passphrase must be linked-ref or absent")
        }
    }

    /**
     * Extracts the password LinkedSecretRef from a Certificate and resolves it.
    */
    private fun extractLinkedRefFromCertificate(plaintext: ByteArray, visited: MutableSet<CredentialsId>): SecretHandle {
        val buf = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        buf.get().toInt() // partCount
        // Skip keystore part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) {
                throw LocalSecretStore.SecretStoreTamperException(
                    "Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})",
                )
            }
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Skip alias part
        run {
            val nameLen = buf.get().toInt() and 0xFF
            if (nameLen > buf.remaining()) {
                throw LocalSecretStore.SecretStoreTamperException(
                    "Malformed envelope: nameLen($nameLen) exceeds remaining bytes(${buf.remaining()})",
                )
            }
            buf.position(buf.position() + nameLen)
            val valueLen = buf.int; buf.position(buf.position() + valueLen)
        }
        // Now at password header: nameLen(1) + name + 4-byte marker
        val pwNameLen = buf.get().toInt() and 0xFF
        if (pwNameLen > buf.remaining()) {
            throw LocalSecretStore.SecretStoreTamperException("Malformed envelope: nameLen($pwNameLen) exceeds remaining bytes(${buf.remaining()})")
        }
        buf.position(buf.position() + pwNameLen)
        val marker = buf.int
        when (marker) {
            0xFFFFFFFF.toInt() -> {
                val pwRefIdLen = buf.int
                val pwRefIdBytes = ByteArray(pwRefIdLen); buf.get(pwRefIdBytes)
                val nextRefId = CredentialsId.from(String(pwRefIdBytes, Charsets.UTF_8))
                return resolveLinkedSecretRef(nextRefId, visited)
            }
            0x00000000.toInt() -> {
                throw LocalSecretStore.SecretStoreTamperException("Credential has no password part")
            }
            else -> throw LocalSecretStore.SecretStoreTamperException("Certificate password must be linked-ref or absent")
        }
    }
    }
