package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
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
        return SecretText(id = PENDING_STORE_ID, scope = CredentialScope.GLOBAL, bytes = bytes)
    }

    fun usernamePassword(username: String, password: ByteArray): UsernamePassword {
        requireNotBlank(username, "username")
        requireNotEmpty(password, "password")
        return UsernamePassword(
            id = PENDING_STORE_ID,
            scope = CredentialScope.GLOBAL,
            username = username,
            password = password,
        )
    }

    fun usernameColonPassword(user: String, pass: ByteArray): UsernameColonPassword {
        requireNotBlank(user, "username")
        requireNotEmpty(pass, "password")
        return UsernameColonPassword(
            id = PENDING_STORE_ID,
            scope = CredentialScope.GLOBAL,
            user = user,
            pass = pass,
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
        return SshPrivateKey(
            id = PENDING_STORE_ID,
            scope = CredentialScope.GLOBAL,
            username = username,
            privateKey = privateKey,
            passphraseRef = null,
        )
    }

    fun secretFile(bytes: ByteArray, originalName: String): SecretFile {
        requireNotEmpty(bytes, "file is empty")
        return SecretFile(
            id = PENDING_STORE_ID,
            scope = CredentialScope.GLOBAL,
            bytes = bytes,
            originalName = originalName,
        )
    }

    fun certificate(keystore: ByteArray, alias: String?): Certificate {
        requireNotEmpty(keystore, "keystore is empty")
        require(isPkcs12(keystore)) { "invalid PKCS#12 keystore" }
        return Certificate(
            id = PENDING_STORE_ID,
            scope = CredentialScope.GLOBAL,
            keystore = keystore,
            passwordRef = null,
            alias = alias?.ifEmpty { null },
        )
    }

    fun zip(bytes: ByteArray): Zip {
        requireNotEmpty(bytes, "ZIP archive is empty")
        val entries = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entries[entry.name] = zis.readBytes()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid ZIP archive: ${e.message}", e)
        }
        require(entries.isNotEmpty()) { "ZIP archive is empty" }
        return Zip(id = PENDING_STORE_ID, scope = CredentialScope.GLOBAL, entries = entries)
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
}
