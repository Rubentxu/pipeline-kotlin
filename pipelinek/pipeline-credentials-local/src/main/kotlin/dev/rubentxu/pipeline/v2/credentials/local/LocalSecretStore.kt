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
 * ## What this class owns, and what it does not
 * This class owns the IO of the store: the file, the lock, the passphrase and
 * the lifecycle. It does NOT own the format: which version the file is in,
 * how an entry is framed, with what AAD it is sealed and what its bytes mean
 * once they are open are properties of the VERSION, and [StoreCodec] is where
 * the version lives.
 * ## The session
 * A session of credentials has an end, and the end is visible.
 * - The store keeps NO decrypted key between operations. The key that opens
 *   the entries is derived and unwrapped by the operation that needs it, and it
 *   dies with that operation, even when the operation fails. What the store
 *   remembers between operations is the FACT that the passphrase was accepted.
 * - [close] is idempotent, wipes the passphrase it was given, and leaves the
 *   store in a state it does not leave: every later operation is refused,
 *   naming that state. A store that answers after its close is a store whose
 *   passphrase was destroyed and whose answers still come out.
 * - The refusal never blames the operator for a mistake of the product.
 * ## File Format
 * ```
 * Header (74 bytes, the same layout in every version):
 *   magic: 4 bytes ("PKCR")
 *   version: 2 bytes LE (1 or 2)
 *   argon2_m: 4 bytes LE (KiB)
 *   argon2_t: 4 bytes LE (iterations)
 *   argon2_p: 4 bytes LE (parallelism)
 *   salt: 16 bytes
 *   wrappedDek: 40 bytes (RFC 3394 output for 32-byte DEK)
 * Entry (repeated):
 *   idLen: 2 bytes LE
 *   credentialId: UTF-8 bytes
 *   kind: 2 bytes LE            -- only in version 2
 *   plaintextLen: 4 bytes LE
 *   nonce: 12 bytes
 *   ciphertext: variable
 *   tag: 16 bytes
 * ```
 * Version 1 holds one secret per entry, and the secret IS the bytes of the
 * entry. Version 2 holds a credential in the canonical part form, and its
 * seal carries the kind of that credential.
*/
class LocalSecretStore(
    private val file: Path,
    private val passphrase: CharArray,
    private val committer: StoreCommit = AtomicStoreCommit,
    private val permissions: CredentialStorePermissionGuard = PosixCredentialStorePermissionGuard,
) : SecretStore {
    private val secureRandom = SecureRandom()

    /**
     * Whether the passphrase of this store has been accepted.
     *
     * The store keeps the FACT and not the key: the key that opens the entries
     * is derived and unwrapped again by every operation that needs it, and it
     * dies with that operation. What is worth keeping is that the passphrase
     * was right, so that a second operation does not pay for the derivation
     * again.
     */
    private var passphraseAccepted = false

    /**
     * Whether this store has been closed.
     *
     * A closed store is a state the store does not leave: every operation is
     * refused from here on, and the refusal names the state instead of
     * blaming the passphrase of the operator.
     */
    @Volatile
    private var closed = false

    /**
     * The secret buffers this store holds open right now.
     *
     * A buffer is in here while it holds key material, and it leaves when it is
     * wiped: [SecretBuffer.wipe] is what takes it out, so «the bytes are
     * wiped» and «the buffer is no longer held» are the same fact.
     */
    private val liveSecrets = ConcurrentHashMap.newKeySet<SecretBuffer>()

    /**
     * How many secret buffers this store holds open right now.
     *
     * It counts and nothing else. No buffer is skipped, delayed or made
     * cheaper because of it. It is what lets a caller see that an operation
     * ended with no key left behind, without reaching into the private fields
     * of the store.
     */
    internal val liveSecretBuffers: Int get() = liveSecrets.size

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
     * Derives the key that wraps this store's data key, as a buffer this store
     * owns.
     *
     * Every derivation this store performs goes through here, so the counter
     * above counts all of them and not only the ones somebody remembered to
     * count. The key comes back in a buffer, because a key that nobody owns is
     * a key nobody wipes.
     */
    private fun deriveKek(salt: ByteArray): SecretBuffer {
        passphraseValidations.incrementAndGet()
        return secret(CredentialCrypto.deriveKek(passphrase, salt))
    }

    /**
     * Takes ownership of a secret buffer.
     *
     * The store holds the buffer while it lives and lets it go when it is
     * wiped, so that what the store holds and what it has wiped are the same
     * count.
     */
    private fun secret(bytes: ByteArray): SecretBuffer {
        val buffer = SecretBuffer.of(bytes) { liveSecrets.remove(it) }
        liveSecrets.add(buffer)
        return buffer
    }

    /**
     * Refuses an operation asked of a store that has been closed.
     *
     * The session of a store ends when it is closed, and a store that keeps
     * answering after its close would answer with a passphrase it no longer
     * has. The refusal names the state and no secret.
     *
     * @throws SecretStoreClosedException when this store has been closed
     */
    private fun requireOpen() {
        if (closed) {
            throw SecretStoreClosedException(
                "the credential store is closed: it holds no passphrase and it opens for nothing",
            )
        }
    }

    /**
     * Checks that this passphrase is the one that opens this store.
     *
     * A store that holds something is sealed with a key that only the right
     * passphrase can unwrap, and unwrapping it is the only way to know that the
     * passphrase is right. A store that holds nothing has nothing to check.
     *
     * @throws SecretStoreClosedException when this store has been closed
     * @throws SecretStorePassphraseMismatchException if the passphrase does not
     *   match the store
     */
    fun verifyPassphrase() {
        requireOpen()
        if (!Files.exists(file) || Files.size(file) == 0L) return
        validatePassphrase(readStoreFile())
    }

    /**
     * Checks that the passphrase opens this store, and forgets the key it used.
     *
     * The key that wraps the data key is derived and the data key is unwrapped:
     * that unwrap IS the proof that the passphrase is the right one. Neither key
     * is kept. What the store remembers is that the passphrase was accepted, so
     * that a second operation does not pay for the derivation again, and the key
     * that opens the entries is derived again by the operation that needs it.
     *
     * @param fileBytes the bytes of the store file
     * @throws SecretStorePassphraseMismatchException when the passphrase does not
     *   match the store
     */
    private fun validatePassphrase(fileBytes: ByteArray) {
        if (passphraseAccepted) return
        val hdr = readHeader(fileBytes)
        val kek = deriveKek(hdr.kdfSalt)
        try {
            secret(CredentialCrypto.unwrapDek(kek.bytes(), hdr.wrappedDek)).wipe()
        } finally {
            kek.wipe()
        }
        passphraseAccepted = true
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
     * local value, and it wipes the keys it holds when its operation ends, so
     * no key outlives the operation that opened it. A snapshot that is closed
     * is a snapshot whose keys are unreadable, whether the operation ended
     * well or badly.
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
        private val kek: SecretBuffer,
    ) : AutoCloseable {
        private var unwrappedDek: SecretBuffer? = null
        private var parsedEntries: Map<String, StoreEntry>? = null

        /**
         * The key that opens the entries, unwrapped once.
         *
         * The unwrap is remembered, so a chain of linked secrets pays for one
         * unwrap and not for one per hop.
         */
        fun dek(): ByteArray {
            val already = unwrappedDek
            if (already != null) return already.bytes()
            keyUnwraps.incrementAndGet()
            val fresh = secret(CredentialCrypto.unwrapDek(kek.bytes(), header.wrappedDek))
            unwrappedDek = fresh
            return fresh.bytes()
        }

        /**
         * The entries the store file holds, parsed once.
         *
         * The parse is remembered, so a chain of linked secrets pays for one
         * parse and not for one per hop.
         *
         * The version of the file decides how it is read, and the codec is
         * where that decision lives: this class does not know which reader
         * belongs to which version.
         */
        fun entries(): Map<String, StoreEntry> {
            val already = parsedEntries
            if (already != null) return already
            entryParses.incrementAndGet()
            val fresh = StoreCodec.of(header.version).read(fileBytes)
            parsedEntries = fresh
            return fresh
        }

        /**
         * Ends this snapshot: the keys it holds become unreadable.
         *
         * The snapshot is closed by the operation that took it, in a `finally`,
         * so the keys die with the operation even when the operation fails.
         * Closing it twice is not an error.
         */
        override fun close() {
            unwrappedDek?.wipe()
            unwrappedDek = null
            kek.wipe()
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
        val entries: MutableMap<String, StoreEntry>,
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
        /**
         * The monitor each store file's writers share inside one JVM.
         *
         * It lives in the companion because it belongs to the FILE: two
         * instances of the store over the same path have to wait for each
         * other exactly like two processes do.
         */
        private val jvmMonitors = ConcurrentHashMap<Path, ReentrantLock>()

        /** OWASP floor KDF params (2023 recommendation) */
        const val OWASP_M = CredentialCrypto.OWASP_M  // KiB
        const val OWASP_T = CredentialCrypto.OWASP_T  // iterations
        const val OWASP_P = CredentialCrypto.OWASP_P  // parallelism
        const val SALT_SIZE = StoreCodec.SALT_SIZE
        const val DEK_SIZE = CredentialCrypto.DEK_SIZE
        // (RFC 3394) output = input + 8 bytes (64-bit IV/wrap vector)
        const val WRAPPED_DEK_SIZE = CredentialCrypto.WRAPPED_DEK_SIZE  // = 40 bytes

        /**
         * Reads the header from a file path.
         * Accessible as LocalSecretStore.readHeader(path)
*/
        fun readHeader(file: Path): StoreHeader {
            val bytes = Files.readAllBytes(file)
            return StoreCodec.readHeader(bytes)
        }
    }

    /**
     * Exception thrown when an operation is asked of a store that has been
     * closed.
     *
     * The message says what happened, because a store that answered a closed
     * session with a passphrase mismatch would blame the operator for a
     * mistake of the product: the passphrase was destroyed by the close.
    */
    class SecretStoreClosedException(message: String) : IllegalStateException(message)

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
        enforcePermissions(file)
    }

    /**
     * Makes this store's file and directory safe to hold secrets, or refuses
     * to open the store.
     *
     * The guard owns the directory's creation as well as its permissions: a
     * directory the store created with the platform's default permissions is
     * a directory the whole machine can list, and the store that made it is
     * the one that has to fix it.
     *
     * The refusal is the store's OWN: the guard speaks about the platform and
     * the store speaks about itself, and the operator gets one exception that
     * names the file whose secrets are at stake. A store whose permissions
     * cannot be guaranteed does not open, because a store that opens without
     * that promise is a store holding secrets nobody has promised to protect.
    */
    private fun enforcePermissions(file: Path) {
        try {
            permissions.enforce(file, file.parent)
        } catch (e: dev.rubentxu.pipeline.v2.credentials.api.CredentialsStorePosixPermissionsException) {
            throw CredentialsStorePosixPermissionsException(
                e.message ?: "the credential store at $file cannot hold its secrets with safe " +
                    "permissions",
            )
        } catch (e: Exception) {
            throw CredentialsStorePosixPermissionsException(
                "the credential store at $file cannot hold its secrets with safe permissions: " +
                    "${e.message}",
            )
        }
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
     * Stores bytes as ONE secret, in the format the store writes.
     *
     * The bytes are wrapped as a [SecretText] and handed to [add], which is
     * the one authority that writes this store: it knows which version the
     * store is in, and it seals every entry under the header the store is
     * published with. A store that holds the legacy format is migrated by
     * that write, so the store this leaves behind is in the current format
     * and holds every credential it held before.
     *
     * @param id The credential ID
     * @param bytes The secret bytes
     * @throws CredentialsStoreEmptySecretException if bytes is empty
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced. The store keeps the content it had.
     */
    override fun put(id: CredentialsId, bytes: ByteArray) {
        requireOpen()
        if (bytes.isEmpty()) throw CredentialsStoreEmptySecretException()

        add(id, SecretText(id, CredentialScope.GLOBAL, bytes))
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
        requireOpen()
        withExclusiveLock {
            val source = loadOrCreateHeader()
            val kek = deriveKek(source.kdfSalt)
            try {
                // Load or generate DEK
                val dek = secret(
                    if (source.wrappedDek.contentEquals(ByteArray(WRAPPED_DEK_SIZE))) {
                        ByteArray(DEK_SIZE).also { secureRandom.nextBytes(it) }
                    } else {
                        CredentialCrypto.unwrapDek(kek.bytes(), source.wrappedDek)
                    },
                )
                try {
                    // Serialize credential to bytes
                    val credentialBytes = CredentialSerializer.serializeCredential(credential)
                    val kindId = CredentialKinds.idOf(credential)

                    // Atomic write
                    val existingBytes = if (Files.exists(file) && Files.size(file) > 0) readStoreFile() else null

                    // The header the store will carry and the entries it will hold
                    // under it. The upgrade is decided in ONE place, so the rule that
                    // seals an entry cannot be one thing here and another there.
                    val upgrade = upgrade(dek.bytes(), source, existingBytes)

                    // The format of the header the store will carry. It decides how
                    // the entry is framed and with what AAD it is sealed.
                    val format = StoreCodec.of(upgrade.header.version)
                    val sealed = AeadCipher.encrypt(
                        dek.bytes(),
                        credentialBytes,
                        format.aad(upgrade.header, id, kindId),
                    )
                    upgrade.entries[id.value] = StoreEntry(id, sealed, kindId)

                    val out = ByteArrayOutputStream()
                    // Always write header for new entries; migrate entries to
                    StoreCodec.writeHeader(
                        out,
                        upgrade.header.copy(wrappedDek = CredentialCrypto.wrapDek(kek.bytes(), dek.bytes())),
                    )
                    for (entry in upgrade.entries.values) {
                        out.write(format.encode(entry))
                    }

                    commit(publishable(out.toByteArray(), upgrade, dek.bytes()))
                } finally {
                    dek.wipe()
                }
            } finally {
                kek.wipe()
            }
        }
    }

    /**
     * Retrieves a typed credential
     * The format of the header decides how the entry is opened and what it
     * holds; this method does not decide which format that is.
*/
    override fun get(id: CredentialsId): Credential {
        requireOpen()
        val snapshot = snapshot()
        try {
            val hdr = snapshot.header
            val dek = snapshot.dek()

            val entry = snapshot.entries()[id.value]
                ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

            val format = StoreCodec.of(hdr.version)
            return format.credential(entry, openEntry(entry, hdr, id, format, dek), id)
        } finally {
            snapshot.close()
        }
    }

    /**
     * The bytes of one entry, once they are open.
     *
     * The AAD comes from the format of the header the entry lives under, so
     * the seal that opens the entry is the one that version seals with.
     *
     * @param entry the entry to open
     * @param header the header the entry lives under
     * @param id the credential the entry belongs to
     * @param format the format of the header
     * @param dek the key that opens the entries
     * @return the bytes of the entry
     * @throws SecretStoreTamperException when the seal does not open
     */
    private fun openEntry(
        entry: StoreEntry,
        header: StoreHeader,
        id: CredentialsId,
        format: StoreFormat,
        dek: ByteArray,
    ): ByteArray = try {
        AeadCipher.decrypt(dek, entry.sealed, format.aad(header, id, entry.kindId))
    } catch (e: javax.crypto.AEADBadTagException) {
        throw SecretStoreTamperException("Tamper detected for credential: ${id.value}", e)
    }

    /**
     * Retrieves a credential as a [SecretHandle].
     *
     * The handle carries the SECRET of the credential and nothing else. What
     * the open bytes of an entry MEAN is a property of the version of the
     * format, and the format is the one that says it: a legacy entry holds
     * the secret itself, and an entry of the current format holds the
     * credential in the store's canonical form, which is NOT the secret -- it
     * carries the part count, the part names and their lengths.
     *
     * Only a credential that IS one secret -- a SecretText -- is delivered as
     * its own bytes; every other kind is refused, because a single opaque
     * value cannot say which of its parts it carries. The caller that wants
     * one part asks for it by name, and the caller that wants the credential
     * asks for it typed.
     *
     * @param id The credential ID
     * @return [SecretHandle] wrapping the secret bytes
     * @throws SecretStoreTamperException if the credential is not there, or
     *   the store has been tampered with
     * @throws CredentialsStoreProjectionException if the credential is not a
     *   single secret
*/
    override fun getAsSecretHandle(id: CredentialsId): SecretHandle {
        requireOpen()
        val snapshot = snapshot()
        try {
            val hdr = snapshot.header

            // The lookup comes BEFORE the key is unwrapped, so a store that does
            // not hold the credential answers "it is not there" even when the
            // passphrase is wrong: the answer does not depend on the order of the
            // checks.
            val entry = snapshot.entries()[id.value]
                ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

            val dek = snapshot.dek()

            // The format of the header decides what the open bytes MEAN: in the
            // legacy format the bytes ARE the secret, and in the current one they
            // hold the credential in the canonical form. Reading the entry with
            // the SAME reader `get` uses is what keeps the two paths from
            // disagreeing about what the store holds.
            val format = StoreCodec.of(hdr.version)
            val plaintext = openEntry(entry, hdr, id, format, dek)
            val credential = format.credential(entry, plaintext, id)
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
        } finally {
            snapshot.close()
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
        requireOpen()
        val snapshot = snapshot()
        try {
            val hdr = snapshot.header
            val dek = snapshot.dek()

            val format = StoreCodec.of(hdr.version)

            // A format whose entries have no part by name says so BEFORE the
            // credential is looked up: a store that cannot answer the question at
            // all does not answer it about a credential that is not there.
            val refusal = format.partsRefusal()
            if (refusal != null) throw SecretStoreTamperException(refusal)

            val entry = snapshot.entries()[id.value]
                ?: throw SecretStoreTamperException("Credential not found: ${id.value}")

            val plaintext = openEntry(entry, hdr, id, format, dek)

            // The walk over the chain of linked secrets. It reads ONE view of the
            // store, and the visited set is what detects a chain that closes on
            // itself.
            return LinkedSecretResolver(hdr, format, { snapshot.dek() }, { snapshot.entries() })
                .extract(plaintext, entry.kindId, id, partName, mutableSetOf(id))
        } finally {
            snapshot.close()
        }
    }

    /**
     * Lists all credential IDs in the store.
     * @return List of credential IDs (never returns values)
     * @throws SecretStorePassphraseMismatchException if the passphrase does not
     *   match the store: asking what a store holds is asking it to open, and a
     *   passphrase that does not match gets an answer, not a list
*/
    override fun list(): List<CredentialsId> {
        requireOpen()
        if (!Files.exists(file) || Files.size(file) == 0L) return emptyList()
        val fileBytes = readStoreFile()
        validatePassphrase(fileBytes)
        val hdr = readHeader(fileBytes)
        // The codec reads the body in the framing of the version the header
        // declares, and refuses a body that is not that body.
        return StoreCodec.of(hdr.version).read(fileBytes).keys.map { CredentialsId.from(it) }
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
        requireOpen()
        withExclusiveLock {
            if (!Files.exists(file) || Files.size(file) == 0L) return
            val fileBytes = readStoreFile()
            validatePassphrase(fileBytes)
            val hdr = readHeader(fileBytes)

            // The store keeps the format it has: the entries are read and
            // written back in the framing of the version its header declares.
            val format = StoreCodec.of(hdr.version)
            val entries: MutableMap<String, StoreEntry> = format.read(fileBytes).toMutableMap()
            entries.remove(id.value) ?: return // Not found, no-op

            // Rewrite without the removed entry
            val out = ByteArrayOutputStream()
            StoreCodec.writeHeader(out, hdr)
            for (entry in entries.values) {
                out.write(format.encode(entry))
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
        requireOpen()
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
            try {
                // Get DEK from header (reuse existing DEK)
                val dek = secret(CredentialCrypto.unwrapDek(kek.bytes(), source.wrappedDek))
                try {
                    // The header the store will carry and the entries it will hold
                    // under it. The upgrade is decided in ONE place, so the rule that
                    // seals an entry cannot be one thing here and another there.
                    val upgrade = upgrade(dek.bytes(), source, fileBytes)

                    if (!upgrade.entries.containsKey(id.value)) {
                        throw SecretStoreTamperException("Credential not found: ${id.value}")
                    }

                    // Serialize and encrypt the new credential
                    val credentialBytes = CredentialSerializer.serializeCredential(credential)
                    val kindId = CredentialKinds.idOf(credential)

                    // The format of the header the store will carry decides how the
                    // entry is framed and with what AAD it is sealed.
                    val format = StoreCodec.of(upgrade.header.version)
                    val sealed = AeadCipher.encrypt(
                        dek.bytes(),
                        credentialBytes,
                        format.aad(upgrade.header, id, kindId),
                    )

                    // Update entries
                    upgrade.entries[id.value] = StoreEntry(id, sealed, kindId)

                    // Atomic write - always write header
                    val out = ByteArrayOutputStream()
                    StoreCodec.writeHeader(out, upgrade.header)
                    for (entry in upgrade.entries.values) {
                        out.write(format.encode(entry))
                    }

                    commit(publishable(out.toByteArray(), upgrade, dek.bytes()))
                } finally {
                    dek.wipe()
                }
            } finally {
                kek.wipe()
            }
        }
    }

    /**
     * Replaces the bytes of ONE secret, in the format the store writes.
     *
     * The bytes are wrapped as a [SecretText] and handed to [rotate], which
     * is the one authority that writes this store: it knows which version the
     * store is in, and it seals every entry under the header the store is
     * published with. A store that holds the legacy format is migrated by
     * that write.
     *
     * A rotation needs a credential to rotate. A store that holds none under
     * that name is refused and nothing is written, so a rotation never
     * brings a credential into existence.
     *
     * @param id The credential ID
     * @param newBytes The new secret bytes
     * @throws CredentialsStoreEmptySecretException if newBytes is empty
     * @throws SecretStoreTamperException if the store holds no credential
     *   under that name
     * @throws CredentialsStoreCommitException when the store could not be
     *   replaced. The store keeps the content it had.
     */
    override fun rotateBytes(id: CredentialsId, newBytes: ByteArray) {
        requireOpen()
        if (newBytes.isEmpty()) throw CredentialsStoreEmptySecretException()

        rotate(id, SecretText(id, CredentialScope.GLOBAL, newBytes))
    }

    /**
     * Closes the store, wiping the passphrase and every key it holds.
     *
     * The close is idempotent: closing a closed store is not an error, and the
     * second close does not wipe anything twice. The store stays closed: every
     * later operation is refused, naming that state, because a store that
     * answers after its close answers with a passphrase it no longer has.
     *
     * The passphrase that is wiped is the one the caller gave: it is the same
     * array, so the caller can see that it is gone.
    */
    override fun close() {
        if (closed) return
        closed = true
        passphrase.fill('\u0000')
        for (buffer in liveSecrets.toList()) {
            buffer.wipe()
        }
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
        val destination = source.copy(version = StoreCodec.VERSION_CURRENT)
        val sourceFormat = StoreCodec.of(source.version)
        val destinationFormat = StoreCodec.of(destination.version)
        val migrating = source.version != destination.version
        val entries: MutableMap<String, StoreEntry> = when {
            fileBytes == null || fileBytes.size <= StoreCodec.HEADER_SIZE -> mutableMapOf()
            migrating -> sourceFormat.read(fileBytes).mapValues { (entryId, entry) ->
                val id = CredentialsId.from(entryId)
                val plaintext = try {
                    AeadCipher.decrypt(dek, entry.sealed, sourceFormat.aad(source, id, entry.kindId))
                } catch (e: javax.crypto.AEADBadTagException) {
                    throw SecretStoreTamperException(
                        "the v1 entry '${id.value}' does not open; the store was not migrated " +
                            "and keeps the content it had",
                        e,
                    )
                }
                // A v1 entry holds the secret itself; an entry of the current
                // format holds the credential in the canonical part form its
                // reader deserializes. The migration moves the secret from one
                // form to the other, so the entry reads back as the credential
                // it was instead of as a blob nobody can interpret.
                val canonical = CredentialSerializer.serializeCredential(
                    SecretText(id, CredentialScope.GLOBAL, plaintext),
                )
                StoreEntry(
                    id,
                    AeadCipher.encrypt(
                        dek,
                        canonical,
                        destinationFormat.aad(destination, id, CredentialKinds.SECRET_TEXT),
                    ),
                    CredentialKinds.SECRET_TEXT,
                )
            }.toMutableMap()
            else -> sourceFormat.read(fileBytes).toMutableMap()
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
        val format = StoreCodec.of(published.version)
        for (entry in format.read(bytes).values) {
            try {
                AeadCipher.decrypt(dek, entry.sealed, format.aad(published, entry.id, entry.kindId))
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

    private fun loadOrCreateHeader(): StoreHeader {
        if (Files.exists(file) && Files.size(file) > 0) {
            return readHeader(readStoreFile())
        }
        // Create new header with fresh salt - use format for new stores
        val salt = ByteArray(SALT_SIZE)
        secureRandom.nextBytes(salt)
        return StoreCodec.newHeader(salt, ByteArray(WRAPPED_DEK_SIZE))
    }

    private fun readHeader(bytes: ByteArray): StoreHeader =
        StoreCodec.readHeader(bytes)

}
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
    private val format: StoreFormat,
    private val dek: () -> ByteArray,
    private val entries: () -> Map<String, StoreEntry>,
) : LinkedSecretWalker {
    /**
     * Extracts a named part from deserialized credential bytes using the canonical part format.
     * Format per part: [nameLen:1][name][len:4 BE][bytes]
     * After reading nameLen and name, position is at len(4). We must also skip len to reach bytes.
    */
    override fun extract(
        plaintext: ByteArray,
        kindId: Short,
        id: CredentialsId,
        partName: String,
        visited: MutableSet<CredentialsId>,
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
            CredentialKinds.SECRET_TEXT -> {
                requirePart(partName == "value", partName, "SecretText")
                buf.get().toInt() // partCount
                SecretHandle.secret(CredentialPartReader.readInlinePart(buf, 0))
            }
            CredentialKinds.USERNAME_PASSWORD -> {
                buf.get().toInt() // partCount
                SecretHandle.secret(CredentialPartReader.readPositionalPart(buf, partName, "UsernamePassword", "username" to 0, "password" to 1))
            }
            CredentialKinds.SSH_PRIVATE_KEY -> {
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
            CredentialKinds.SECRET_FILE -> {
                buf.get().toInt() // partCount
                SecretHandle.secret(CredentialPartReader.readPositionalPart(buf, partName, "SecretFile", "originalName" to 0, "content" to 1))
            }
            CredentialKinds.CERTIFICATE -> {
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
            CredentialKinds.ZIP -> SecretHandle.secret(envelope { CredentialPartReader.readZipPart(buf, partName) })
            CredentialKinds.USERNAME_COLON_PASSWORD -> {
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

        // The AAD of the referenced entry comes from the format of the store
        // it lives in, so the seal that opens it is the one that format seals
        // with.
        val aad = format.aad(hdr, refId, refEntry.kindId)
        val plaintext = try {
            AeadCipher.decrypt(dek, refEntry.sealed, aad)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw LocalSecretStore.SecretStoreTamperException("Tamper detected for referenced credential: ${refId.value}", e)
        }

        // For SecretText: directly extract and return the value
        if (refEntry.kindId == CredentialKinds.SECRET_TEXT) {
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
        if (refEntry.kindId == CredentialKinds.SSH_PRIVATE_KEY) {
            return extractLinkedRefFromSshPrivateKey(plaintext, visited)
        }

        // For Certificate: follow the password LinkedSecretRef chain
        if (refEntry.kindId == CredentialKinds.CERTIFICATE) {
            return extractLinkedRefFromCertificate(plaintext, visited)
        }

        // Other credential types cannot be the target of a LinkedSecretRef for passphrase/password
        throw LinkedSecretReferenceTypeMismatchException(
            refId,
            "SecretText",
            CredentialKinds.nameOf(refEntry.kindId) ?: "an unknown kind of credential",
        )
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
