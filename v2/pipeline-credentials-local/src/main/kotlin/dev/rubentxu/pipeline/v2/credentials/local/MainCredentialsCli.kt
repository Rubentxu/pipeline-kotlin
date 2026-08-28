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
import java.io.Console
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * Main credentials CLI — subcommand dispatcher for credential store operations.
 *
 * ## Exit Codes
 *
 * - 0: Success
 * - 1: Usage error
 * - 2: Missing store
 * - 3: Wrong passphrase
 * - 4: Tamper detected
 *
 * ## Subcommands
 *
 * - `add [--kind <kind>] <id>` — prompts for secret via Console.readPassword(), stores with passphrase
 * - `list` — lists all credential IDs with kind and scope (never values)
 * - `remove <id>` — removes a credential
 * - `rotate [--kind <kind>] <id>` — re-encrypts with new secret (prompts via Console.readPassword())
 *
 * ## Supported Kinds (--kind)
 *
 * - `secret-text`: raw string secret (default if --kind omitted)
 * - `username-password`: username + password pair
 * - `ssh-private-key`: SSH private key with optional passphrase
 * - `secret-file`: file contents as secret
 * - `certificate`: PKCS#12 keystore
 * - `zip`: ZIP archive
 * - `username-colon-password`: colon-joined user:pass string
 */
object MainCredentialsCli {

    private val STORE_FILE: Path = Path.of(System.getProperty("user.home"), ".pipeline", "credentials.bin")

    // Supported credential kinds
    enum class CredentialKind {
        SECRET_TEXT,
        USERNAME_PASSWORD,
        SSH_PRIVATE_KEY,
        SECRET_FILE,
        CERTIFICATE,
        ZIP,
        USERNAME_COLON_PASSWORD;

        companion object {
            fun fromString(s: String): CredentialKind? = when (s.lowercase().replace("-", "")) {
                "secrettext", "secret-text", "secret_text" -> SECRET_TEXT
                "usernamepassword", "username-password", "username_password" -> USERNAME_PASSWORD
                "sshprivatekey", "ssh-private-key", "ssh_private_key" -> SSH_PRIVATE_KEY
                "secretfile", "secret-file", "secret_file" -> SECRET_FILE
                "certificate" -> CERTIFICATE
                "zip" -> ZIP
                "usernamecolonpassword", "username-colon-password", "username_colon_password" -> USERNAME_COLON_PASSWORD
                else -> null
            }

            fun allKinds(): String = "secret-text, username-password, ssh-private-key, secret-file, certificate, zip, username-colon-password"
        }
    }

    @JvmStatic
    fun main(args: Array<String>): Int {
        return when (args.firstOrNull()) {
            "add" -> add(args.drop(1))
            "list" -> list(args.drop(1))
            "remove" -> remove(args.drop(1))
            "rotate" -> rotate(args.drop(1))
            else -> {
                println("Usage: pipeline credentials {add|list|remove|rotate} [args]")
                println("Add:    pipeline credentials add [--kind <kind>] <id>")
                println("        --kind values: ${CredentialKind.allKinds()}")
                println("List:   pipeline credentials list")
                println("Remove: pipeline credentials remove <id>")
                println("Rotate: pipeline credentials rotate [--kind <kind>] <id>")
                1
            }
        }
    }

    private fun add(args: List<String>): Int {
        var kind: CredentialKind? = null
        var idArg: String? = null

        // Parse arguments
        val it = args.iterator()
        while (it.hasNext()) {
            val arg = it.next()
            when {
                arg == "--kind" && it.hasNext() -> {
                    val kindStr = it.next()
                    kind = CredentialKind.fromString(kindStr)
                    if (kind == null) {
                        println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.allKinds()}")
                        return 1
                    }
                }
                !arg.startsWith("--") && idArg == null -> idArg = arg
            }
        }

        val id = idArg ?: run {
            println("Error: missing credential id")
            return 1
        }

        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)

            val credential = when (kind ?: CredentialKind.SECRET_TEXT) {
                CredentialKind.SECRET_TEXT -> readSecretText()
                CredentialKind.USERNAME_PASSWORD -> readUsernamePassword()
                CredentialKind.SSH_PRIVATE_KEY -> readSshPrivateKey()
                CredentialKind.SECRET_FILE -> readSecretFile()
                CredentialKind.CERTIFICATE -> readCertificate()
                CredentialKind.ZIP -> readZip()
                CredentialKind.USERNAME_COLON_PASSWORD -> readUsernameColonPassword()
            }

            store.add(CredentialsId.from(id), credential)
            println("Credential '$id' stored successfully.")
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            println("Error: empty secret not allowed")
            1
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
            1
        } catch (e: Exception) {
            println("Error: ${e.message}")
            1
        }
    }

    private fun readSecretText(): SecretText {
        print("Enter secret value: ")
        val secret = System.console()?.readPassword() ?: error("no TTY available")
        if (secret.isEmpty()) error("empty secret not allowed")
        return SecretText(
            id = CredentialsId(""),
            scope = CredentialScope.GLOBAL,
            bytes = String(secret).toByteArray()
        )
    }

    private fun readUsernamePassword(): UsernamePassword {
        print("Enter username: ")
        val username = readLine() ?: error("no TTY available")
        if (username.isEmpty()) error("username cannot be empty")
        print("Enter password: ")
        val password = System.console()?.readPassword() ?: error("no TTY available")
        if (password.isEmpty()) error("password cannot be empty")
        return UsernamePassword(
            id = CredentialsId(""),
            scope = CredentialScope.GLOBAL,
            username = username,
            password = String(password).toByteArray()
        )
    }

    private fun readSshPrivateKey(): SshPrivateKey {
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
            id = CredentialsId(""),
            scope = CredentialScope.GLOBAL,
            username = username,
            privateKey = privateKey.toByteArray(),
            passphraseRef = null // passphrase not stored as separate credential in simple CLI
        )
    }

    private fun readSecretFile(): SecretFile {
        print("Enter file path: ")
        val path = readLine() ?: error("no TTY available")
        if (path.isEmpty()) error("file path cannot be empty")
        val file = Paths.get(path)
        if (!Files.exists(file)) error("file does not exist: $path")
        val bytes = Files.readAllBytes(file)
        if (bytes.isEmpty()) error("file is empty")
        return SecretFile(
            id = CredentialsId(""),
            scope = CredentialScope.GLOBAL,
            bytes = bytes,
            originalName = file.fileName.toString()
        )
    }

    private fun readCertificate(): Certificate {
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
            id = CredentialsId(""),
            scope = CredentialScope.GLOBAL,
            keystore = keystoreBytes,
            passwordRef = null,
            alias = alias.ifEmpty { null }
        )
    }

    private fun readZip(): Zip {
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
            id = CredentialsId(""),
            scope = CredentialScope.GLOBAL,
            entries = entries
        )
    }

    private fun readUsernameColonPassword(): UsernameColonPassword {
        print("Enter username: ")
        val username = readLine() ?: error("no TTY available")
        if (username.isEmpty()) error("username cannot be empty")
        print("Enter password: ")
        val password = System.console()?.readPassword() ?: error("no TTY available")
        if (password.isEmpty()) error("password cannot be empty")
        return UsernameColonPassword(
            id = CredentialsId(""),
            scope = CredentialScope.GLOBAL,
            user = username,
            pass = String(password).toByteArray()
        )
    }

    private fun list(args: List<String>): Int {
        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)
            val ids = store.list()
            if (ids.isEmpty()) {
                println("No credentials stored.")
            } else {
                println("Stored credentials:")
                println("%-40s %-20s %-10s".format("ID", "KIND", "SCOPE"))
                println("-".repeat(70))
                for (id in ids) {
                    try {
                        val cred = store.get(id)
                        val kind = cred::class.simpleName ?: "Unknown"
                        val scope = cred.scope.name
                        println("%-40s %-20s %-10s".format(id.value, kind, scope))
                    } catch (e: Exception) {
                        println("%-40s %-20s %-10s".format(id.value, "Unknown", "Unknown"))
                    }
                }
            }
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        } catch (e: Exception) {
            println("Error listing credentials: ${e.message}")
            1
        }
    }

    private fun remove(args: List<String>): Int {
        val id = args.firstOrNull() ?: run {
            println("Usage: pipeline credentials remove <id>")
            return 1
        }
        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)
            store.remove(CredentialsId.from(id))
            println("Credential '$id' removed.")
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            4
        } catch (e: Exception) {
            println("Error: ${e.message}")
            1
        }
    }

    private fun rotate(args: List<String>): Int {
        var kind: CredentialKind? = null
        var idArg: String? = null

        // Parse arguments
        val it = args.iterator()
        while (it.hasNext()) {
            val arg = it.next()
            when {
                arg == "--kind" && it.hasNext() -> {
                    val kindStr = it.next()
                    kind = CredentialKind.fromString(kindStr)
                    if (kind == null) {
                        println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.allKinds()}")
                        return 1
                    }
                }
                !arg.startsWith("--") && idArg == null -> idArg = arg
            }
        }

        val id = idArg ?: run {
            println("Error: missing credential id")
            return 1
        }

        return try {
            val passphrase = PassphraseResolver.resolve()
            val store = LocalSecretStore(STORE_FILE, passphrase)

            val credential = when (kind ?: CredentialKind.SECRET_TEXT) {
                CredentialKind.SECRET_TEXT -> readSecretText()
                CredentialKind.USERNAME_PASSWORD -> readUsernamePassword()
                CredentialKind.SSH_PRIVATE_KEY -> readSshPrivateKey()
                CredentialKind.SECRET_FILE -> readSecretFile()
                CredentialKind.CERTIFICATE -> readCertificate()
                CredentialKind.ZIP -> readZip()
                CredentialKind.USERNAME_COLON_PASSWORD -> readUsernameColonPassword()
            }

            store.rotate(CredentialsId.from(id), credential)
            println("Credential '$id' rotated successfully.")
            0
        } catch (e: LocalSecretStore.CredentialsStorePassphraseUnavailableException) {
            println("Error: ${e.message}")
            2
        } catch (e: LocalSecretStore.SecretStoreTamperException) {
            println("Error: store tampered: ${e.message}")
            4
        } catch (e: LocalSecretStore.CredentialsStoreEmptySecretException) {
            println("Error: empty secret not allowed")
            1
        } catch (e: IllegalArgumentException) {
            println("Error: ${e.message}")
            1
        } catch (e: Exception) {
            println("Error: ${e.message}")
            1
        }
    }
}
