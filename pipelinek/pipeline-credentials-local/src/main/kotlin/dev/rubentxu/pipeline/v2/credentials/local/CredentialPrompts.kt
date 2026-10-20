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
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * Interactive prompts that build one credential of each supported kind.
 *
 * Pure in the sense that matters here: each function reads from the console
 * and returns a fresh value, with no file handle, lock or cached state. They
 * live apart from [MainCredentialsCli] so the dispatch table there stays
 * about dispatching.
 */
internal object CredentialPrompts {

fun readSecretText(): SecretText {
    print("Enter secret value: ")
    val secret = System.console()?.readPassword() ?: error("no TTY available")
    if (secret.isEmpty()) error("empty secret not allowed")
    return SecretText(
        // WU-RP-042: the store entry key is the CLI-supplied id passed to
        // SecretStore.add(id, credential); the embedded credential.id is not
        // the entry key and MUST NOT be constructed blank (CredentialsId has a
        // non-blank invariant). The placeholder CredentialsId("") threw on
        // every `credentials add` invocation (regression found by the
        // installed-distribution UAT). Use a typed non-blank placeholder that
        // add() never persists as the entry key.
        id = CredentialsId("<pending-store-id>"),
        scope = CredentialScope.GLOBAL,
        bytes = String(secret).toByteArray()
    )
}

fun readUsernamePassword(): UsernamePassword {
    print("Enter username: ")
    val username = readLine() ?: error("no TTY available")
    if (username.isEmpty()) error("username cannot be empty")
    print("Enter password: ")
    val password = System.console()?.readPassword() ?: error("no TTY available")
    if (password.isEmpty()) error("password cannot be empty")
    return UsernamePassword(
        id = CredentialsId("<pending-store-id>"),
        scope = CredentialScope.GLOBAL,
        username = username,
        password = String(password).toByteArray()
    )
}

fun readSshPrivateKey(): SshPrivateKey {
    print("Enter SSH username: ")
    val username = readLine() ?: error("no TTY available")
    if (username.isEmpty()) error("username cannot be empty")
    println("Enter SSH private key (paste PEM content, end with a line containing '.':")
    val privateKeyLines = mutableListOf<String>()
    var ended = false
    while (!ended) {
        val line = readLine() ?: error("no TTY available")
        if (line.trim() == ".") ended = true
        else privateKeyLines.add(line)
    }
    val privateKey = privateKeyLines.joinToString("\n")
    if (privateKey.isEmpty()) error("private key cannot be empty")
    // Validate PEM format
    if (!privateKey.contains("-----BEGIN") || !privateKey.contains("-----END")) {
        error("invalid SSH private key: missing PEM header/footer")
    }
    print("Enter passphrase (leave empty for no passphrase): ")
    val passphraseChars = System.console()?.readPassword() ?: CharArray(0)
    return SshPrivateKey(
        id = CredentialsId("<pending-store-id>"),
        scope = CredentialScope.GLOBAL,
        username = username,
        privateKey = privateKey.toByteArray(),
        passphraseRef = null // passphrase not stored as separate credential in simple CLI
    )
}

fun readSecretFile(): SecretFile {
    print("Enter file path: ")
    val path = readLine() ?: error("no TTY available")
    if (path.isEmpty()) error("file path cannot be empty")
    val file = Paths.get(path)
    if (!Files.exists(file)) error("file does not exist: $path")
    val bytes = Files.readAllBytes(file)
    if (bytes.isEmpty()) error("file is empty")
    return SecretFile(
        id = CredentialsId("<pending-store-id>"),
        scope = CredentialScope.GLOBAL,
        bytes = bytes,
        originalName = file.fileName.toString()
    )
}

fun readCertificate(): Certificate {
    print("Enter keystore file path (PKCS#12): ")
    val path = readLine() ?: error("no TTY available")
    if (path.isEmpty()) error("keystore path cannot be empty")
    val file = Paths.get(path)
    if (!Files.exists(file)) error("keystore file does not exist: $path")
    val keystoreBytes = Files.readAllBytes(file)
    // Validate PKCS#12 by trying to load it
    try {
        val ks = java.security.KeyStore.getInstance("PKCS12")
        ks.load(keystoreBytes.inputStream(), null)
    } catch (e: Exception) {
        error("invalid PKCS#12 keystore: ${e.message}")
    }
    print("Enter keystore password (leave empty for no password): ")
    val passwordChars = System.console()?.readPassword() ?: CharArray(0)
    print("Enter key alias (leave empty for default): ")
    val alias = readLine() ?: ""
    return Certificate(
        id = CredentialsId("<pending-store-id>"),
        scope = CredentialScope.GLOBAL,
        keystore = keystoreBytes,
        passwordRef = null,
        alias = alias.ifEmpty { null }
    )
}

fun readZip(): Zip {
    print("Enter ZIP file path: ")
    val path = readLine() ?: error("no TTY available")
    if (path.isEmpty()) error("ZIP path cannot be empty")
    val file = Paths.get(path)
    if (!Files.exists(file)) error("ZIP file does not exist: $path")
    val bytes = Files.readAllBytes(file)
    // Validate ZIP by checking entries
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
        error("invalid ZIP archive: ${e.message}")
    }
    if (entries.isEmpty()) error("ZIP archive is empty")
    return Zip(
        id = CredentialsId("<pending-store-id>"),
        scope = CredentialScope.GLOBAL,
        entries = entries
    )
}

fun readUsernameColonPassword(): UsernameColonPassword {
    print("Enter username: ")
    val username = readLine() ?: error("no TTY available")
    if (username.isEmpty()) error("username cannot be empty")
    print("Enter password: ")
    val password = System.console()?.readPassword() ?: error("no TTY available")
    if (password.isEmpty()) error("password cannot be empty")
    return UsernameColonPassword(
        id = CredentialsId("<pending-store-id>"),
        scope = CredentialScope.GLOBAL,
        user = username,
        pass = String(password).toByteArray()
    )
}
}
