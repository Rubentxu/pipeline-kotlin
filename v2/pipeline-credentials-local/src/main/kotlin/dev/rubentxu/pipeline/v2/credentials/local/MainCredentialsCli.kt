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

    // Store path resolution MUST match the runtime contract (Main.kt,
    // composeWithCredentialsExecutor): PIPELINE_CREDENTIALS_STORE env override,
    // else the canonical default ~/.pipeline/credentials.bin. The CLI previously
    // ignored the env var, so `credentials add` wrote to the default file while
    // the run read the env-provided one (divergence found by WU-RP-042 S1 R2).
    private val STORE_FILE: Path = resolveStoreFile()

    /**
     * Single authority for the CLI store path. Internal visibility exists for
     * the contract regression test (R2, WU-RP-042): CLI and runtime MUST
     * resolve the same store.
     */
    internal fun resolveStoreFile(): Path =
        System.getenv("PIPELINE_CREDENTIALS_STORE")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".pipeline", "credentials.bin")

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

            const val ALL_KINDS = "secret-text, username-password, ssh-private-key, secret-file, certificate, zip, username-colon-password"
        }
    }

    @JvmStatic
    fun main(args: Array<String>): Int {
        return when (args.firstOrNull()) {
            "add" -> add(args.drop(1))
            "list" -> list()
            "remove" -> remove(args.drop(1))
            "rotate" -> rotate(args.drop(1))
            else -> {
                println("Usage: pipeline credentials {add|list|remove|rotate} [args]")
                println("Add:    pipeline credentials add [--kind <kind>] <id>")
                println("        --kind values: ${CredentialKind.ALL_KINDS}")
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
                        println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.ALL_KINDS}")
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
                CredentialKind.SECRET_TEXT -> CredentialPrompts.readSecretText()
                CredentialKind.USERNAME_PASSWORD -> CredentialPrompts.readUsernamePassword()
                CredentialKind.SSH_PRIVATE_KEY -> CredentialPrompts.readSshPrivateKey()
                CredentialKind.SECRET_FILE -> CredentialPrompts.readSecretFile()
                CredentialKind.CERTIFICATE -> CredentialPrompts.readCertificate()
                CredentialKind.ZIP -> CredentialPrompts.readZip()
                CredentialKind.USERNAME_COLON_PASSWORD -> CredentialPrompts.readUsernameColonPassword()
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

    private fun list(): Int {
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
                        println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.ALL_KINDS}")
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
                CredentialKind.SECRET_TEXT -> CredentialPrompts.readSecretText()
                CredentialKind.USERNAME_PASSWORD -> CredentialPrompts.readUsernamePassword()
                CredentialKind.SSH_PRIVATE_KEY -> CredentialPrompts.readSshPrivateKey()
                CredentialKind.SECRET_FILE -> CredentialPrompts.readSecretFile()
                CredentialKind.CERTIFICATE -> CredentialPrompts.readCertificate()
                CredentialKind.ZIP -> CredentialPrompts.readZip()
                CredentialKind.USERNAME_COLON_PASSWORD -> CredentialPrompts.readUsernameColonPassword()
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
