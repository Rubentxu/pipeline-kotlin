package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import java.nio.file.Path

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
 * - `add [--kind <kind>] [--from-stdin] <id>` — stores a credential. Without
 *   `--from-stdin` the value is asked for at the terminal; with it, the value
 *   is read from standard input.
 * - `list` — lists all credential IDs with kind and scope (never values)
 * - `remove <id>` — removes a credential
 * - `rotate [--kind <kind>] [--from-stdin] <id>` — replaces a stored credential
 *   with a new value, read the same way `add` reads it.
 *
 * ## Seeding without a terminal
 *
 * `--from-stdin` exists so a store can be prepared by a script. The passphrase
 * already arrives through the environment, but the value itself used to have
 * exactly one possible source, an interactive terminal, which left every
 * non-interactive use of `add` -- provisioning, containers, continuous
 * integration -- with no way in. The value never reaches a log either way: it
 * is read once, held in memory for as long as the call takes, and handed
 * straight to the store.
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

    // The store location is decided by CredentialsStorePath, the ONE authority.
    // This comment used to spell the rule out here AND claim the runtime agreed
    // (Main.kt, composeWithCredentialsExecutor) — and the runtime resolved a
    // different default, because it took <controlRoot>/../credentials.bin. Two
    // copies of one rule is how `credentials add` came to write somewhere the run
    // never looked. See CredentialsStorePath's KDoc.
    private val STORE_FILE: Path = resolveStoreFile()

    /**
     * The CLI's store path, delegated to [CredentialsStorePath] rather than
     * recomputed. Internal visibility exists for the contract regression test
     * (R2, WU-RP-042): CLI and runtime MUST resolve the same store.
     */
    internal fun resolveStoreFile(): Path = CredentialsStorePath.fromEnvironment(System.getenv())

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
                println("Add:    pipeline credentials add [--kind <kind>] [--from-stdin] <id>")
                println("        --kind values: ${CredentialKind.ALL_KINDS}")
                println("        --from-stdin reads the value from standard input")
                println("List:   pipeline credentials list")
                println("Remove: pipeline credentials remove <id>")
                println("Rotate: pipeline credentials rotate [--kind <kind>] [--from-stdin] <id>")
                1
            }
        }
    }

    private fun add(args: List<String>): Int {
        val parsed = parseWriteArguments(args) ?: return 1
        return try {
            val store = openStore()
            store.add(CredentialsId.from(parsed.id), readCredential(parsed.kind, parsed.fromStdin))
            println("Credential '${parsed.id}' stored successfully.")
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
        val parsed = parseWriteArguments(args) ?: return 1
        return try {
            val store = openStore()
            store.rotate(CredentialsId.from(parsed.id), readCredential(parsed.kind, parsed.fromStdin))
            println("Credential '${parsed.id}' rotated successfully.")
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

    /** The two commands that write a value share these arguments exactly. */
    private data class WriteArguments(
        val kind: CredentialKind,
        val id: String,
        val fromStdin: Boolean,
    )

    /**
     * Reads the arguments of `add` and `rotate`, which differ only in what they
     * do with the result. Returns null after printing the reason, so a caller
     * never has to invent a usage string of its own and two commands cannot
     * disagree about which flags exist.
     */
    private fun parseWriteArguments(args: List<String>): WriteArguments? {
        var kind: CredentialKind? = null
        var id: String? = null
        var fromStdin = false

        val it = args.iterator()
        while (it.hasNext()) {
            val arg = it.next()
            when {
                arg == "--kind" && it.hasNext() -> {
                    val kindStr = it.next()
                    kind = CredentialKind.fromString(kindStr)
                    if (kind == null) {
                        println("Error: unknown kind '$kindStr'. Valid kinds: ${CredentialKind.ALL_KINDS}")
                        return null
                    }
                }

                arg == "--from-stdin" -> fromStdin = true
                !arg.startsWith("--") && id == null -> id = arg
                // A flag whose value is missing is a typo the user should see,
                // not a silently ignored argument.
                arg.startsWith("--") -> {
                    println("Error: $arg needs a value")
                    return null
                }
            }
        }

        val idArg = id ?: run {
            println("Error: missing credential id")
            return null
        }
        return WriteArguments(kind ?: CredentialKind.SECRET_TEXT, idArg, fromStdin)
    }

    private fun openStore(): LocalSecretStore =
        LocalSecretStore(STORE_FILE, PassphraseResolver.resolve())

    private fun readCredential(kind: CredentialKind, fromStdin: Boolean): Credential =
        if (fromStdin) StreamCredentialPrompts.read(kind, System.`in`)
        else CredentialPrompts.read(kind)
}
