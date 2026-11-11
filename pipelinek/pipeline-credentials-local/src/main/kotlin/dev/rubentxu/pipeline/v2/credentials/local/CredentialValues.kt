package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import java.security.KeyStore
import java.util.zip.ZipInputStream

/**
 * Builds one credential of each supported kind from plain values, applying
 * every rule about what makes a value acceptable.
 * Two callers need this and they must not disagree. An interactive terminal
 * collects the values one question at a time; a redirected stream receives them
 * already assembled. Both end up here, so a store seeded from a pipe holds the
 * same bytes as one seeded at the prompt, and an empty password is rejected by
 * the same check either way. The rules live here once; the two callers only
 * decide where the values come from.
 * Nothing in this object reads the console, a stream or the filesystem: the
 * file-backed kinds take bytes the caller has already read. That keeps the
 * checks pure and testable, and keeps a path typed at a terminal from becoming
 * a second way to reach a file the caller did not choose.
 * The bytes a caller brings are bounded, because the caller reads them from
 * outside and nothing there is bounded by this module: an archive says how
 * much it expands to, and the store says how much it can hold. Both answers
 * are applied here, and both name the ceiling they crossed without saying
 * what the credential held.
*/
internal object CredentialValues {

    /**
     * Placeholder carried by a credential until the store keys it. The store
     * entry key is the id supplied on the command line, so the embedded id is
     * never the one that matters -- but [CredentialsId] rejects a blank value,
     * so it cannot be left empty. A name that is obviously not a real id makes
     * an accidental use of it visible instead of plausible.
*/
    private val PENDING_STORE_ID = CredentialsId("<pending-store-id>")

    fun secretText(bytes: ByteArray): SecretText {
        requireNotEmpty(bytes, "secret")
        return fitting(SecretText(id = PENDING_STORE_ID, scope = CredentialScope.GLOBAL, bytes = bytes))
    }

    fun usernamePassword(username: String, password: ByteArray): UsernamePassword {
        requireNotBlank(username, "username")
        requireNotEmpty(password, "password")
        return fitting(
            UsernamePassword(
                id = PENDING_STORE_ID,
                scope = CredentialScope.GLOBAL,
                username = username,
                password = password,
            ),
        )
    }

    fun usernameColonPassword(user: String, pass: ByteArray): UsernameColonPassword {
        requireNotBlank(user, "username")
        requireNotEmpty(pass, "password")
        return fitting(
            UsernameColonPassword(
                id = PENDING_STORE_ID,
                scope = CredentialScope.GLOBAL,
                user = user,
                pass = pass,
            ),
        )
    }

    fun sshPrivateKey(username: String, privateKey: ByteArray): SshPrivateKey {
        requireNotBlank(username, "username")
        val text = String(privateKey, Charsets.UTF_8)
        require(text.isNotEmpty()) { "private key cannot be empty" }
        // The same two markers the format is defined by. Checking them here
        // rather than in each caller means a key pasted at the prompt and one
        // piped in are accepted or refused on the same grounds.
        require(text.contains("-----BEGIN") && text.contains("-----END")) {
            "invalid SSH private key: missing PEM header/footer"
        }
        return fitting(
            SshPrivateKey(
                id = PENDING_STORE_ID,
                scope = CredentialScope.GLOBAL,
                username = username,
                privateKey = privateKey,
                passphraseRef = null,
            ),
        )
    }

    fun secretFile(bytes: ByteArray, originalName: String): SecretFile {
        requireNotEmpty(bytes, "file is empty")
        return fitting(
            SecretFile(
                id = PENDING_STORE_ID,
                scope = CredentialScope.GLOBAL,
                bytes = bytes,
                originalName = originalName,
            ),
        )
    }

    fun certificate(keystore: ByteArray, alias: String?): Certificate {
        requireNotEmpty(keystore, "keystore is empty")
        require(isPkcs12(keystore)) { "invalid PKCS#12 keystore" }
        return fitting(
            Certificate(
                id = PENDING_STORE_ID,
                scope = CredentialScope.GLOBAL,
                keystore = keystore,
                passwordRef = null,
                alias = alias?.ifEmpty { null },
            ),
        )
    }

    /**
     * The credential of an archive, expanded under the ceilings of [limits].
     *
     * ## Why the expansion is bounded
     *
     * An archive decides how much it expands to, and an entry that names a
     * megabyte can carry a gigabyte. The bytes are counted while the entry is
     * being read and the ceiling is applied then, so the process never holds
     * an entry larger than the ceiling allows, and never holds an archive at
     * all.
     *
     * ## Why the size an entry declares is not believed
     *
     * The header of an entry says how big it is, and that is a claim. The
     * bytes are counted instead, because the claim is what an archive that
     * wants to exhaust a heap would lie about.
     *
     * ## Why two entries cannot carry one name
     *
     * A credential holds a map of entries, so two entries with one name leave
     * one of them out, and the operator is never told which one. The archive
     * is refused instead, and the refusal does not name the entries.
     *
     * ## Why the names are checked here
     *
     * The form the store keeps writes the name of an entry in one byte, so a
     * name it cannot carry is refused when the credential is written, with a
     * message about the store's own file. The archive is refused at the
     * boundary instead, where the operator can be told what to change.
     */
    fun zip(bytes: ByteArray, limits: CredentialImportLimits = CredentialImportLimits.DEFAULT): Zip {
        requireNotEmpty(bytes, "ZIP archive is empty")
        if (bytes.size > limits.maxArchiveBytes) {
            throw CredentialImportRefusal.InputTooLarge(ZIP_ARCHIVE, limits.maxArchiveBytes)
        }
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entries.size >= limits.maxEntries) {
                        throw CredentialImportRefusal.TooManyEntries(limits.maxEntries)
                    }
                    val name = entry.name
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    if (nameBytes.isEmpty() || nameBytes.size > MAX_ENTRY_NAME_BYTES) {
                        throw CredentialImportRefusal.EntryNameNotCarried(MAX_ENTRY_NAME_BYTES)
                    }
                    if (entries.containsKey(name)) {
                        throw CredentialImportRefusal.DuplicateEntryNames()
                    }
                    val content = BoundedInput.read(zis, limits.maxEntryBytes, ENTRY_OF_ARCHIVE)
                    total += content.size
                    if (total > limits.maxContentBytes) {
                        throw CredentialImportRefusal.TooLargeInTotal(limits.maxContentBytes)
                    }
                    entries[name] = content
                    entry = zis.nextEntry
                }
            }
        } catch (refusal: CredentialImportRefusal) {
            throw refusal
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid ZIP archive: ${e.message}", e)
        }
        require(entries.isNotEmpty()) { "ZIP archive is empty" }
        return fitting(Zip(id = PENDING_STORE_ID, scope = CredentialScope.GLOBAL, entries = entries))
    }

    /**
     * The credential, if the store can hold it.
     *
     * The store seals the form a credential is kept in, and it refuses to open
     * an entry whose length is above the ceiling that form allows. A
     * credential that does not fit is therefore refused here, where the
     * operator can be told, instead of being written into a store that cannot
     * give it back.
     */
    private fun <T : Credential> fitting(credential: T): T {
        val canonical = try {
            CredentialSerializer.serializeCredential(credential)
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            throw CredentialImportRefusal.NotCarriedByTheForm(e)
        }
        if (canonical.size > StoreCodec.MAX_PLAINTEXT_LENGTH) {
            throw CredentialImportRefusal.LargerThanTheStoreHolds(StoreCodec.MAX_PLAINTEXT_LENGTH)
        }
        return credential
    }

    private fun isPkcs12(bytes: ByteArray): Boolean = try {
        KeyStore.getInstance("PKCS12").load(bytes.inputStream(), null)
        true
    } catch (e: Exception) {
        false
    }

    private fun requireNotEmpty(value: ByteArray, what: String) {
        require(value.isNotEmpty()) { "$what cannot be empty" }
    }

    private fun requireNotBlank(value: String, what: String) {
        require(value.isNotBlank()) { "$what cannot be empty" }
    }

    /** How an archive is named when a refusal has to say what was too large. */
    private const val ZIP_ARCHIVE = "the ZIP archive"

    /** How an entry of an archive is named when a refusal has to say it. */
    private const val ENTRY_OF_ARCHIVE = "an entry of the archive"

    /** The bytes the name of an entry may take: the form the store keeps writes it in one byte. */
    private const val MAX_ENTRY_NAME_BYTES = 255
}
